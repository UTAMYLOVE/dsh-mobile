// DSH Mobile's native mode: loaded with `node --import` (main thread and every worker) before the official dsh. No dsh file is
// changed; everything here adapts Android to what dsh expects of Linux. Proven on the emulator by spike/native first.
import fs from 'node:fs'
import childProcess from 'node:child_process'
import { registerHooks, syncBuiltinESMExports } from 'node:module'

// 1. Android's node reports process.platform "android"; dsh (file lock, process inspector, platform packages) knows "linux".
//    The kernel is Linux, so the launcher presents it as such.
Object.defineProperty(process, 'platform', { value: 'linux', enumerable: true })

// 2. node-addon-require-builtin has no Android build; it is routed to require-builtin.cjs next to this file, which hands out
//    the same internal modules through --expose-internals.
const standIn = new URL('./require-builtin.cjs', import.meta.url).href
registerHooks({
  resolve(specifier, context, next) {
    if (specifier === 'node-addon-require-builtin') return { url: standIn, format: 'commonjs', shortCircuit: true }
    return next(specifier, context)
  },
})

// 3. Android's SELinux refuses hard links; dsh publishes each new session log with link(tmp, final) so it never replaces one.
//    When link fails, the same no-replace publish is an exclusive copy (fails if final exists) plus an fsync.
const linkFailed = (e) => e && ['EACCES', 'EPERM', 'UNKNOWN', 'ENOSYS', 'EXDEV'].includes(e.code)
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

// 4. Native code must come from the app's native library directory (W^X): dsh's file-lock addon (the Linux build in the rootfs)
//    loads the Android build instead (DSHN_FLOCK).
const flock = process.env.DSHN_FLOCK
const realDlopen = process.dlopen
process.dlopen = function dlopen(module, filename, ...rest) {
  if (flock && /[\\/]node-addon-system-linux-[^\\/]+[\\/]bin[\\/][^\\/]+[\\/]system\.node$/.test(String(filename))) return realDlopen.call(this, module, flock, ...rest)
  return realDlopen.call(this, module, filename, ...rest)
}

// 5. The same for programs: dsh's ripgrep (@vscode/ripgrep's Linux binary in the rootfs) runs as Android's ripgrep (DSHN_RG).
const rg = process.env.DSHN_RG
const swap = (file) => (rg && typeof file === 'string' && /[\\/]@vscode[\\/]ripgrep[\\/]bin[\\/]rg$/.test(file) ? rg : file)
for (const name of ['spawn', 'spawnSync', 'execFile', 'execFileSync']) {
  const real = childProcess[name]
  childProcess[name] = function (file, ...rest) { return real.call(this, swap(file), ...rest) }
}
syncBuiltinESMExports()
