// Loaded with `node --import`: Node on Android reports process.platform "android"; dsh's file lock and a few platform
// packages only know "linux". The kernel is Linux, so the launcher presents it as such. No dsh file is changed.
Object.defineProperty(process, 'platform', { value: 'linux', enumerable: true })
