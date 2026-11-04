#!/bin/bash
# Runs on the CI runner while the emulator is up: push, run the checks, start dsh, drive it, restart it, report.
set -x
S=$1   # stage dir (n/)
adb shell rm -rf /data/local/tmp/n
tar -czf n.tar.gz -C "$(dirname "$S")" n
adb push n.tar.gz /data/local/tmp/n.tar.gz > /dev/null
adb shell "cd /data/local/tmp && tar -xzf n.tar.gz && rm n.tar.gz && chmod -R 755 n/usr/bin"
adb shell sh /data/local/tmp/n/device-run.sh checks > checks.txt 2>&1
cat checks.txt
adb shell sh /data/local/tmp/n/device-run.sh start
for i in $(seq 1 90); do adb shell cat /data/local/tmp/n/r/dsh.out > dsh.out 2>/dev/null; grep -q "dsh web:" dsh.out && break; sleep 2; done
cat dsh.out | grep -vE "token=" | tail -40
adb forward tcp:3080 tcp:3080
node spike/native/client.mjs dsh.out "$S/dsh/node_modules" first || true
adb shell sh /data/local/tmp/n/device-run.sh stop
adb shell sh /data/local/tmp/n/device-run.sh start
for i in $(seq 1 90); do adb shell cat /data/local/tmp/n/r/dsh.out > dsh2.out 2>/dev/null; grep -q "dsh web:" dsh2.out && break; sleep 2; done
node spike/native/client.mjs dsh2.out "$S/dsh/node_modules" second || true
adb shell cat /data/local/tmp/n/r/dsh.out | grep -vE "token=" | tail -60 > dsh-final.txt
adb shell cat /data/local/tmp/n/r/fetch.log > fetch.log 2>/dev/null
true
