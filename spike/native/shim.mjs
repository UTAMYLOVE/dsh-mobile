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
