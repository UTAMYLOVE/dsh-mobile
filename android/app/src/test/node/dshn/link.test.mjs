// The hard-link stand-ins: the native shim's fallback when Android refuses link(2) (assets/dshn/shim.mjs, point 3) and the
// proot module that always copies (assets/dshn/proot-link.mjs). Each case runs in its own node, with the module loaded by
// `--import` as the app loads it, through the same steps dsh's attachment store takes for a file (dsh-attachment-local
// saveFileVerbatim): a 0600 staged file linked to its object, the object made 0400, the 0400 object linked to the file's
// name. CI run 44044072270 (E2eTest t16): the copy of the 0400 object was opened for writing to be synced, an app uid may
// not do that, and every first upload of a file failed natively. Root may (DAC override), so as root the cases run as nobody.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const DSHN = path.resolve(here, '../../../main/assets/dshn')
const asApp = process.getuid?.() === 0 ? { uid: 65534, gid: 65534 } : {}

const made = []
after(() => { for (const d of made) fs.rmSync(d, { recursive: true, force: true }) })

// Android's answer to link(2) in the app's domain (logcat: avc: denied { link }), installed before the shim reads fs.
const REFUSE_LINK = `import fs from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
const refused = (existing, target) => Object.assign(new Error(\`EACCES: permission denied, link '\${existing}' -> '\${target}'\`), { errno: -13, code: 'EACCES', syscall: 'link', path: existing, dest: target })
fs.promises.link = async (existing, target) => { throw refused(existing, target) }
fs.linkSync = (existing, target) => { throw refused(existing, target) }
fs.link = (existing, target, callback) => process.nextTick(callback, refused(existing, target))
syncBuiltinESMExports()
`

// dsh's steps, with dsh's own imports (named exports of node:fs/promises, which the modules reach by syncBuiltinESMExports).
const CASE = `import fs from 'node:fs'
import { chmod, link, mkdir, open, readFile, stat, unlink } from 'node:fs/promises'
const root = process.env.DSH_HOME + '/attachments/v1'
const text = '小雨的筆記：霧城的早晨。\\n'
const outcome = async (run) => { try { await run(); return 'ok' } catch (e) { return e.code ?? String(e) } }
const mode = async (p) => ((await stat(p)).mode & 0o777).toString(8)
const report = {}
for (const dir of ['tmp', 'file-objects', 'files/aa']) await mkdir(root + '/' + dir, { recursive: true })
const staged = root + '/tmp/staged'
const h = await open(staged, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY, 0o600)
await h.writeFile(text); await h.sync(); await h.close()
const object = root + '/file-objects/aa'
report.object = await outcome(() => link(staged, object))
await unlink(staged)
await chmod(object, 0o400)
const name = root + '/files/aa/e2e-note.txt'
report.alias = await outcome(() => link(object, name))
report.aliasText = await readFile(name, 'utf8').catch((e) => e.code)
report.aliasMode = await mode(name).catch((e) => e.code)
report.again = await outcome(() => link(object, name))
report.sync = await outcome(async () => fs.linkSync(object, name + '.sync'))
report.syncText = await readFile(name + '.sync', 'utf8').catch((e) => e.code)
report.callback = await outcome(() => new Promise((resolve, reject) => fs.link(object, name + '.cb', (e) => (e ? reject(e) : resolve()))))
report.callbackText = await readFile(name + '.cb', 'utf8').catch((e) => e.code)
console.log(JSON.stringify(report))
`

function run(...preloads) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dshn-link-'))
  made.push(dir)
  fs.chmodSync(dir, 0o755)
  fs.cpSync(DSHN, path.join(dir, 'dshn'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'refuse-link.mjs'), REFUSE_LINK)
  fs.writeFileSync(path.join(dir, 'case.mjs'), CASE)
  const home = path.join(dir, 'home')
  fs.mkdirSync(home)
  if (asApp.uid !== undefined) fs.chownSync(home, asApp.uid, asApp.gid)
  const env = { ...process.env, DSH_HOME: home, HOME: home }
  delete env.NODE_TEST_CONTEXT
  delete env.NODE_OPTIONS
  const imports = preloads.flatMap((p) => ['--import', pathToFileURL(path.join(dir, p)).href])
  const r = spawnSync(process.execPath, [...imports, path.join(dir, 'case.mjs')], { cwd: dir, env, encoding: 'utf8', ...asApp })
  assert.equal(r.status, 0, r.error ? String(r.error) : r.stderr)
  return JSON.parse(r.stdout.trim().split('\n').pop())
}

const published = {
  object: 'ok',
  alias: 'ok',
  aliasText: '小雨的筆記：霧城的早晨。\n',
  aliasMode: '400',
  again: 'EEXIST',
  sync: 'ok',
  syncText: '小雨的筆記：霧城的早晨。\n',
}

test('natively, a read-only object is published under a second name when Android refuses the link', () => {
  // dsh links only through node:fs/promises (attachments, files, session logs), so the shim leaves the callback form alone.
  const { callback, callbackText, ...report } = run('refuse-link.mjs', 'dshn/shim.mjs')
  assert.deepEqual(report, published)
})

test('under proot every link is the same exclusive copy, read-only objects included', () => {
  const report = run('dshn/proot-link.mjs')
  assert.deepEqual(report, { ...published, callback: 'ok', callbackText: published.aliasText })
})
