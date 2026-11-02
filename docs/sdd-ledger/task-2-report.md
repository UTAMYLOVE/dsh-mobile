# Task 2 report: rootfs packaging

Status: DONE_WITH_CONCERNS

## Built
- rootfs/versions.env (pins: dsh 0.2.1-rc.5, ROOTFS_VERSION 1, Node 24.22.1 + sha256 per arch, debian:bookworm-slim)
- rootfs/build-rootfs.sh <arm64|x86_64> <out.tar.xz> [staging_dir]: docker (qemu binfmt) create/start of target-arch bookworm-slim, runs inner-setup.sh, exports, repacks without docker artifacts, xz -9e.
- rootfs/inner-setup.sh: apt ca-certificates/curl/xz; official Node tarball verified against hardcoded sha256 AND SHASUMS256.txt; `npm install --prefix /opt/dsh/0.2.1-rc.5 @deepseek-ai/dsh@0.2.1-rc.5` inside target arch; /opt/dsh/current symlink; /opt/dshm/plugins (+ staging copy); /root/workspace; /etc/dshm-rootfs-version; strips man/doc/info/locale/apt/npm caches, purges curl/xz. No auth-gate, no NODE_OPTIONS.
- rootfs/smoke-test.sh <tar>: extract + chroot (qemu binfmt; proot via SMOKE_USE_PROOT=1) checks node v24, major 24, dsh --version == pin, version file, /root/workspace, current symlink, /opt/dshm/plugins.
- ci.yml: `rootfs` matrix job (build, smoke, size to GITHUB_STEP_SUMMARY, upload artifact retention 7d). Scripts chmod +x in git.

## RED
Before build script existed, smoke test failed (local, bash): .superpowers/sdd/2026-11-02-dsh-mobile/task-2-red.txt ("FAIL: rootfs tarball not found", exit=1).

## GREEN
Final run https://github.com/UTAMYLOVE/dsh-mobile/actions/runs/41456718227 : android, plugins, rootfs(arm64), rootfs(x86_64) all green, "SMOKE OK" both.
Sizes (xz): arm64 125,813,172 B (120M); x86_64 128,214,248 B (123M).
Earlier failed iterations: tar --delete corrupted archive (run 41452278302); smoke `test -d /root/workspace` failed because /root is 0700 (run 41454631204). Both fixed.

## Commits
6614b6c build: rootfs with node 24 and pinned dsh; cdcd5a8 repack via extract; d1f4ca8 smoke sudo test.

## Concerns
- ~120 MB compressed per arch (Node ~ 40MB + 614-package dsh tree); large for APK/asset — consider download-on-first-run or further pruning (md/map/ts files, unused native prebuilds for other platforms in node_modules).
- Smoke test uses chroot, not proot; real proot/Android behavior (symlinks, /proc) is untested here. dsh runtime deps like git/ripgrep not installed (not in brief).
- arm64 build takes ~8-14 min under qemu.

## Fix round 1
Commit: build: rootfs fix round 1 (git/rg, digest pin, integrity, atomic output). CI: https://github.com/UTAMYLOVE/dsh-mobile/actions/runs/41460318464 (all green; arm64 job 20m, x86_64 9.5m; timeout-minutes 45 added).
1. ripgrep finding: dsh's tree ships only @vscode/ripgrep + @vscode/ripgrep-win32-x64 (Windows rg.exe) when installed on Windows; no Linux rg binary was found locally, so apt `git ripgrep bash coreutils ca-certificates` are installed. Smoke now checks `command -v git rg bash` inside the rootfs: PASS git->/usr/bin/git, rg->/usr/bin/rg, bash->/usr/bin/bash on both arches.
2. Base image pinned: debian:bookworm-slim@sha256:99c0b25c...b6f3 (versions.env). package-lock.json and `npm ls --all` output are generated at build, kept in /opt/dsh/<ver>/ and uploaded as artifacts dshm-rootfs-<arch>.package-lock.json / .npm-ls.txt next to the tarball. (Did not switch to committed lockfile + npm ci.)
3. DSH_INTEGRITY=sha512-WkyJZv4z...rpLTXpw== (npm view dist.integrity) in versions.env; inner-setup.sh compares it with the lockfile entry for @deepseek-ai/dsh and exits 1 on mismatch (passed).
4. Output written to $OUT.tmp then mv; tmp removed in cleanup trap.
Sizes now: arm64 140,459,764 B (134M); x86_64 142,157,520 B (136M).
