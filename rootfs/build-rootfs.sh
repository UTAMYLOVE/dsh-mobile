#!/usr/bin/env bash
# Usage: build-rootfs.sh <arm64|x86_64> <out.tar.xz> [staging_dir]
# Builds a minimal Debian bookworm rootfs with Node 24 and the pinned dsh.
# Requires docker (with qemu-user binfmt registered for foreign arch).
# staging_dir (optional): its contents are copied to /opt/dshm/ in the rootfs.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
. "$HERE/versions.env"

ARCH="${1:?arch (arm64|x86_64) required}"
OUT="${2:?output path required}"
STAGING="${3:-}"

case "$ARCH" in
  arm64)  PLATFORM=linux/arm64; NODE_ARCH=arm64; NODE_SHA="$NODE_SHA256_arm64" ;;
  x86_64) PLATFORM=linux/amd64; NODE_ARCH=x64;   NODE_SHA="$NODE_SHA256_x86_64" ;;
  *) echo "unknown arch: $ARCH" >&2; exit 2 ;;
esac

WORK="$(mktemp -d)"
CTR="dshm-rootfs-$$"
cleanup() { docker rm -f "$CTR" >/dev/null 2>&1 || true; sudo rm -rf "$WORK"; }
trap cleanup EXIT

mkdir -p "$WORK/staging"
if [ -n "$STAGING" ] && [ -d "$STAGING" ]; then cp -a "$STAGING"/. "$WORK/staging/"; fi
cp "$HERE/inner-setup.sh" "$WORK/inner-setup.sh"

docker create --platform "$PLATFORM" --name "$CTR" \
  -e NODE_VERSION="$NODE_VERSION" -e NODE_ARCH="$NODE_ARCH" -e NODE_SHA="$NODE_SHA" \
  -e DSH_PINNED_VERSION="$DSH_PINNED_VERSION" -e ROOTFS_VERSION="$ROOTFS_VERSION" \
  "$DEBIAN_IMAGE" bash /inner-setup.sh >/dev/null
docker cp "$WORK/inner-setup.sh" "$CTR:/inner-setup.sh"
docker cp "$WORK/staging" "$CTR:/staging"
docker start -a "$CTR"

mkdir -p "$WORK/root"
docker export "$CTR" | sudo tar -xp --numeric-owner -C "$WORK/root"
# drop docker-only artifacts and the setup files
sudo rm -rf "$WORK/root/.dockerenv" "$WORK/root/inner-setup.sh" "$WORK/root/staging"
mkdir -p "$(dirname "$OUT")"
sudo tar -c --numeric-owner -C "$WORK/root" . | xz -9e -T0 -c > "$OUT"
echo "rootfs: $OUT ($(du -h "$OUT" | cut -f1), $(stat -c %s "$OUT") bytes)"
