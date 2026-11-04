// Drives dsh on the emulator (through adb forward) and prints one PASS/FAIL line per capability.
// node client.mjs <dsh.out> <node_modules dir> <phase: first|second>
import fs from 'node:fs'
import { createRequire } from 'node:module'
const [, , outFile, nm, phase] = process.argv
const require = createRequire(nm + '/@deepseek-ai/dsh-api-gateway/package.json')
const WebSocket = require('ws')
const results = []
const ok = (name, pass, note = '') => { results.push({ name, pass, note }); console.log(`${pass ? 'PASS' : 'FAIL'} ${name} ${note}`) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const url = fs.readFileSync(outFile, 'utf8').match(/dsh web: (\S+)/)?.[1]
ok('dsh web 啟動', !!url, url ? '' : 'no launch line')
if (!url) { fs.writeFileSync('results-' + phase + '.json', JSON.stringify(results)); process.exit(0) }
const origin = new URL(url).origin
const r0 = await fetch(url, { redirect: 'manual' })
const cookie = (r0.headers.get('set-cookie') || '').split(';')[0]
ok('登入 cookie', r0.status === 303 && !!cookie, String(r0.status))
let n = 0
const rpc = async (method, args) => {
  const res = await fetch(`${origin}/api/${method}`, { method: 'POST', headers: { cookie, 'content-type': 'application/json', origin }, body: JSON.stringify({ type: 'client-request', rpcId: 'r' + (++n), method, payload: { args } }) })
  return res.json()
}
const list = await rpc('session/list', { _request: {} })
const sessions = JSON.stringify(list)
if (phase === 'second') {
  const id = fs.existsSync('session-id.txt') ? fs.readFileSync('session-id.txt', 'utf8') : ''
  ok('重啟後對話還在（檔案鎖 + 紀錄）', !!id && sessions.includes(id), id)
  fs.writeFileSync('results-' + phase + '.json', JSON.stringify(results)); process.exit(0)
}
const ws = new WebSocket(origin.replace('http', 'ws') + '/api/remote.mux', { headers: { cookie, origin } })
const frames = []
ws.on('message', (d) => frames.push(JSON.parse(String(d))))
await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
const send = (m) => ws.send(JSON.stringify(m))
send({ type: 'open', streamId: 'ev1', endpoint: '$events', payload: { args: {} } })
send({ type: 'open', streamId: 'ws1', endpoint: 'workspace/follow', payload: { args: {} } })
await sleep(800)
let workspaceId = frames.find((m) => m.streamId === 'ws1' && m.value?.type === 'baseline')?.value?.value?.items?.[0]?.workspaceId
if (!workspaceId) {
  const created = await rpc('workspace/create', { request: { path: '/data/local/tmp/n/ws' } })
  workspaceId = created?.result?.value?.workspace?.workspaceId
}
ok('工作區', !!workspaceId)
const created = await rpc('session/create', { request: { workspaceId } })
const sessionId = created?.result?.value?.sessionId
ok('建立對話', !!sessionId, JSON.stringify(created).slice(0, 120))
fs.writeFileSync('session-id.txt', sessionId || '')
send({ type: 'open', streamId: 'f1', endpoint: 'session/follow', payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: 500 } } } })
await sleep(300)
const text = () => JSON.stringify(frames.filter((m) => m.streamId === 'f1'))
const ask = async (name, prompt, expect, ms = 60000) => {
  await rpc('session/prompt', { request: { requestId: 'q' + (++n), sessionId, mode: 'queue', content: [{ type: 'text', text: prompt }] } })
  const end = Date.now() + ms
  while (Date.now() < end) {
    // answer any approval so tools can run
    const wf = frames.filter((m) => m.streamId === 'ev1' && m.value?.type === 'waterfall' && !m.answered)
    const ready = frames.find((m) => m.streamId === 'ev1' && m.value?.type === 'ready')
    for (const w of wf) { w.answered = true; await rpc('$events/result', { clientId: ready?.value?.clientId, eventId: w.value.eventId, outcome: { kind: 'result', value: 'allowed-once' } }) }
    if (expect.test(text())) { ok(name, true); return }
    await sleep(300)
  }
  ok(name, false, (t => t.length > 420 ? t.slice(0, 160) + ' … ' + t.slice(-260) : t)(text()))
}
await ask('模型回覆（串流）', 'hello there', /Hello from the fake model/)
await ask('bash 工具', 'DO_BASH please', /hi-from-bash 42/)
await ask('寫檔工具', 'DO_WRITE please', /written-on-android|native\.md/)
await ask('grep 搜尋（ripgrep）', 'DO_GREP please', /native\.md/)
await ask('子代理人', 'DO_SUB please', /Child agent finished|subagent-settled/, 90000)
ws.close()
fs.writeFileSync('results-' + phase + '.json', JSON.stringify(results))
