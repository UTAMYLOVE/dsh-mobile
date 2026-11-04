#!/system/bin/sh
# Runs on the emulator (adb shell): official dsh on Termux's Android Node, with no proot and no Debian.
# device-run.sh checks | start | stop
N=/data/local/tmp/n
P=$N/usr
export LD_LIBRARY_PATH=$P/lib
export PATH=$P/bin:/system/bin
export HOME=$N/home DSH_HOME=$N/dsh-home TMPDIR=$N/tmp
export SSL_CERT_FILE=$P/etc/tls/cert.pem
export SPIKE_FETCH_LOG=$N/r/fetch.log DEEPSEEK_BASE_URL=http://10.0.2.2:3999 DEEPSEEK_API_KEY=fake-key DSH_TELEMETRY_MODE=DISABLED
mkdir -p $HOME $DSH_HOME $TMPDIR $N/ws $N/r
NODE="$P/bin/node --import file://$N/shim.mjs --expose-internals"
BIN=$N/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js
case "$1" in
  checks)
    chk() { echo "### $1"; shift; "$@" 2>&1 | head -20; echo "exit=$?"; }
    chk "uname" uname -a
    chk "/bin/sh exists" ls -l /bin/sh /system/bin/sh
    chk "/bin/ps" ls -l /bin/ps
    chk "ps -axo pid=,ppid=,lstart=" /bin/ps -axo pid=,ppid=,lstart=
    chk "node --version" $P/bin/node --version
    chk "node platform" $P/bin/node -p "process.platform+' '+process.arch+' '+process.versions.node"
    chk "node https" $P/bin/node -e "fetch('https://registry.npmjs.org/').then(r=>console.log('https',r.status)).catch(e=>console.log('https-fail',e.cause?.code||e.message))"
    chk "bash" $P/bin/bash -c 'echo bash-ok $BASH_VERSION'
    chk "rg" $P/bin/rg --version
    cd $N/dsh; chk "flock addon" $NODE -e "import('@deepseek-ai/node-addon-system/flock').then(async m=>{const fs=await import('node:fs');const fd=fs.openSync('$TMPDIR/l','w');await m.tryLockExclusive(fd);console.log('flock-ok')}).catch(e=>console.log('flock-fail',e.message))" 
    chk "hard link" ln $TMPDIR/l $TMPDIR/l2; rm -f $TMPDIR/l2
    chk "landlock probe" $N/dsh/node_modules/@deepseek-ai/node-addon-system-linux-x64/bin/landlock-run --probe
    chk "dsh --version" $NODE $BIN --version
    ;;
  start)
    cd $N/ws
    setsid nohup $NODE $BIN --profile web --patch $N/patch.yml --no-open --host 127.0.0.1 --port 3080 > $N/r/dsh.out 2>&1 &
    echo $! > $N/r/dsh.pid
    ;;
  stop)
    kill $(cat $N/r/dsh.pid) 2>/dev/null; sleep 2; kill -9 $(cat $N/r/dsh.pid) 2>/dev/null; true
    ;;
esac
