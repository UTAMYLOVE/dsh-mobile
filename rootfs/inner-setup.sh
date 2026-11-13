#!/usr/bin/env bash
# Runs INSIDE the target-arch Debian container. Not for direct host use.
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl xz-utils git ripgrep bash coreutils

# --- Node.js (official tarball, sha256 verified twice) ---
cd /tmp
F="node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"
curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${F}"
curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt"
echo "${NODE_SHA}  ${F}" | sha256sum -c -
grep " ${F}\$" SHASUMS256.txt | sha256sum -c -
tar -xJf "$F" -C /usr/local --strip-components=1 \
  --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' \
  --exclude='*/include' --exclude='*/share/doc' --exclude='*/share/man' --exclude='*/share/systemtap'
rm -f "$F" SHASUMS256.txt
node --version

# --- dsh, installed in the target arch/libc ---
mkdir -p "/opt/dsh/${DSH_PINNED_VERSION}"
npm install --prefix "/opt/dsh/${DSH_PINNED_VERSION}" "@deepseek-ai/dsh@${DSH_PINNED_VERSION}" \
  --no-audit --no-fund --loglevel=error
ln -sfn "/opt/dsh/${DSH_PINNED_VERSION}" /opt/dsh/current
"/opt/dsh/current/node_modules/.bin/dsh" --version
# integrity check against pinned dist.integrity (from the generated lockfile)
GOT="$(node -p "require('/opt/dsh/${DSH_PINNED_VERSION}/package-lock.json').packages['node_modules/@deepseek-ai/dsh'].integrity")"
[ "$GOT" = "$DSH_INTEGRITY" ] || { echo "dsh integrity mismatch: $GOT" >&2; exit 1; }
# sharp's WebAssembly build, next to the glibc one: on Android's own node (no proot) the glibc build cannot load and sharp falls
# back to it, so pictures can be attached there too (npm leaves it out here: run 43337935453). It is installed in a directory
# of its own and only the packages the dsh tree lacks are copied in: an npm install with --cpu=wasm32 in the dsh tree itself
# removed dsh's linux addons and dsh did not start (run 43325750462). The app's dsh updater runs the same script.
cat > /usr/local/bin/dshm-add-sharp-wasm <<'SH'
#!/bin/bash
# dshm-add-sharp-wasm DIR: adds @img/sharp-wasm32 (the version of DIR's sharp) and what it needs to DIR/node_modules.
set -euo pipefail
DIR="$1"
V="$(node -p "JSON.parse(require('fs').readFileSync('$DIR/node_modules/sharp/package.json','utf8')).version")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
npm install --prefix "$TMP" --force --cpu=wasm32 "@img/sharp-wasm32@$V" --no-audit --no-fund --loglevel=error
for d in "$TMP"/node_modules/*; do
  n="$(basename "$d")"
  case "$n" in
    @*) mkdir -p "$DIR/node_modules/$n"
        for s in "$d"/*; do [ -e "$DIR/node_modules/$n/$(basename "$s")" ] || cp -a "$s" "$DIR/node_modules/$n/"; done ;;
    *)  [ -e "$DIR/node_modules/$n" ] || cp -a "$d" "$DIR/node_modules/" ;;
  esac
done
test -f "$DIR/node_modules/@img/sharp-wasm32/package.json"
SH
chmod 755 /usr/local/bin/dshm-add-sharp-wasm
BEFORE="$(find "/opt/dsh/${DSH_PINNED_VERSION}/node_modules" -type f | wc -l)"
dshm-add-sharp-wasm "/opt/dsh/${DSH_PINNED_VERSION}"
# nothing of dsh's own tree may be gone, and dsh still starts
[ "$(find "/opt/dsh/${DSH_PINNED_VERSION}/node_modules" -type f | wc -l)" -ge "$BEFORE" ]
"/opt/dsh/current/node_modules/.bin/dsh" --version
test -f "/opt/dsh/${DSH_PINNED_VERSION}/node_modules/@img/sharp-wasm32/package.json"
test -f "/opt/dsh/${DSH_PINNED_VERSION}/node_modules/@deepseek-ai/dsh/lib/bin.js"
(cd "/opt/dsh/${DSH_PINNED_VERSION}" && npm ls --all > npm-ls.txt 2>&1 || true)

# --- app layout ---
# /root/workspace and /root/.dsh are only mount points: the app binds the user's data (kept outside the rootfs) onto them.
mkdir -p /opt/dshm/plugins /root/workspace /root/.dsh
if [ -d /staging ] && [ -n "$(ls -A /staging)" ]; then cp -a /staging/. /opt/dshm/; fi
echo "${ROOTFS_VERSION}" > /etc/dshm-rootfs-version

# --- strip ---
apt-get purge -y --auto-remove curl xz-utils || true
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/log/apt /var/log/dpkg.log /var/log/alternatives.log \
       /root/.npm /root/.cache /tmp/* /usr/share/man /usr/share/doc /usr/share/info /usr/share/lintian \
       /usr/local/lib/node_modules/npm/man /usr/local/lib/node_modules/npm/docs
find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
