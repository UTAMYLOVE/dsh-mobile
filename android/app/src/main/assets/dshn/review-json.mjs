// DSH Mobile: dsh's Auto review (@deepseek-ai/dsh-experimental-auto-review) asks the conversation's own model for a one-line
// JSON verdict ({"risk": ..., "decision": ...}) and refuses the tool call when the answer does not parse. Claude sometimes says a
// sentence first or wraps the JSON in a ``` block, and the call failed with "Unexpected token 'T', "The comman"... is not valid
// JSON" (the owner's screenshot, 2026-11-15). When the plugin is loaded, its source is adjusted as it loads:
//  - its parse is given the verdict found in the answer, and an allow that only adds a "reason" loses the reason;
//  - a reviewer request that failed on the network or the server is tried again twice (after ~1 s and ~3 s);
//  - a review that still fails asks the owner, the way a reviewer's denial does, instead of failing the tool;
//  - the review runs at the conversation's own reasoning effort.
// The verdict itself is checked exactly as before (the plugin's own protocol check runs on what is handed back); an answer with
// no verdict still fails. No dsh file is changed. Each change replaces one exact piece of the expected source and is skipped
// when that piece is not there (another dsh version), so one missing piece never blocks the others.
import { registerHooks } from 'node:module'

/** Where [t] has the `}` closing the object that opens at [start], outside strings; -1 when it never closes. */
function closingBrace(t, start) {
  let depth = 0
  let inString = false
  let escape = false
  for (let i = start; i < t.length; i++) {
    const c = t[i]
    if (inString) {
      if (escape) escape = false
      else if (c === '\\') escape = true
      else if (c === '"') inString = false
      continue
    }
    if (c === '"') inString = true
    else if (c === '{') depth++
    else if (c === '}' && --depth === 0) return i
  }
  return -1
}

/** The last balanced {...} of [t], read from the end (the answer when no object in it is a verdict, so it still fails). */
function lastObject(t) {
  let end = -1
  for (let i = t.length - 1; i >= 0; i--) if (t[i] === '}') { end = i; break }
  if (end < 0) return t
  let depth = 0
  let inString = false
  for (let i = end; i >= 0; i--) {
    const c = t[i]
    if (inString) {
      if (c === '"' && !escaped(t, i)) inString = false
      continue
    }
    if (c === '"') inString = true
    else if (c === '}') depth++
    else if (c === '{' && --depth === 0) return t.slice(i, end + 1)
  }
  return t
}

function escaped(t, i) {
  let n = 0
  for (let j = i - 1; j >= 0 && t[j] === '\\'; j--) n++
  return n % 2 === 1
}

const isRecord = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/**
 * The verdict in a reviewer answer: the whole text when it already is one object; else the last {...} that parses to an object
 * with a "decision" member. Earlier objects are what the reviewer quoted (the tool's arguments, `awk '{print $1}'`, `echo {}`,
 * `find -exec rm {} \;`). When two verdicts in one answer disagree, the answer is handed back whole, so the review fails rather
 * than guessing which one was meant.
 */
export function reviewJson(text) {
  const t = String(text).trim()
  if (t.startsWith('{') && closingBrace(t, 0) === t.length - 1) return t
  const verdicts = []
  for (let i = 0; i < t.length; i++) {
    if (t[i] !== '{') continue
    const end = closingBrace(t, i)
    if (end < 0) continue
    const slice = t.slice(i, end + 1)
    let value
    try { value = JSON.parse(slice) } catch { continue }
    if (isRecord(value) && Object.hasOwn(value, 'decision')) verdicts.push({ slice, value })
  }
  if (verdicts.length === 0) return lastObject(t)
  const last = verdicts.at(-1)
  if (verdicts.some((v) => v.value.decision !== last.value.decision || v.value.risk !== last.value.risk)) return t
  return last.slice
}

/** Members of the top-level object of [text] (a duplicated key counts twice, unlike JSON.parse); the plugin counts the same way. */
function memberCount(text) {
  const syntax = text.replace(/"(?:\\.|[^"\\])*"/gs, '')
  let depth = 0
  let count = 0
  for (const c of syntax) {
    if (c === '{' || c === '[') depth++
    else if (c === '}' || c === ']') depth--
    else if (c === ':' && depth === 1) count++
  }
  return count
}

/**
 * [json] without the "reason" of an allow: the plugin's protocol says an allow never has one and refused the whole review over
 * it (the reviewer's own words are not needed to run the call). Only low or medium allows with exactly risk, decision and reason
 * are changed; anything else (a low deny, a high allow, repeated keys) is handed on as it is, for the plugin to refuse.
 */
export function normalizeVerdict(json) {
  let value
  try { value = JSON.parse(json) } catch { return json }
  if (!isRecord(value)) return json
  const keys = Object.keys(value)
  if (keys.length !== 3 || memberCount(json) !== 3 || !Object.hasOwn(value, 'reason')) return json
  if (value.decision !== 'allow' || (value.risk !== 'low' && value.risk !== 'medium')) return json
  return JSON.stringify({ risk: value.risk, decision: value.decision })
}

/** What the plugin's parse is given. */
export const verdictOf = (text) => normalizeVerdict(reviewJson(text))

/** Reviewer failures that are worth another try: the server or the network hiccuped, or the answer came back empty. */
const RETRYABLE = /^auto-review: reviewer ended with error (SERVER|TRANSPORT|EMPTY_RESPONSE)\b/
/** The waits before the second and third tries. */
export const RETRY_WAITS_MS = [1000, 3000]

/** False when [signal] aborted during the wait. */
function pause(ms, signal) {
  if (signal?.aborted) return Promise.resolve(false)
  return new Promise((resolve) => {
    const onAbort = () => { clearTimeout(timer); resolve(false) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(true) }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Runs one review ([run]) and, when it failed on a server, network or empty-answer error, runs it again after each of [waits].
 * Never again after a timeout (the reviewer already waited minutes), a rate limit (the daily cap, the runaway guard, a provider
 * 429: asking again would only add to it), an abort, or an answer that was not a verdict. The original error is rethrown.
 */
export async function reviewRetry(run, signal, waits = RETRY_WAITS_MS) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await run()
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (attempt >= waits.length || signal?.aborted || !RETRYABLE.test(message)) throw error
      if (!(await pause(waits[attempt], signal))) throw error
    }
  }
}

/** The line the owner reads on the approval card when the review itself did not work. */
export const REVIEW_FAILED_LINE = '自動審查這次沒有成功（網路或模型回覆格式），請你決定要不要執行。'
export const reviewFailure = (_error) => REVIEW_FAILED_LINE

export const AUTO_REVIEW = /[\\/]@deepseek-ai[\\/]dsh-experimental-auto-review[\\/]lib[\\/]index\.js$/

/** The pieces of the plugin's source that change, each with its replacement. Every one occurs once in dsh 0.2.1-rc.5. */
export const PATCHES = [
  {
    name: 'verdict',
    from: 'function parseDecision(text) {\n\tconst value = JSON.parse(text);',
    to: 'function parseDecision(text) {\n\ttext = globalThis.__dshmReviewJson(text);\n\tconst value = JSON.parse(text);',
  },
  {
    name: 'retry',
    from: 'return readDecision(ctx.llm.stream(options));',
    to: 'return globalThis.__dshmReviewRetry(() => readDecision(ctx.llm.stream(options)), signal);',
  },
  {
    // A failed review goes the way of a reviewer's denial: the gates after it still run (plan mode), then the owner is asked.
    // It stays final for subagents (approval "never": nobody can be asked) and for a call that was already stopped.
    name: 'ask-on-failure',
    from: '\t\t\t\tif (!review.ok) return failed(exec, review.error);\n\t\t\t\tconst { decision } = review;',
    to: '\t\t\t\tif (!review.ok && (exec.signal.aborted || ctx.approval.overrideOf(agent.session) === "never")) return failed(exec, review.error);\n' +
      '\t\t\t\tconst { decision } = review.ok ? review : { decision: { risk: "high", decision: "deny", reason: globalThis.__dshmReviewFailure(review.error) } };',
  },
  {
    // The conversation's own effort (思考 低 reviews at low), only for the route the conversation itself used: that effort is
    // known to be accepted there. Never a fixed effort, which a model can refuse (UNSUPPORTED_REASONING_EFFORT, a failed review).
    name: 'effort',
    from: '\t\ttemperature: 0,\n\t\tsignal\n\t});',
    to: '\t\t...((h) => h?.config.provider === snapshot.provider && h.config.model === snapshot.model && h.config.reasoningEffort !== void 0 ? { reasoningEffort: h.config.reasoningEffort } : {})(agent.session.requestHeader()),\n' +
      '\t\ttemperature: 0,\n\t\tsignal\n\t});',
  },
]

/** [source] with every patch whose piece occurs exactly once applied; the others are skipped. */
export function patchAutoReview(source) {
  let out = source
  for (const p of PATCHES) {
    const at = out.indexOf(p.from)
    if (at < 0 || out.indexOf(p.from, at + 1) >= 0) continue
    out = out.slice(0, at) + p.to + out.slice(at + p.from.length)
  }
  return out
}

/** Installs the helpers the patched source calls and the load hook (once per thread). */
export function install() {
  if (globalThis.__dshmReviewJson) return
  globalThis.__dshmReviewJson = verdictOf
  globalThis.__dshmReviewRetry = reviewRetry
  globalThis.__dshmReviewFailure = reviewFailure
  registerHooks({
    load(url, context, next) {
      const result = next(url, context)
      if (!AUTO_REVIEW.test(url) || result.source == null) return result
      const source = typeof result.source === 'string' ? result.source : Buffer.from(result.source).toString('utf8')
      return { ...result, source: patchAutoReview(source) }
    },
  })
}
