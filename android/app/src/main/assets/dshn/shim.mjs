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
// 5b. dsh makes a stored picture durable by fsyncing every directory from its home up to the filesystem root. On Android the
//     levels above the app's own data (/data/user/0, /data/user, /data) may not be opened, and the prompt was refused with
//     "EACCES: permission denied, open '/data/user/0'" (the owner's 0.5.0 screenshot: 「prompt rejected」). Those levels are the
//     system's; when one of them refuses a read-only open, the caller gets a handle whose sync does nothing.
const ownHome = process.env.DSH_HOME || process.env.HOME || ''
const aboveHome = (p) => typeof p === 'string' && p.startsWith('/') && ownHome !== '' && p !== ownHome && ownHome.startsWith(p.endsWith('/') ? p : p + '/')
const realPromisesOpen = fs.promises.open
fs.promises.open = async function open(path, ...rest) {
  try { return await realPromisesOpen.call(this, path, ...rest) } catch (e) {
    if (!e || !['EACCES', 'EPERM'].includes(e.code) || !aboveHome(path)) throw e
    const nothing = async () => {}
    return { fd: -1, sync: nothing, datasync: nothing, close: nothing, [Symbol.asyncDispose]: nothing }
  }
}

// 6. Conversations made under proot have /root/workspace (and dsh's home /root/.dsh) as their folder, and dsh never runs a turn
//    whose folder is missing. Those are the same folders on the phone under their host names, so paths below them are mapped
//    (DSHN_REMAP = "from=to;from=to") in fs, in a child's working directory and in chdir. Nothing else is rewritten.
const remaps = (process.env.DSHN_REMAP || '').split(';').map((s) => s.split('=')).filter((p) => p.length === 2 && p[0].startsWith('/') && p[1].startsWith('/'))
if (remaps.length > 0) {
  const map = (p) => {
    if (typeof p !== 'string') return p
    for (const [from, to] of remaps) if (p === from || p.startsWith(from + '/')) return to + p.slice(from.length)
    return p
  }
  const wrap = (owner, name, paths) => {
    const real = owner?.[name]
    if (typeof real !== 'function') return
    const wrapped = function (...args) {
      for (let i = 0; i < paths && i < args.length; i++) args[i] = map(args[i])
      return real.apply(this, args)
    }
    for (const key of Object.keys(real)) wrapped[key] = real[key] // e.g. realpath.native
    owner[name] = wrapped
  }
  const one = ['access', 'appendFile', 'chmod', 'chown', 'exists', 'lchown', 'lstat', 'lutimes', 'mkdir', 'open', 'openAsBlob', 'opendir', 'readdir', 'readFile',
    'readlink', 'realpath', 'rm', 'rmdir', 'stat', 'statfs', 'truncate', 'unlink', 'utimes', 'watch', 'watchFile', 'unwatchFile', 'writeFile',
    'createReadStream', 'createWriteStream', 'glob']
  const two = ['copyFile', 'cp', 'link', 'rename', 'symlink']
  for (const owner of [fs, fs.promises]) {
    for (const name of one) { wrap(owner, name, 1); wrap(owner, name + 'Sync', 1) }
    for (const name of two) { wrap(owner, name, 2); wrap(owner, name + 'Sync', 2) }
  }
  if (typeof fs.realpath.native === 'function') wrap(fs.realpath, 'native', 1)
  if (typeof fs.realpathSync.native === 'function') wrap(fs.realpathSync, 'native', 1)
  const withCwd = (args) => args.map((a) => (a && typeof a === 'object' && !Array.isArray(a) && typeof a.cwd === 'string' ? { ...a, cwd: map(a.cwd) } : a))
  for (const name of ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']) {
    const real = childProcess[name]
    childProcess[name] = function (...args) { return real.apply(this, withCwd(args)) }
    for (const key of Object.getOwnPropertySymbols(real)) childProcess[name][key] = real[key] // util.promisify.custom
  }
  const realChdir = process.chdir
  process.chdir = function chdir(dir) { return realChdir.call(this, map(dir)) }
}
syncBuiltinESMExports()
