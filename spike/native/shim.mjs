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
