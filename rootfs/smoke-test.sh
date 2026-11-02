#!/usr/bin/env bash
# Usage: smoke-test.sh <rootfs.tar.xz>
# Extracts the rootfs and runs checks inside it via chroot (qemu-user binfmt
# handles foreign arch on CI; proot is used instead if chroot is unavailable).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
. "$HERE/versions.env"

TAR="${1:-}"
fail=0
ok()   { echo "PASS: $*"; }
bad()  { echo "FAIL: $*"; fail=1; }

if [ -z "$TAR" ] || [ ! -f "$TAR" ]; then
  echo "FAIL: rootfs tarball not found: '${TAR}'"
  exit 1
fi

DIR="$(mktemp -d)"
trap 'sudo rm -rf "$DIR"' EXIT
sudo tar -xpf "$TAR" -C "$DIR" || { echo "FAIL: cannot extract $TAR"; exit 1; }

run() {
  if [ "${SMOKE_USE_PROOT:-0}" = 1 ]; then
    proot -r "$DIR" -b /dev -b /proc -0 /usr/bin/env -i HOME=/root PATH=/usr/local/bin:/usr/bin:/bin "$@"
  else
    sudo chroot "$DIR" /usr/bin/env -i HOME=/root PATH=/usr/local/bin:/usr/bin:/bin "$@"
  fi
}

v="$(run node --version 2>&1)"
case "$v" in v24.*) ok "node $v";; *) bad "node version: '$v'";; esac

major="$(run node -p 'process.versions.node.split(".")[0]' 2>&1)"
[ "$major" = 24 ] && ok "node major 24" || bad "node major: '$major'"

d="$(run /opt/dsh/current/node_modules/.bin/dsh --version 2>&1 | tail -n1 | tr -d '\r')"
[ "$d" = "$DSH_PINNED_VERSION" ] && ok "dsh --version = $d" || bad "dsh --version: '$d' (want $DSH_PINNED_VERSION)"

rv="$(cat "$DIR/etc/dshm-rootfs-version" 2>/dev/null | tr -d '\r\n')"
[ "$rv" = "$ROOTFS_VERSION" ] && ok "rootfs version $rv" || bad "/etc/dshm-rootfs-version: '$rv'"

[ -d "$DIR/root/workspace" ] && ok "/root/workspace exists" || bad "/root/workspace missing"

l="$(readlink "$DIR/opt/dsh/current" 2>/dev/null)"
case "$l" in
  "/opt/dsh/$DSH_PINNED_VERSION"|"$DSH_PINNED_VERSION") ok "/opt/dsh/current -> $l";;
  *) bad "/opt/dsh/current -> '$l'";;
esac

[ -d "$DIR/opt/dshm/plugins" ] && ok "/opt/dshm/plugins exists" || bad "/opt/dshm/plugins missing"

[ "$fail" = 0 ] && echo "SMOKE OK" || echo "SMOKE FAILED"
exit "$fail"
