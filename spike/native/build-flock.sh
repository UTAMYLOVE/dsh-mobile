#!/bin/bash
# Builds dsh's own flock addon (official source, BSD-3) for Android: build-flock.sh ARCH NODE_INCLUDE SRC OUT
set -euo pipefail
ARCH=$1; INC=$2; SRC=$3; OUT=$4
case "$ARCH" in x86_64) T=x86_64-linux-android24 ;; aarch64) T=aarch64-linux-android24 ;; *) echo "arch?"; exit 1 ;; esac
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
"$CC" --target="$T" -shared -fPIC -O2 -I"$INC" -Wl,--allow-shlib-undefined "$SRC" -o "$OUT"
file "$OUT"
