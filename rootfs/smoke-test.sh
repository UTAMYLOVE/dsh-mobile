#!/usr/bin/env bash
# Usage: smoke-test.sh <rootfs.tar.xz>
# Extracts the rootfs and runs checks inside it via chroot (qemu-user binfmt
# handles foreign arch on CI; proot is used instead if chroot is unavailable).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
. "$HERE/versions.env"

ROOTFS_VERSION="${ROOTFS_VERSION}-$(bash "$HERE/source-hash.sh")" # as build-rootfs.sh and BuildConfig.ROOTFS_VERSION

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

sudo test -d "$DIR/root/workspace" && ok "/root/workspace exists" || bad "/root/workspace missing"
sudo test -d "$DIR/root/.dsh" && ok "/root/.dsh exists" || bad "/root/.dsh missing"

l="$(readlink "$DIR/opt/dsh/current" 2>/dev/null)"
case "$l" in
  "/opt/dsh/$DSH_PINNED_VERSION"|"$DSH_PINNED_VERSION") ok "/opt/dsh/current -> $l";;
  *) bad "/opt/dsh/current -> '$l'";;
esac

[ -d "$DIR/opt/dshm/plugins" ] && ok "/opt/dshm/plugins exists" || bad "/opt/dshm/plugins missing"

[ -f "$DIR/opt/dshm/plugins/memory/index.js" ] && ok "/opt/dshm/plugins/memory/index.js exists" || bad "/opt/dshm/plugins/memory/index.js missing"
[ -f "$DIR/opt/dshm/plugins/notify/index.js" ] && ok "/opt/dshm/plugins/notify/index.js exists" || bad "/opt/dshm/plugins/notify/index.js missing"
[ -f "$DIR/opt/dshm/plugins/ui/index.js" ] && ok "/opt/dshm/plugins/ui/index.js exists" || bad "/opt/dshm/plugins/ui/index.js missing"
[ -f "$DIR/opt/dshm/plugins/guard/index.js" ] && ok "/opt/dshm/plugins/guard/index.js exists" || bad "/opt/dshm/plugins/guard/index.js missing"

for t in git rg bash; do
  p="$(run sh -c "command -v $t" 2>&1)"
  [ -n "$p" ] && [ "${p#/}" != "$p" ] && ok "$t -> $p" || bad "$t not found: $p"
done

# Every load-time change the app makes to dsh's Auto review (android/app/src/main/assets/dshn/review-json.mjs) must still apply to
# the pinned dsh: a patch whose anchor moved is skipped without a sound, and its fix (a reviewer slip asks instead of failing,
# the conversation's own effort, the reason in Traditional Chinese) would be gone with every test still green.
sudo cp "$HERE/../android/app/src/main/assets/dshn/review-json.mjs" "$DIR/tmp/dshm-review-json.mjs"
sudo tee "$DIR/tmp/dshm-patch-check.mjs" > /dev/null <<'JS'
import { readFileSync } from 'node:fs'
import { PATCHES, patchAutoReview } from '/tmp/dshm-review-json.mjs'
const lib = '/opt/dsh/current/node_modules/@deepseek-ai/dsh-experimental-auto-review/lib/index.js'
let source
try { source = readFileSync(lib, 'utf8') } catch { console.log('no auto-review plugin at ' + lib); process.exit(0) }
const out = patchAutoReview(source)
const missing = PATCHES.filter((p) => !out.includes(p.to)).map((p) => p.name)
console.log(missing.length === 0 ? 'all ' + PATCHES.length : 'missing ' + missing.join(','))
JS
pc="$(run node /tmp/dshm-patch-check.mjs 2>&1 | tail -n1)"
case "$pc" in all\ *) ok "Auto review patches apply to dsh $DSH_PINNED_VERSION ($pc)";; *) bad "Auto review patches: $pc";; esac

[ "$fail" = 0 ] && echo "SMOKE OK" || echo "SMOKE FAILED"
exit "$fail"
