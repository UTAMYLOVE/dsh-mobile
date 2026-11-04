// Minimal fake Anthropic Messages SSE server for observing dsh UI traffic.
import http from 'node:http'
import fs from 'node:fs'
const LOG = process.argv[3] || 'fake-llm.log'
const port = Number(process.argv[2] || 3999)
const sleep = (ms) => new Promise(r => setTimeout(r, ms))
let n = 0
function textOf(content) {
  if (typeof content === 'string') return content
  return (content || []).map(b => b.type === 'text' ? b.text : b.type === 'tool_result' ? '[tool_result]' + JSON.stringify(b.content).slice(0, 300) : '').join('\n')
}
http.createServer(async (req, res) => {
  fs.appendFileSync(LOG + '.conn', JSON.stringify({ t: Date.now(), method: req.method, url: req.url, len: req.headers['content-length'], te: req.headers['transfer-encoding'], enc: req.headers['content-encoding'] }) + '\n')
  req.on('error', (e) => fs.appendFileSync(LOG + '.conn', JSON.stringify({ t: Date.now(), reqError: e.message }) + '\n'))
  let body = ''
  for await (const c of req) body += c
  fs.appendFileSync(LOG + '.conn', JSON.stringify({ t: Date.now(), gotBytes: body.length }) + '\n')
  if (!req.url.includes('/messages')) { res.writeHead(404); res.end(); return }
  let j; try { j = JSON.parse(body) } catch { res.writeHead(400); res.end(); return }
  const id = ++n
  const msgs = j.messages || []
  const last = msgs[msgs.length - 1]
  const lastText = last ? textOf(last.content) : ''
  const sys = typeof j.system === 'string' ? j.system : (j.system || []).map(s => s.text).join('\n')
  const toolNames = (j.tools || []).map(t => t.name)
  fs.appendFileSync(LOG, JSON.stringify({ id, url: req.url, model: j.model, nMsgs: msgs.length, tools: toolNames, sysHead: sys.slice(0, 200), lastText: lastText.slice(-600) }) + '\n')
  if ((j.tools || []).length && !fs.existsSync(LOG + '.tools.json') || process.env.DUMP_TOOLS) fs.writeFileSync(LOG + '.tools.json', JSON.stringify(j.tools || [], null, 1))
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  const send = (ev, data) => res.write(`event: ${ev}\ndata: ${JSON.stringify({ type: ev, ...data })}\n\n`)
  send('message_start', { message: { id: 'msg_' + id, type: 'message', role: 'assistant', model: j.model, content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 1000 + id, output_tokens: 1, cache_read_input_tokens: 500 } } })
  let idx = 0
  const thinking = async (t) => { send('content_block_start', { index: idx, content_block: { type: 'thinking', thinking: '', signature: '' } }); for (const w of t.split(' ')) { send('content_block_delta', { index: idx, delta: { type: 'thinking_delta', thinking: w + ' ' } }); await sleep(30) } send('content_block_delta', { index: idx, delta: { type: 'signature_delta', signature: 'sig' + id } }); send('content_block_stop', { index: idx }); idx++ }
  const text = async (t, d = 40) => { send('content_block_start', { index: idx, content_block: { type: 'text', text: '' } }); for (const w of t.split(' ')) { send('content_block_delta', { index: idx, delta: { type: 'text_delta', text: w + ' ' } }); await sleep(d) } send('content_block_stop', { index: idx }); idx++ }
  const tool = async (name, input) => { send('content_block_start', { index: idx, content_block: { type: 'tool_use', id: 'toolu_' + id + '_' + idx, name, input: {} } }); const s = JSON.stringify(input); send('content_block_delta', { index: idx, delta: { type: 'input_json_delta', partial_json: s.slice(0, 5) } }); await sleep(30); send('content_block_delta', { index: idx, delta: { type: 'input_json_delta', partial_json: s.slice(5) } }); send('content_block_stop', { index: idx }); idx++ }
  let stop = 'end_turn'
  const isToolResult = last && Array.isArray(last.content) && last.content.some(b => b.type === 'tool_result')
  const scenario = (process.env.SCENARIO_FILE && fs.existsSync(process.env.SCENARIO_FILE)) ? JSON.parse(fs.readFileSync(process.env.SCENARIO_FILE, 'utf8')) : {}
  if (!toolNames.length || /title/i.test(sys.slice(0, 400)) && !toolNames.length) {
    await text('Fake Session Title')
  } else if (isToolResult) {
    await text('Tool finished. All good: ' + lastText.slice(0, 80).replace(/\s+/g, ' '))
  } else {
    // find first matching keyword rule from scenario file: [{match, tool, input, sleepMs}]
    const rule = (scenario.rules || []).find(r => lastText.includes(r.match))
    if (rule && rule.tool) {
      await thinking('I should call ' + rule.tool)
      await tool(rule.tool, rule.input)
      stop = 'tool_use'
    } else if (rule && rule.text) {
      await thinking('child thinking about it')
      await text(rule.text, rule.delay || 200)
    } else {
      await thinking('Let me think about this carefully step by step')
      await text('Hello from the fake model. This is a streamed answer with **markdown**.')
    }
  }
  send('message_delta', { delta: { stop_reason: stop, stop_sequence: null }, usage: { output_tokens: 42 + id, input_tokens: 1000 + id, cache_read_input_tokens: 500 } })
  send('message_stop', {})
  res.end()
}).listen(port, '0.0.0.0', () => console.log('fake llm on', port))
// One bad request must never take the fake model down for the rest of the run.
process.on('uncaughtException', (e) => fs.appendFileSync(LOG + '.conn', JSON.stringify({ t: Date.now(), crash: String(e && e.stack || e).slice(0, 400) }) + '\n'))
process.on('unhandledRejection', (e) => fs.appendFileSync(LOG + '.conn', JSON.stringify({ t: Date.now(), crash: String(e && e.stack || e).slice(0, 400) }) + '\n'))
