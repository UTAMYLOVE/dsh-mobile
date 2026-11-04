#!/bin/bash
# Builds the native dsh runtime for one ABI: build.sh arm64|x86_64 PACKAGE_LOCK OUT DEB_CACHE
#   PACKAGE_LOCK  the rootfs' dsh package-lock.json (out/dshm-rootfs-<arch>.package-lock.json): the file-lock addon is built from
#                 the exact @deepseek-ai/node-addon-system version the bundled dsh uses
#   OUT           gets jniLibs/<abi>/ and assets/native/ (gradle -Pdshm.nativeDir=OUT)
set -euo pipefail
ARCH=$1; LOCK=$2; OUT=$3; CACHE=$4
case "$ARCH" in arm64) TX=aarch64; ABI=arm64-v8a ;; x86_64) TX=x86_64; ABI=x86_64 ;; *) echo "arch must be arm64 or x86_64"; exit 1 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
python3 "$HERE/fetch-termux.py" "$TX" "$WORK/tx" --exact --lock "$HERE/termux.lock" --cache "$CACHE" $(grep -v '^#' "$HERE/packages.txt")
VER=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['packages']['node_modules/@deepseek-ai/node-addon-system']['version'])" "$LOCK")
echo "node-addon-system $VER"
(cd "$WORK" && npm pack "@deepseek-ai/node-addon-system@$VER" --silent > pack.txt && tar -xzf "$(cat pack.txt)")
bash "$HERE/build-flock.sh" "$TX" "$WORK/tx/usr/include/node" "$WORK/package/src/flock.c" "$WORK/flock.node"
python3 "$HERE/build-runtime.py" "$ABI" "$WORK/tx/usr" "$WORK/flock.node" "$OUT"
