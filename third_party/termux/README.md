# Vendored Termux packages (proot and its two libraries)

DSH Mobile runs the Debian rootfs through Termux's build of proot. The Gradle task `fetchProot`
(`android/app/proot.gradle.kts`) extracts four files from these `.deb`s into `android/app/src/main/jniLibs/<abi>/`
(git-ignored): `libproot.so` (`bin/proot`, its `libtalloc.so.2` NEEDED entry renamed to `libtalloc.so`),
`libproot-loader.so` (`libexec/proot/loader`), `libtalloc.so` (`lib/libtalloc.so.2.5.0`) and `libandroid-shmem.so`.
It checks every `.deb` against the sha256 below (pinned in the Gradle script too) and never uses the network.

The packages are kept here because the Termux repository only serves the newest build of each package: a pinned file name
starts returning 404 as soon as Termux publishes a new revision. These are the unmodified files as Termux published them.

| ABI | File | Package / version | sha256 |
|---|---|---|---|
| arm64-v8a | `arm64-v8a/proot_5.1.107.102_aarch64.deb` | proot 5.1.107.102 | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| arm64-v8a | `arm64-v8a/libtalloc_2.5.0_aarch64.deb` | libtalloc 2.5.0 | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` |
| arm64-v8a | `arm64-v8a/libandroid-shmem_0.7_aarch64.deb` | libandroid-shmem 0.7 | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |
| x86_64 | `x86_64/proot_5.1.107.102_x86_64.deb` | proot 5.1.107.102 | `2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758` |
| x86_64 | `x86_64/libtalloc_2.5.0_x86_64.deb` | libtalloc 2.5.0 | `b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530` |
| x86_64 | `x86_64/libandroid-shmem_0.7_x86_64.deb` | libandroid-shmem 0.7 | `ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797` |

The x86_64 set is used only by the emulator end-to-end test in CI; the released APK packages arm64-v8a only.

## Where they came from

Downloaded from the Termux main repository pool (the sha256 values match Termux's `Packages` index at that time):

- `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_<arch>.deb`
- `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_<arch>.deb`
- `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_<arch>.deb`

where `<arch>` is `aarch64` or `x86_64`.

## Licences and source code

Each `.deb` carries its licence under `data/data/com.termux/files/usr/share/doc/<package>/copyright` (for proot and
libtalloc a link to the full licence text in Termux's `share/LICENSES/`).

| Package | Licence | Corresponding source |
|---|---|---|
| proot | GPL-2.0 | Termux's proot fork, <https://github.com/termux/proot>; build recipe and patches: <https://github.com/termux/termux-packages/tree/master/packages/proot> |
| libtalloc | Termux declares GPL-3.0 for the package; upstream talloc's library licence is LGPL-3.0-or-later | <https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz>; recipe: <https://github.com/termux/termux-packages/tree/master/packages/libtalloc> |
| libandroid-shmem | BSD-3-Clause | <https://github.com/termux/libandroid-shmem> (tag v0.7); recipe: <https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem> |

DSH Mobile links none of this code into its own: proot runs as a separate executable, with libtalloc and libandroid-shmem as
its shared libraries. Anyone who receives the APK may obtain the corresponding source of these three packages from the
locations above.

## Updating

Download the new `.deb`s for both ABIs, replace the files here, update the file names and sha256 values in this README and in
`prootDebs` in `android/app/proot.gradle.kts`, and run `./gradlew :app:testDebugUnitTest --tests '*BundledNativeLibsTest'`
(it checks that every library each binary needs is bundled or part of Android).
