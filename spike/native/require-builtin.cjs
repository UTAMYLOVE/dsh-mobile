// Stand-in for node-addon-require-builtin on Android, where no prebuilt addon exists (it ships glibc/musl/darwin/win32 only).
// The addon only hands out Node's own internal modules; `node --expose-internals` lets plain require() do the same.
'use strict'
function requireBuiltin(id) { return require(id) }
function isAllowedInternalId(id) { return typeof id === 'string' && id.startsWith('internal/') }
function getBindingInfo() { return { source: 'expose-internals', backend: 'js' } }
module.exports = { requireBuiltin, isAllowedInternalId, getBindingInfo, default: { requireBuiltin, isAllowedInternalId, getBindingInfo } }
