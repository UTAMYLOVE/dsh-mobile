# Task 3 report: RootfsInstaller + ProotRunner

Commit: d8fb307 (pushed to main). CI run https://github.com/UTAMYLOVE/dsh-mobile/actions/runs/41465992981 : android job SUCCESS (Linux, symlink tests run there), plugins SUCCESS; rootfs (arm64/x86_64) jobs were still in progress at hand-off (untouched by this task).

## Built
- RootfsInstaller: streams `open("rootfs.tar.xz")` through XZCompressorInputStream+TarArchiveInputStream into filesDir/rootfs.tmp, verifies etc/dshm-rootfs-version == expected, deletes old rootfs, ATOMIC_MOVE to filesDir/rootfs. Stale rootfs.tmp removed at start; tmp removed on any failure. Preserves symlinks (absolute targets as-is), hard links (copy fallback), dirs, mode bits (owner rw/rwx forced, setuid dropped). Tar-slip rejected (normalized-path escape, and writing through an extracted symlink). Deletes never follow symlinks. Progress = compressed bytes / available(), 0..99, then 100.
- ProotRunner: `libproot.so --link2symlink --kill-on-exit -0 -r <rootfs> -b /proc -b /dev -b /sys -w /root/workspace <args>`; env cleared then PROOT_LOADER, LD_LIBRARY_PATH=nativeLibDir, PROOT_TMP_DIR, HOME=/root, PATH, LANG, caller env on top.
- proot.gradle.kts (applied from app/build.gradle.kts): task `fetchProot`, hooked to preBuild, downloads pinned Termux debs, sha256-verifies, extracts to src/main/jniLibs/<abi>/ (git-ignored). `jniLibs.useLegacyPackaging = true`. Deps added: commons-compress 1.27.1, xz 1.10 (libs.versions.toml).

## Pins (source https://packages.termux.dev/apt/termux-main/pool/main/ + path)
- proot 5.1.107.102 aarch64 p/proot/proot_5.1.107.102_aarch64.deb 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030
- proot 5.1.107.102 x86_64 p/proot/proot_5.1.107.102_x86_64.deb 2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758
- libtalloc 2.5.0 aarch64 libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
- libtalloc 2.5.0 x86_64 libt/libtalloc/libtalloc_2.5.0_x86_64.deb b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530
Mapping: bin/proot -> libproot.so, libexec/proot/loader -> libproot-loader.so, lib/libtalloc.so.2.5.0 -> libtalloc.so. Important: proot's NEEDED is "libtalloc.so.2" (Android only unpacks lib*.so), so the task patches that string in-place to "libtalloc.so" (asserts exactly one occurrence; verified 0 old / 1 new in the built arm64 binary). Termux proot also lists libandroid-shmem as a package dep but it is not an ELF NEEDED (only libc, libtalloc).

## TDD
RED: tests written first; `testDebugUnitTest` failed with "Unresolved reference 'RootfsInstaller'". GREEN: ManifestPermissionsTest 1, ProotRunnerTest 3, RootfsInstallerTest 9 (3 skipped locally on Windows because symlinks unavailable; they ran and passed on Linux CI).
Tests: idempotent+versioned, progress, interrupted install (IOException mid-stream) leaves no rootfs/rootfs.tmp, stale tmp, wrong archive version, tar-slip, write-through-symlink, symlink/hardlink/exec-bit preservation, reinstall does not follow symlinks on delete.

## Concerns
- Not verified on a real device/emulator: proot actually executing from nativeLibraryDir with patched NEEDED, and LD_LIBRARY_PATH resolution.
- The strict "no entry under an extracted symlink" check would fail install if the real rootfs tar ever has entries below a symlinked dir (unusual for a filesystem-walk tar); first real install should confirm.
- Rootfs likely lacks /etc/resolv.conf DNS on Android (not handled here; later task should write one or bind).
- Gradle build (and unit tests) now need network on first run for fetchProot (cached afterwards in app/build/proot-debs).

## Fix round 1
Finding: Important 1, upgrade could leave a half-deleted `rootfs` (version marker surviving while `usr/` is gone) if the process was killed during `deleteTree(rootfs)`.

Changed (RootfsInstaller.kt only, public interface unchanged):
- New `swapIn()`: if `rootfs` exists, ATOMIC_MOVE `rootfs` -> `rootfs.old`; ATOMIC_MOVE `rootfs.tmp` -> `rootfs`; then delete `rootfs.old`. `rootfs` is therefore always the whole old tree or the whole new tree.
- If the tmp -> rootfs move fails after the old tree was set aside, `rootfs.old` is moved back before rethrowing (restore failure is attached as suppressed). The outer catch still removes `rootfs.tmp`.
- `install` removes a leftover `rootfs.old` at start (alongside stale `rootfs.tmp`), via `deleteTree`, which never follows symlinks.
- Final `deleteTree(rootfs.old)` is best-effort (IOException swallowed): the new rootfs is already live, and the next install cleans up.

Tests added (RootfsInstallerTest, 9 -> 12):
- `leftoverRootfsOldIsRemovedWithoutFollowingSymlinks`: leftover `rootfs.old` with a symlink to an outside dir is removed by the next install; outside file survives.
- `upgradeSwapsInNewRootfsAndLeavesNoOldOrTmp`: v1 -> v2 upgrade; new marker and content, v1-only file gone, `filesDir` contains exactly `rootfs`.
- `failedUpgradeKeepsOldRootfsUsable`: failed upgrade (archive marker mismatch) leaves v1 installed, no `.old`/`.tmp`.

RED: `cd android && ./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.runtime.RootfsInstallerTest'` -> "12 tests completed, 1 failed"; failing test `leftoverRootfsOldIsRemovedWithoutFollowingSymlinks` (java.lang.AssertionError, rootfs.old still existed). The other two new tests passed on the old code: they are regression guards for the happy and rejected paths, since the old code only misbehaves when killed mid-delete, which is not reproducible deterministically.
GREEN: same command, then full `./gradlew :app:testDebugUnitTest --no-daemon -q` -> XML results: ManifestPermissionsTest 1/0 failures, ProotRunnerTest 3/0, RootfsInstallerTest 12/0 failures, 0 skipped (symlink tests ran on Linux).

Concerns:
- The restore-on-failure branch (tmp -> rootfs move fails after the old tree is set aside) has no test. Triggering it needs a failing `Files.move` between the two renames, which would require a production test hook or filesystem permission tricks that also break the first rename. Reviewed by reading only.
- A kill between the two renames leaves no `rootfs` (and `rootfs.old` + `rootfs.tmp`): `isInstalled()` is false and the next install cleans both and reinstalls. This is a safe state, not a half tree.
