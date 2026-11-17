// DSH Mobile under proot (相容模式): loaded with `node --import` before the official dsh. No dsh file is changed.
//
// proot runs with --link2symlink because Android refuses hard links. It fakes link(existing, target) by moving `existing` to
// a hidden file next to it and pointing both names at that file with symlinks. dsh's write tool publishes a new file with
// link(<staging dir>/<name>.tmp, target) and then removes the whole staging dir, which takes the hidden file with it: the new
// file was left as a symlink to nothing (DSH on the owner's phone, 2026-11-15: the write tool breaks on new files). So under
// proot every hard link dsh asks for is made the way the native shim makes it when link fails: an exclusive copy (it fails
// when the target exists, as link does) plus an fsync. dsh only links to publish without replacing, never to share a file.
// The copy has the source's mode (dsh links read-only objects too), so it is synced through a read-only handle.
import fs from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import { install as reviewJson } from './review-json.mjs'

// Auto review's verdict is read from the reviewer's answer even when the model wrapped it in a sentence (review-json.mjs).
reviewJson()

const copy = async (existing, target) => {
  await fs.promises.copyFile(existing, target, fs.constants.COPYFILE_EXCL)
  const h = await fs.promises.open(target, 'r')
  try { await h.sync() } finally { await h.close() }
}
fs.promises.link = async function link(existing, target) { await copy(existing, target) }
fs.linkSync = function linkSync(existing, target) {
  fs.copyFileSync(existing, target, fs.constants.COPYFILE_EXCL)
  const fd = fs.openSync(target, 'r')
  try { fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
}
fs.link = function link(existing, target, callback) { copy(existing, target).then(() => callback(null), (e) => callback(e)) }
syncBuiltinESMExports()
