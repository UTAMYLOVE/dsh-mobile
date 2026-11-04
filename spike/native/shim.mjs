// Loaded with `node --import` (main thread and every worker): no dsh file is changed.
// 1. Node on Android reports process.platform "android"; dsh's file lock and a few platform packages only know "linux".
//    The kernel is Linux, so the launcher presents it as such.
// 2. node-addon-require-builtin has no Android build; it is routed to require-builtin.cjs next to this file, which gives the
//    same internal modules through --expose-internals.
import { registerHooks } from 'node:module'
Object.defineProperty(process, 'platform', { value: 'linux', enumerable: true })
const standIn = new URL('./require-builtin.cjs', import.meta.url).href
registerHooks({
  resolve(specifier, context, next) {
    if (specifier === 'node-addon-require-builtin') return { url: standIn, format: 'commonjs', shortCircuit: true }
    return next(specifier, context)
  },
})

// 3. Android's SELinux refuses hard links outside a few system domains, and dsh publishes each new session log with
//    link(tmp, final) so it never replaces an existing one. When link fails, the same no-replace publish is done by an
//    exclusive copy (COPYFILE_EXCL fails if final exists) followed by an fsync; dsh removes tmp itself afterwards.
import fs from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
const linkFailed = (e) => e && (e.code === 'EACCES' || e.code === 'EPERM' || e.code === 'UNKNOWN' || e.code === 'ENOSYS' || e.code === 'EXDEV')
const realLink = fs.promises.link
fs.promises.link = async function link(existing, target) {
  try { return await realLink(existing, target) } catch (e) {
    if (!linkFailed(e)) throw e
    await fs.promises.copyFile(existing, target, fs.constants.COPYFILE_EXCL)
    const h = await fs.promises.open(target, 'r+')
    try { await h.sync() } finally { await h.close() }
  }
}
const realLinkSync = fs.linkSync
fs.linkSync = function linkSync(existing, target) {
  try { return realLinkSync(existing, target) } catch (e) {
    if (!linkFailed(e)) throw e
    fs.copyFileSync(existing, target, fs.constants.COPYFILE_EXCL)
    const fd = fs.openSync(target, 'r+'); try { fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
  }
}
syncBuiltinESMExports()

// 4. Spike diagnostics (SPIKE_FETCH_LOG=path): every fetch's method, url, body size, status, and the full error chain.
if (process.env.SPIKE_FETCH_LOG) {
  const logPath = process.env.SPIKE_FETCH_LOG
  const realFetch = globalThis.fetch
  const sizeOf = (b) => b == null ? 0 : typeof b === 'string' ? Buffer.byteLength(b) : b.byteLength ?? b.size ?? -1
  const chain = (e) => { const out = []; for (let x = e, i = 0; x && i < 6; x = x.cause, i++) out.push({ name: x.name, message: String(x.message).slice(0, 300), code: x.code, errno: x.errno, syscall: x.syscall }); return out }
  const note = (o) => { try { fs.appendFileSync(logPath, JSON.stringify({ t: Date.now(), pid: process.pid, ...o }) + '\n') } catch {} }
  globalThis.fetch = async function fetch(input, init) {
    const url = String(input?.url ?? input)
    const meta = { url: url.slice(0, 120), method: init?.method ?? input?.method ?? 'GET', bytes: sizeOf(init?.body), enc: init?.headers?.['content-encoding'] ?? init?.headers?.get?.('content-encoding') }
    note({ ev: 'start', ...meta })
    try { const r = await realFetch(input, init); note({ ev: 'status', status: r.status, ...meta }); return r } catch (e) { note({ ev: 'error', ...meta, chain: chain(e) }); throw e }
  }
  process.on('uncaughtExceptionMonitor', (e) => note({ ev: 'uncaught', chain: chain(e) }))
}
