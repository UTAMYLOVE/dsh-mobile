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
# back to it, so pictures can be attached there too. Same version as the sharp dsh installed.
SHARP_V="$(node -p "JSON.parse(require('fs').readFileSync('/opt/dsh/${DSH_PINNED_VERSION}/node_modules/sharp/package.json','utf8')).version")"
npm install --prefix "/opt/dsh/${DSH_PINNED_VERSION}" --no-save --force --cpu=wasm32 "@img/sharp-wasm32@${SHARP_V}" \
  --no-audit --no-fund --loglevel=error
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
