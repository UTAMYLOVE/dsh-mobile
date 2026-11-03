# Bug: `session/writer-held` after 「套用並重啟」 (DSH Mobile 0.2.0)

Status: investigated only. No code changed, nothing committed.
Date: 2026-11-03. dsh 0.2.1-rc.5. Termux proot 5.1.107.102.

## Symptom and trigger

- The owner saved settings with Settings → 「套用並重啟」. The restart hung, and afterwards sending in the existing conversation 「哈嘍問候」 failed:
  - the chat banner showed 「沒有完成（session/writer-held）」;
  - the dock showed 「dsh 沒有收下這則訊息：session/writer-held」.
- Android Settings → Force stop, then reopening the app, cured it.
- Correction from the owner: the trigger was 「套用並重啟」, not the 0.1.2 → 0.2.0 update.

## Verdict

The old dsh is never killed. On Android, `Process.destroy()` and `destroyForcibly()` both send **SIGTERM**, and **proot ignores SIGTERM**. As a result:

1. `DshController.stop()` waits 2 s twice.
2. It gives up silently and reports dsh as stopped.
3. 「套用並重啟」 starts a second proot + node on a new random port.
4. The first dsh keeps running. Its agent for 「哈嘍問候」 still holds the session's kernel `flock` on `session.lock`.
5. The new dsh's resume hits that lock: `SessionAlreadyOwnedError` → `session/writer-held`.

Force stop SIGKILLs every process in the app's cgroup. That kills the orphan, and the kernel releases the lock.

## Ranked causes

### 1. A leaked old dsh holds the session's `flock`, because stop never kills proot or node

**Confidence: high (about 90%).** The whole chain is proven from source. Only the on-device process listing (see "How to confirm") is missing.

#### How the app stops dsh

- `DshController.tearDownLocked` (`android/app/src/main/kotlin/app/dshmobile/service/DshController.kt:294-305`) does:
  - `process.destroy()`, then `waitFor(2 s)`;
  - `destroyForcibly()`, then `waitFor(2 s)`;
  - then it returns whatever happened. It never checks again whether the process died. It also stops the gateway and deletes the patch file (`:306-307`).
- The `Process` is proot's. `nice` execs proot, and node is proot's child (`runtime/ProotRunner.kt:28-41`, launched at `DshMobileApp.kt:162`).

#### What Android's process API actually sends

From Android libcore (ojluni):

- `UNIXProcess.destroy()` calls `destroyProcess(pid)` (`ojluni/src/main/java/java/lang/UNIXProcess.java:220-235`).
- The native side is `kill(pid, SIGTERM)` (`ojluni/src/main/native/UNIXProcess_md.c:1056-1059`).
- `UNIXProcess` does not override `destroyForcibly()`. `Process.destroyForcibly()` is the default `destroy(); return this;` (`ojluni/src/main/java/java/lang/Process.java:244-247`).
- So on a phone, **both calls send SIGTERM**. The JVM unit tests use desktop OpenJDK, where `destroyForcibly()` sends SIGKILL. That is why no test caught this.

#### What proot does with those signals

From termux/proot `src/tracee/event.c`:

- `event_loop()` installs handlers for every signal (`:330-366`).
- SIGQUIT, SIGILL, SIGABRT, SIGFPE and SIGSEGV call `kill_all_tracees2`.
- Everything else, **SIGTERM, SIGINT and SIGHUP included**, gets `SIG_IGN`, with the comment "Ignore all other signals, including terminating ones" (`:362-364`).
- So proot survives both of our signals. Node never receives a signal from us.
- `--kill-on-exit` only fires when the first tracee exits (`tracee.c:350-360`), so it does not help here.
- Even a SIGKILL to proot would not be enough:
  - `atexit(kill_all_tracees)` (`event.c:317`) does not run on SIGKILL;
  - no `PTRACE_O_EXITKILL` is set;
  - so node would be orphaned, alive, with its open fds.

#### Why the lock stays held

From `@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js`:

- A write open goes `open(id, "write")` → `tracker.claimWrite` (`:2520`) → `acquireLease` (`:2524`, `:2959-2969`) → `SessionWriteLease.acquire` (`:669-717`).
- `acquire` opens `<session dir>/session.lock` and calls `tryLockExclusive(fd)`.
- That is a real `flock(LOCK_EX|LOCK_NB)` (`node-addon-system/src/flock.c:58`).
- EAGAIN or EWOULDBLOCK becomes `SessionAlreadyOwnedError` (`:698`).
- The lock is held for the whole life of the write handle, which is the life of the live Agent. The doc block at `:618-646` says "A live but wedged holder keeps the lock until its process exits".
- There is no idle eviction of agents. The old dsh keeps every session it ever resumed locked until it dies.

#### Where the error surfaces

- Every send goes through `session/prompt` → `ApiSessionAgentController.resolve` (`dsh-api-session-controller/lib/index.js:221-245`).
- The new process has no live agent, so it resumes (`:370-402` → `dsh-agent-loop/lib/index.js:1986` `persistence.open(id, "write")`).
- The `flock` fails, and `:243` maps the error to `session/writer-held`.
- `selectModel` (`:793`), `rename` (`:827`) and file upload hit the same path.
- `follow` and `page` are reads, so the conversation still *displays* fine, which is what the owner saw.

#### Why the new dsh starts at all

- `DSH_ARGS` uses `--port 0` (`DshController.kt:342-347`), so there is never a port clash.
- `ensureRunning` only looks at `current`, which `teardown` nulled (`:177-178`, `:287-292`).
- Nothing checks for a surviving instance.

#### The settings path

`ui/SettingsScreen.kt:293-321` `apply()` runs:

1. `env.runtime.stop()` (`:305`), which leaks the old dsh;
2. `applyAgentTeams`;
3. `supervise()` (`:312`), which also makes `DshService` call `ensureRunning`;
4. `env.runtime.ensureRunning()` (`:313`), which starts dsh #2.

The session the owner had been chatting in was resumed, and so locked, in dsh #1.

#### Every other stop leaks the same way

- 「回退」/dsh update: `MainOps.kt:31-52`, `DshVersions.kt:189-224`.
- Background grace: `UiHolder.kt:78-82`.
- Heartbeat: `HeartbeatWorker.kt:86-90`.
- `DshService.onDestroy`: `DshService.kt:92-103`.
- Crash give-up: `DshService.kt:239`.
- Guest npm timeouts: `runtime/GuestCommand.kt:35-47`.

In practice the grace and heartbeat leaks are usually cleaned up when Android later kills the cached app process, which kills the whole cgroup. That is why the bug looked tied to one action.

#### Ruled out by the force-stop evidence

- **A 0.1.2 dsh surviving the update.** A package replace uses the same `forceStopPackage` → `killProcessGroup` path as Force stop. Also, the owner has corrected the trigger.
- **A stale lock on disk.** A dead process cannot hold a `flock`.

#### Why the leaked dsh sometimes dies by itself, making the bug intermittent

- `destroy()` closes our ends of its stdout and stderr pipes.
- If dsh writes a log line after that, it gets EPIPE. dsh's fail-loud handler then exits (`dsh-app-boot/lib/index.js:4225-4253`), which frees the lock.
- An idle orphan logs nothing, so it lives on, as here.

### 2. The restart "hang" is the leak plus unbounded waits with no feedback

**Confidence: medium (about 65%) for the exact mechanism; high that nothing in the UI shows it.**

#### Worst case for one attempt, all silent

| Step | Time | Source |
|---|---|---|
| Stop that achieves nothing | 4 s | `DshController.kt:95`, `:296-299` |
| Launch-line timeout | 60 s | `:94`, `:205-206` |
| Health check | 60 s | `runtime/HealthCheck.kt:17-20` |
| Workspace registration | 15 s | `runtime/WorkspaceRegistrar.kt:31-37` |

That is about **2 min 20 s** behind an indeterminate bar labelled 「處理中…」 (`SettingsScreen.kt:439-443`). Back is swallowed (`:189`) and 「關閉」 is disabled (`:436`).

#### Why dsh #2 boots slowly

dsh #2 boots under ptrace while dsh #1 is still alive.

- If the owner confirmed the "work is running" card (`askOrRun`, `SettingsScreen.kt:288-291`, `:380-385`), dsh #1 is mid-turn.
- Its gateway has been stopped (`DshController.kt:306`), so its model calls fail and get retried.
- Its tools and subagents keep running.
- Two Node processes under proot on a phone can push the cold boot towards the 60 s limits.

#### How a failed start cascades

- If dsh #2 misses a deadline, `start()` tears it down with the same non-kill (`DshController.kt:225-231`). That leaks dsh #2 as well.
- `DshService.startDsh` then counts a crash and restarts after 2 s (`DshService.kt:118-121`, `:229-244`).
- Each retry adds load and leaves one more orphan.
- After 3 failures in 10 min it gives up (`CrashPolicy.kt`).

#### What the UI does in the meantime

`DshService.attachTracker` keeps watching dsh #1's work until dsh #2 is attached (`DshService.kt:126-140`). dsh #1 is still alive, so the "working" notification and the `"work"` holder reflect a dsh the UI has already detached from (`MainActivity.kt:175-190`).

#### Side issue: 「重新啟動」 does not restart

Settings' 「重新啟動」 (`SettingsScreen.kt:323-335`) only calls `ensureRunning()`. When dsh is up it does nothing, so it can never clear this state. Error Retry (`MainActivity.kt:439-441`) behaves the same way.

### 3. Something in the same dsh process holds the session for write

**Confidence: low (about 5%).**

- `resolve()` deduplicates concurrent resumes (the `resumes` map) and returns the live Agent when one exists (`api-session-controller/lib/index.js:221-235`, `:365-369`). So a second resume in the same process never reaches `claimWrite`.
- Only two places open a session for write:
  - the agent loop's resume (`dsh-agent-loop/lib/index.js:1986`);
  - message feedback, which is short-lived and only opens for write when no live session exists (`dsh-message-feedback/lib/index.js:255-296`).
- Our plugins (`plugins/packages/{memory,notify,ui,common}`) never open sessions. The memory "dream" calls the model directly.
- The app's calls are `session/follow`, `session/page`, `session/prompt`, `session/selectModel`, `session/rename`, `fileUploads/upload` and `subagents/interruptByParent` (`remote/DshRemote.kt:69-190`). None of them opens a second writer.
- A wedged handle close in a single dsh is still possible. If cause 1 is fixed and the bug comes back without a restart, look here.

### 4. `flock` broken under proot

**Confidence: about 0%, ruled out.**

- `flock(2)` takes an fd, not a path. proot passes it through untouched, so it is the host kernel's lock on the host inode.
- The inode check in `acquire` compares `fstat` with `stat` on the same host file (`:700-707`), so the two agree.
- Force stop curing the problem proves the lock was held by a live process, not emulated or left stale.

### 5. The service and the UI both start dsh

**Not a cause on its own.**

- There is one process and one `DshController`. `ensureRunning` is serialized by `startLock` (`DshController.kt:122-132`).
- It only matters through cause 2's cascade: every failed start leaks.

## Fix plan

### For cause 1: really kill the tree, and refuse to run two

**F1. A real stop.** Add a `ProcessTreeKiller` used by `tearDownLocked` and by `ProotGuestCommand`.

1. **Find proot's pid.**
   - Parse `Process.toString()`; Android prints `Process[pid=N, hasExited=…]`.
   - Or launch through `/system/bin/sh -c 'echo dshm-pid:$$; exec "$0" "$@"'`. `exec` keeps the pid, so it is proot's.
   - Record the start time (field 22 of `/proc/<pid>/stat`) so a reused pid is never killed.
2. **Find the descendants.** Scan `/proc/[0-9]*/stat` for the ppid chain. The app can see its own uid's processes. node is proot's child, and tool subprocesses are node's children.
3. **Shut down in order.**
   1. Send SIGTERM to the **node** pid(s), proot's direct children, with `android.os.Process.sendSignal`. dsh handles SIGTERM with a graceful dispose (`dsh/lib/profile-boot-C14i-8IN.js:280-285`), which closes the write handles and so releases the locks cleanly. Wait up to about 3 s.
   2. Send **SIGQUIT** to proot. Its handler SIGKILLs every tracee (`event.c:167-177`, `tracee.c:1043-1049`).
   3. Send SIGKILL to every pid still alive in the recorded tree, and to proot.
   4. Poll `/proc` until the whole tree is gone, with a limit of about 5 s.
4. **If anything survives,** do not report the stop as done:
   - log `controller: old dsh pid N still alive after stop`;
   - make `ensureRunning` throw a distinct `DshStillRunningException` instead of launching a second dsh.

**F2. A stale-instance sweep before every start.** In `ensureRunning`, before `launcher.start`:

- scan `/proc` for this uid's processes whose cmdline contains `libproot.so` or `/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js` and that are not the current run;
- kill them with F1;
- also persist `pid + starttime` in `filesDir/dsh.pid` as a second key.

This covers leaks from any path, including future ones.

**F3. Apply F1 to `ProotGuestCommand` timeouts** (`GuestCommand.kt:35-47`).

**F4. Tests.**

- A JVM test with a fake dsh that ignores SIGTERM, the way proot does: `sh -c 'trap "" TERM; echo <launch line>; exec sleep 60'`. An ignored signal stays ignored across `exec`. The test must prove that the tree is gone after `stop()` and that no second start happens while the old one lives. Desktop `destroyForcibly()` sends SIGKILL, so the test must not rely on it: assert on the new kill path.
- An emulator e2e:
  1. prompt in a session;
  2. 「套用並重啟」;
  3. prompt the same session again; it must succeed;
  4. a `/proc` scan finds exactly one `bin.js`.

**F5. Minor.** After teardown the exit-watcher coroutine of a leaked run stays parked on `Dispatchers.IO` forever (`DshController.kt:254-255`). It goes away with F1.

### For cause 2: bound the restart and show it

**F6. Phase reporting.** Add a phase `StateFlow` on `DshController` with these states:

- `Stopping(pid)`
- `WaitingForOldExit(elapsed)`
- `Launching(elapsed)`
- `HealthCheck(elapsed)`
- `Registering`
- `Ready`
- `Failed(reason)`

Log each step with its timing, for example `controller: stopped pid=… in 850 ms`, `controller: launch line after 23 s`, `controller: healthy after 31 s`. Today a successful start or stop logs nothing, so an exported log cannot show where a restart hung.

**F7. Settings and main-screen Loading show the phase and a seconds counter instead of a bare 「處理中…」.**

- After 30 s, add 「比平常久」.
- At 90 s, or on `DshStillRunningException`, offer 「強制結束 dsh 並重啟」. It runs F1 + F2, then a start.
- If that also fails, offer 「到系統設定強制停止」, which opens `ACTION_APPLICATION_DETAILS_SETTINGS` for the package.

**F8. Make Settings' 「重新啟動」 really restart.** It should be stop (F1) + start, not `ensureRunning` only (`SettingsScreen.kt:323-335`). The same applies to the error screen's Retry when dsh is up but unusable.

**F9. Chat error handling.**

- Map `session/writer-held` to a specific message, for example 「這個對話還被舊的 dsh 佔著」, with the same 「強制重啟 dsh」 action.
- Keep it out of `Rpc.PERMANENT` (`remote/Rpc.kt:68-71`), because a restart can fix it.
- Show it once, not in both the banner and the dock (known T9 minor).

**F10. After a confirmed "work running" restart, detach the service's `WorkTracker` from the old dsh when `stop()` is called.**

Today it follows the leaked dsh until the new one attaches. Release `"work"` at that point and end the "working" notification.

### For cause 3, only if the bug returns after F1/F2

Log the `session/writer-held` session id together with the dsh pid. Check whether the holder is the current pid with `/proc/locks` (see below). If it is the current dsh, it is a dsh bug: report it upstream with the session id and the steps.

## How to confirm on the device, without adb

### A. Differential repro (about 2 minutes)

1. Open conversation X, send a message and wait for the reply.
2. Settings → 「套用並重啟」 and wait for it to finish.
3. Send in X. Expected: `session/writer-held`.
4. Start a **new** conversation and send. Expected: it works. A fresh session was never locked by the old dsh.
5. Settings → 「重新啟動」, then send in X again. It still fails, because that button never stops dsh.
6. Force stop, reopen, send in X. It works.

Cause 1 predicts exactly this pattern. Cause 3 would not fail only on sessions used before the restart, and would not survive in-app restarts while clearing on Force stop.

### B. Show the second dsh and the lock holder

Use a fresh conversation, so the agent's bash tool runs inside the new dsh's proot, where `/proc` is the host's. Ask the agent to run:

```sh
for p in /proc/[0-9]*; do c=$(tr '\0' ' ' < $p/cmdline 2>/dev/null); case "$c" in *bin.js*|*libproot*) echo "${p#/proc/} ppid=$(cut -d' ' -f4 $p/stat) $c";; esac; done
grep FLOCK /proc/locks
for f in $(find /root/.dsh -name session.lock); do echo "$(stat -c %i $f) $f"; done
```

Expected:

- **two** `node … bin.js … --port 0` lines, each under its own `libproot.so`;
- a `FLOCK … WRITE <pid> <maj:min:inode>` line whose inode is the `session.lock` of 「哈嘍問候」 and whose pid is the **older** node (the lower pid, whose parent is the older proot).

### C. Error log

Settings → export the error log.

- The apply produces no `dsh exited` or `controller` line for the old instance. Today the controller logs nothing on a stop.
- If the restart hung, look for any of these:
  - `controller: start failed: dsh did not print its launch line within 60000 ms`
  - `controller: start failed: dsh health check failed`
  - `service: could not start dsh: …`
  - repeated restarts
- After F6, the log will show the stop and start timings directly.

## Quickest workaround for the owner, now

- **After every 「套用並重啟」, 「回退」 or dsh update, force stop the app** (Android Settings → Apps → DSH Mobile → Force stop) and reopen it. Do the same as soon as any conversation shows `session/writer-held`. Nothing is lost: conversations are on disk under `userdata`.
- **Don't** press 「套用並重啟」 again to fix it. Each press leaks one more dsh.
- **Don't** expect Settings' 「重新啟動」 to fix it. It does not stop anything.
- If the error shows up and force stopping is inconvenient, a **new** conversation still works until then.
- To lower the chance of hitting it, apply settings right after opening the app, before chatting. Only sessions used before the restart get locked.
