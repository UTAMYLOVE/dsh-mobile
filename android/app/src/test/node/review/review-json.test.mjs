// Auto review's verdict found in the reviewer's answer, and the other load-time changes to the plugin (assets/dshn/review-json.mjs).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import {
  reviewJson, normalizeVerdict, verdictOf, patchAutoReview, install, reviewRetry, reviewFailure, REVIEW_FAILED_LINE, PATCHES, AUTO_REVIEW,
} from '../../../main/assets/dshn/review-json.mjs'

const verdict = '{"risk":"low","decision":"allow"}'
const deny = '{"risk":"high","decision":"deny","reason":"sends the key away"}'

test('a plain verdict is left as it is', () => {
  assert.equal(reviewJson(verdict), verdict)
  assert.equal(reviewJson(`  ${verdict}\n`), verdict)
})

test('a sentence before or after the verdict is dropped', () => {
  // The owner's failure: "Unexpected token 'T', "The comman"... is not valid JSON".
  assert.equal(reviewJson(`The command only lists files in the workspace.\n${verdict}`), verdict)
  assert.equal(reviewJson(`${verdict}\nThat is my decision.`), verdict)
})

test('a fenced block is read', () => {
  assert.equal(reviewJson('Here is the verdict:\n```json\n{"risk":"high","decision":"deny","reason":"deletes {all} files"}\n```'), '{"risk":"high","decision":"deny","reason":"deletes {all} files"}')
})

test('braces and quotes inside the reason do not confuse it', () => {
  const v = '{"risk":"medium","decision":"deny","reason":"writes \\"}\\" to {x}"}'
  assert.equal(reviewJson(`Reviewing. ${v}`), v)
  assert.deepEqual(JSON.parse(reviewJson(`Reviewing. ${v}`)).decision, 'deny')
})

test('braces the reviewer quotes from the command are not the verdict', () => {
  // The last {...} of these answers used to be the quoted command, and the review failed.
  assert.equal(reviewJson(`The command runs awk '{print $1}' on a log.\n${verdict}`), verdict)
  assert.equal(reviewJson(`${verdict}\nIt only runs awk '{print $1}' notes.txt.`), verdict)
  assert.equal(reviewJson(`It runs echo {} and nothing else. ${verdict}`), verdict)
  assert.equal(reviewJson(`${verdict} (it runs echo {})`), verdict)
  assert.equal(reviewJson(`It runs find . -exec rm {} \\; over the project.\n${deny}`), deny)
})

test('a quoted tool-arguments block before the verdict is skipped', () => {
  const answer = 'The pending call:\n```json\n{"command":"ls -la","description":"List files"}\n```\nVerdict:\n```json\n' + verdict + '\n```'
  assert.equal(reviewJson(answer), verdict)
})

test('two verdicts that disagree fail the review instead of picking one', () => {
  const answer = `At first: ${verdict}\nOn reflection: ${deny}`
  assert.equal(reviewJson(answer), answer.trim())
  assert.throws(() => JSON.parse(reviewJson(answer)))
  // The same verdict twice is fine.
  assert.equal(reviewJson(`${verdict}\nAgain: ${verdict}`), verdict)
})

test('an answer with no verdict still fails', () => {
  assert.throws(() => JSON.parse(reviewJson('The command looks safe to me.')))
  // An object without a decision is handed on as before, for the plugin's protocol check to refuse.
  assert.equal(reviewJson('Sure. {"risk":"low"}'), '{"risk":"low"}')
})

test('an allow that only adds a reason loses the reason; nothing else is changed', () => {
  assert.equal(normalizeVerdict('{"risk":"low","decision":"allow","reason":"x"}'), verdict)
  assert.equal(normalizeVerdict('{"risk":"medium","decision":"allow","reason":"asked for it"}'), '{"risk":"medium","decision":"allow"}')
  assert.equal(verdictOf('Looks fine. {"risk":"low","decision":"allow","reason":"reads a file"}'), verdict)
  // Shapes the plugin must keep refusing, or keep as they are.
  for (const v of [
    '{"risk":"low","decision":"deny","reason":"x"}', '{"risk":"low","decision":"deny"}', '{"risk":"high","decision":"allow","reason":"x"}',
    '{"risk":"low","decision":"allow","note":"x"}', '{"risk":"low","decision":"allow","reason":"x","extra":1}', deny, verdict,
    '{"risk":"low","decision":"allow","reason":"x","reason":"y"}', '{"decision":"deny","risk":"low","decision":"allow","reason":"x"}',
  ]) assert.equal(normalizeVerdict(v), v, v)
})

// --- the source changes ---

const plugin = [
  'function parseDecision(text) {\n\tconst value = JSON.parse(text);\n\treturn value;\n}',
  'async function classifyRisk(ctx, agent, exec, signal) {\n\tconst options = {\n\t\tmessages: [],\n\t\ttemperature: 0,\n\t\tsignal\n\t});\n\treturn readDecision(ctx.llm.stream(options));\n}',
  'async function gate() {\n\t\t\t\tif (!review.ok) return failed(exec, review.error);\n\t\t\t\tconst { decision } = review;\n}',
  'export { parseDecision };\n',
].join('\n')

test('every change lands in the expected source', () => {
  const out = patchAutoReview(plugin)
  for (const p of PATCHES) assert.ok(out.includes(p.to), p.name)
  assert.match(out, /__dshmReviewJson\(text\)/)
  assert.match(out, /__dshmReviewRetry\(\(\) => readDecision\(ctx\.llm\.stream\(options\)\), signal\)/)
  assert.match(out, /if \(!review\.ok && \(exec\.signal\.aborted \|\| ctx\.approval\.overrideOf\(agent\.session\) === "never"\)\) return failed/)
  assert.match(out, /__dshmReviewFailure\(review\.error\)/)
  assert.match(out, /reasoningEffort: h\.config\.reasoningEffort/)
})

test('a source missing one piece still gets the others, and an unknown source is left alone', () => {
  for (const missing of PATCHES) {
    const out = patchAutoReview(plugin.replace(missing.from, '/* changed in another dsh version */'))
    assert.ok(!out.includes(missing.to), missing.name)
    for (const p of PATCHES) if (p !== missing) assert.ok(out.includes(p.to), `${p.name} without ${missing.name}`)
  }
  assert.equal(patchAutoReview('export const x = 1\n'), 'export const x = 1\n')
  // A piece that occurs twice is not the expected source either.
  const twice = plugin + '\n' + PATCHES[1].from
  assert.ok(!patchAutoReview(twice).includes(PATCHES[1].to))
  assert.ok(AUTO_REVIEW.test('file:///opt/dsh/current/node_modules/@deepseek-ai/dsh-experimental-auto-review/lib/index.js'))
  assert.ok(!AUTO_REVIEW.test('file:///opt/dsh/current/node_modules/@deepseek-ai/dsh-core/lib/index.js'))
})

test('a failed review asks with one plain line', () => {
  assert.equal(reviewFailure(new Error('auto-review: reviewer ended with error SERVER: boom')), REVIEW_FAILED_LINE)
  assert.equal(REVIEW_FAILED_LINE, '自動審查這次沒有成功（網路或模型回覆格式），請你決定要不要執行。')
})

// --- the retry ---

const reviewerError = (code) => new Error(`auto-review: reviewer ended with error ${code}: e2e`)

/** A review that fails with each of [errors] in turn, then answers [answer]. */
function flaky(errors, answer = { risk: 'low', decision: 'allow' }) {
  const calls = { n: 0 }
  const run = async () => {
    const e = errors[calls.n++]
    if (e !== undefined) throw e
    return answer
  }
  return { run, calls }
}

test('a server, network or empty-answer failure is tried again, at most twice', async () => {
  for (const code of ['SERVER', 'TRANSPORT', 'EMPTY_RESPONSE']) {
    const once = flaky([reviewerError(code)])
    assert.deepEqual(await reviewRetry(once.run, undefined, [1, 1]), { risk: 'low', decision: 'allow' }, code)
    assert.equal(once.calls.n, 2, code)
    const always = flaky([reviewerError(code), reviewerError(code), reviewerError(code), reviewerError(code)])
    await assert.rejects(reviewRetry(always.run, undefined, [1, 1]), /reviewer ended with error/)
    assert.equal(always.calls.n, 3, code)
  }
})

test('a rate limit, a timeout, an abort or a bad answer is not tried again', async () => {
  for (const error of [
    reviewerError('RATE_LIMIT'), reviewerError('TIMEOUT'), new Error('auto-review: reviewer ended with aborted ABORTED: stop'),
    new Error('auto-review: reviewer output does not match the risk/decision protocol'), new SyntaxError('Unexpected token'),
  ]) {
    const r = flaky([error])
    await assert.rejects(reviewRetry(r.run, undefined, [1, 1]), (e) => e === error)
    assert.equal(r.calls.n, 1, error.message)
  }
})

test('the default waits are about one and three seconds', async () => {
  const { RETRY_WAITS_MS } = await import('../../../main/assets/dshn/review-json.mjs')
  assert.deepEqual(RETRY_WAITS_MS, [1000, 3000])
})

test('an abort during the wait stops the retry with the original error', async () => {
  const controller = new AbortController()
  const error = reviewerError('SERVER')
  const r = flaky([error])
  const started = Date.now()
  const pending = reviewRetry(r.run, controller.signal, [10_000, 10_000])
  setTimeout(() => controller.abort(), 20)
  await assert.rejects(pending, (e) => e === error)
  assert.equal(r.calls.n, 1)
  assert.ok(Date.now() - started < 5_000)
  // Already aborted: no wait, no second try.
  const aborted = flaky([reviewerError('SERVER')])
  await assert.rejects(reviewRetry(aborted.run, AbortSignal.abort(), [10_000]))
  assert.equal(aborted.calls.n, 1)
})

// --- the installed hook ---

test('the installed hook fixes the plugin as it loads', async () => {
  install()
  assert.equal(typeof globalThis.__dshmReviewRetry, 'function')
  assert.equal(typeof globalThis.__dshmReviewFailure, 'function')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'review-json-'))
  const lib = path.join(dir, 'node_modules', '@deepseek-ai', 'dsh-experimental-auto-review', 'lib')
  fs.mkdirSync(lib, { recursive: true })
  fs.writeFileSync(path.join(dir, 'node_modules', '@deepseek-ai', 'dsh-experimental-auto-review', 'package.json'), '{"type":"module"}')
  fs.writeFileSync(path.join(lib, 'index.js'), 'function parseDecision(text) {\n\tconst value = JSON.parse(text);\n\treturn value;\n}\nexport { parseDecision };\n')
  const { parseDecision } = await import(pathToFileURL(path.join(lib, 'index.js')).href)
  assert.deepEqual(parseDecision(`The command is read-only.\n${verdict}`), { risk: 'low', decision: 'allow' })
  assert.deepEqual(parseDecision('{"risk":"low","decision":"allow","reason":"x"}'), { risk: 'low', decision: 'allow' })
  fs.rmSync(dir, { recursive: true, force: true })
})
