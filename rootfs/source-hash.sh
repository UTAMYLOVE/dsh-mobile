#!/usr/bin/env bash
# Usage: source-hash.sh
# Prints the 8-hex content hash of everything the rootfs is built from: rootfs/** and plugins/** (build products excluded:
# node_modules, dist, .turbo, coverage, *.tsbuildinfo). The rootfs version is ROOTFS_VERSION-<this hash> (versions.env), so any
# change to the rootfs scripts or the plugins makes the app reinstall the rootfs (user data lives outside it).
# android/app/build.gradle.kts computes the same value for BuildConfig.ROOTFS_VERSION (RootfsVersionTest checks they agree):
# sha256 of the `sha256sum` lines ("<hex>  <path>") of the regular files, paths relative to the repository root, sorted bytewise.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
find rootfs plugins \
  -type d \( -name node_modules -o -name dist -o -name .turbo -o -name coverage \) -prune \
  -o -type f ! -name '*.tsbuildinfo' -print \
  | LC_ALL=C sort \
  | while IFS= read -r f; do sha256sum "$f"; done \
  | sha256sum | cut -c1-8
