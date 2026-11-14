// DSH Mobile: dsh's Auto review (@deepseek-ai/dsh-experimental-auto-review) asks the conversation's own model for a one-line
// JSON verdict ({"risk": ..., "decision": ...}) and refuses the tool call when the answer does not parse. Claude sometimes says a
// sentence first or wraps the JSON in a ``` block, and the call failed with "Unexpected token 'T', "The comman"... is not valid
// JSON" (the owner's screenshot, 2026-11-15). When the plugin is loaded, its parse is given the JSON object found in the answer
// instead. The verdict itself is checked exactly as before (the plugin's own protocol check runs on that object); an answer with
// no JSON object still fails. No dsh file is changed: the source is adjusted as it loads, and only when it is the expected one.
import { registerHooks } from 'node:module'

/** The one JSON object in a reviewer answer: the whole text when it already is one, else a ``` block, else the last {...}. */
export function reviewJson(text) {
  const t = String(text).trim()
  if (t.startsWith('{') && t.endsWith('}')) return t
  const fenced = /```(?:json)?\s*(\{[\s\S]*?\})\s*```/i.exec(t)
  if (fenced) return fenced[1]
  // The last balanced {...} outside strings.
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

export const AUTO_REVIEW = /[\\/]@deepseek-ai[\\/]dsh-experimental-auto-review[\\/]lib[\\/]index\.js$/
const PARSE = 'function parseDecision(text) {\n\tconst value = JSON.parse(text);'

/** [source] with parseDecision reading the JSON found by [reviewJson]; unchanged when it is not the expected source. */
export function patchAutoReview(source) {
  if (!source.includes(PARSE)) return source
  return source.replace(PARSE, 'function parseDecision(text) {\n\ttext = globalThis.__dshmReviewJson(text);\n\tconst value = JSON.parse(text);')
}

/** Installs the load hook (once per thread). */
export function install() {
  if (globalThis.__dshmReviewJson) return
  globalThis.__dshmReviewJson = reviewJson
  registerHooks({
    load(url, context, next) {
      const result = next(url, context)
      if (!AUTO_REVIEW.test(url) || result.source == null) return result
      const source = typeof result.source === 'string' ? result.source : Buffer.from(result.source).toString('utf8')
      return { ...result, source: patchAutoReview(source) }
    },
  })
}
