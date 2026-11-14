// Auto review's verdict found in the reviewer's answer (assets/dshn/review-json.mjs).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { reviewJson, patchAutoReview, install, AUTO_REVIEW } from '../../../main/assets/dshn/review-json.mjs'

const verdict = '{"risk":"low","decision":"allow"}'

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

test('an answer with no JSON object still fails to parse', () => {
  assert.throws(() => JSON.parse(reviewJson('The command looks safe to me.')))
})

const plugin = 'function parseDecision(text) {\n\tconst value = JSON.parse(text);\n\treturn value;\n}\nexport { parseDecision };\n'

test('only the expected parse is changed', () => {
  assert.match(patchAutoReview(plugin), /__dshmReviewJson\(text\)/)
  assert.equal(patchAutoReview('export const x = 1\n'), 'export const x = 1\n')
  assert.ok(AUTO_REVIEW.test('file:///opt/dsh/current/node_modules/@deepseek-ai/dsh-experimental-auto-review/lib/index.js'))
  assert.ok(!AUTO_REVIEW.test('file:///opt/dsh/current/node_modules/@deepseek-ai/dsh-core/lib/index.js'))
})

test('the installed hook fixes the plugin as it loads', async () => {
  install()
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'review-json-'))
  const lib = path.join(dir, 'node_modules', '@deepseek-ai', 'dsh-experimental-auto-review', 'lib')
  fs.mkdirSync(lib, { recursive: true })
  fs.writeFileSync(path.join(dir, 'node_modules', '@deepseek-ai', 'dsh-experimental-auto-review', 'package.json'), '{"type":"module"}')
  fs.writeFileSync(path.join(lib, 'index.js'), plugin)
  const { parseDecision } = await import(pathToFileURL(path.join(lib, 'index.js')).href)
  assert.deepEqual(parseDecision(`The command is read-only.\n${verdict}`), { risk: 'low', decision: 'allow' })
  fs.rmSync(dir, { recursive: true, force: true })
})
