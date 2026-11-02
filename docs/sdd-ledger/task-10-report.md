# Task 10 report: heartbeat worker, holder-count API, carried-over fixes

Status: DONE_WITH_CONCERNS (see Concerns).

## Final signatures

```kotlin
// service/DshController.kt
class RootfsNotInstalledException : IllegalStateException("rootfs not installed")   // thrown by ensureRunning
interface DshRuntime { fun stop(); suspend fun ensureRunning(): RunningDsh }          // unchanged (Task 9)
interface DshHolderRuntime : DshRuntime {                                             // new; DshController implements it
    fun retain(owner: String); fun release(owner: String); fun stopIfUnretained(): Boolean
}
class DshController(...unchanged ctor...) : DshHolderRuntime {
    val holders: Set<String>              // snapshot copy
    override fun retain(owner: String)    // set semantics (same owner twice = one hold), synchronized
    override fun release(owner: String)
    override fun stopIfUnretained(): Boolean  // true iff holders empty (stop() ran; also true when nothing was running)
    override fun stop()                   // still the forced stop; now also effective mid-start
}
// service/DshService.kt: companion const val HOLDER = "service"
// notify/Notifier.kt:    const val ID_HEARTBEAT = 5
// runtime/DshClient.kt:  suspend fun postJsonForText(path, json): DshClient.TextResponse(code: Int, body: String)
// heartbeat/HeartbeatScheduler.kt
object HeartbeatScheduler {
    const val WORK_NAME = "dshm-heartbeat"; DEFAULT_INTERVAL_MIN = 45; MIN_INTERVAL_MIN = 30; MAX_INTERVAL_MIN = 60
    fun schedule(context: Context, intervalMin: Int = 45); fun cancel(context: Context)
}
// heartbeat/HeartbeatWorker.kt
class HeartbeatDeps(runtime: DshHolderRuntime, clientFor: (LaunchUrl) -> DshClient, notify: (NotifyRequest) -> Unit,
                    serviceNotification: () -> Notification, errorLog: ErrorLog, timeoutMs: Long = 300_000)
class HeartbeatWorkerFactory(deps: () -> HeartbeatDeps) : WorkerFactory()
class HeartbeatWorker(context, params: WorkerParameters, deps: HeartbeatDeps) : CoroutineWorker   // HOLDER = "heartbeat"
// DshMobileApp : Application(), Configuration.Provider  -> heartbeatDeps (lazy), workManagerConfiguration (HeartbeatWorkerFactory),
//                                                          onCreate() { HeartbeatScheduler.schedule(this, 45) }
```

## Behaviour
- Worker flow: go foreground (best effort) -> `retain("heartbeat")` -> `withTimeout(5 min) { ensureRunning; login; POST /dshm/heartbeat "{}" }` -> 200: each well-formed reminder (string title and body) goes to `notify(NotifyRequest(title, body, null))`, ErrorLog line `[heartbeat] done: promoted=N reminders=M` (no content). Anything else (non-200 incl. 409/500/502, unparseable body, timeout, any exception incl. RootfsNotInstalledException, login failure) -> no notification and one ErrorLog line with only the status code or exception class. `finally` (NonCancellable, IO): `release("heartbeat")`, `stopIfUnretained()`. Always `Result.success()`; a cancellation by WorkManager is rethrown after the finally.
- Foreground (ambiguity 2): `setForeground` and the notification build are in a try/catch; a failure (e.g. `ForegroundServiceStartNotAllowedException` from a background start on Android 12+) is logged as `[heartbeat] could not go foreground (<class>), continuing in the background` and the heartbeat goes on. The cold start still works because WorkManager keeps the process alive for the duration of doWork. The foreground notification uses its own id (`ID_HEARTBEAT`), not `ID_SERVICE`: WorkManager cancels the notification it was given when the work ends, which would have removed the DshService's notification while the service still runs.
- Manifest: WorkManager's `SystemForegroundService` declares no foreground type, and Android 14+ rejects a type that the service does not declare. I added an `androidx.work.impl.foreground.SystemForegroundService` entry (`tools:node="merge"`, `foregroundServiceType="specialUse"` plus the special-use subtype property). No new uses-permission; the merged manifest still has exactly the 9 allowed ones (ManifestPermissionsTest green).
- Injection (ambiguity 1): `DshMobileApp` is a `Configuration.Provider` with a `HeartbeatWorkerFactory`; tests build the worker through `TestListenableWorkerBuilder.setWorkerFactory(HeartbeatWorkerFactory { fakes })`.
- Holder race fix: `stopIfUnretained` decides and marks the run `stopRequested` atomically under the state lock; `runningInfo()` ignores a run that is stopping, and `ensureRunning` tears a leftover down (blocking) before starting, so a holder arriving right after the decision gets a fresh dsh, never the dying one, and the old teardown cannot delete the new run's patch file.
- (a) launcher throws -> the patch file (token inside) is deleted. (b) `RootfsNotInstalledException` (still an IllegalStateException with the same message) -> DshService logs, `stopSelf()`, no CrashPolicy, no give-up notice. (c) GIVE_UP -> `crashPolicy.reset()`. (d) `stop()` mid-start sets `stopPending` (only while a start is in flight; a stop while idle does not poison the next start); start checks it right after the Run is published and again, under the state lock, before publishing running state (`_isRunning` is now set in that same lock); it cleans up and throws `CancellationException`. A stop that makes the launch fail (process killed while waiting for the launch line) is also reported as `CancellationException`, not as "dsh exited".
- DshService: `retain("service")` in onStartCommand, `release("service")` in onDestroy followed by the existing forced `stop()`.
- `DshClient.postJsonForText` (new, existing API untouched): reads the body (bounded to 1 MiB) inside the cancellable exchange. Found during TDD: with `postJson` the blocking `body.string()` ignored the 5-minute timeout while the server dribbled its body.

## Tests (full suite 235, 0 failures, 0 errors, 0 skipped; output pristine)
New: DshControllerTest +14 (now 32), HeartbeatWorkerTest 17, HeartbeatSchedulerTest 8, DshClientTest +2 (now 8), DshServiceTest 1. Heartbeat tests use the real DshController with a fake `sh` dsh process in front of a MockWebServer (FakeDshServer got an optional `/dshm/heartbeat` route), so holder behaviour is exercised end to end:
- held by service -> no second start, still running, still held; no other holder -> stopped; service joins mid-heartbeat -> not stopped; heartbeat holds "heartbeat" while working; 500/409/502, unparseable body, rootfs missing, login failure, timeout (0.7 s limit, returns in < 2.5 s despite a 3 s body delay), external cancellation, foreground failure -> all `success()`, no notification, holders released; two reminders -> two notifications; promoted count logged, no content; malformed reminder entries skipped; a throwing notify does not stop the next reminder.
- Scheduler: 5 -> 30 min, 90 -> 60, 30/60/45 kept, NetworkType.CONNECTED, unique name, re-schedule keeps the same work id (UPDATE), cancel.
- Controller: holder set semantics, snapshot, stopIfUnretained held/unheld/idle, forced stop while held, fresh dsh for a late holder, 8-thread retain/release churn, stopIfUnretained racing retain never stops a held dsh, patch deleted when launcher throws, stop before the process exists, stop while waiting for the launch line, idle stop not poisoning, start after a cancelled start.
- DshServiceTest (Robolectric): rootfs missing -> stopSelf, ErrorLog line, no give-up notification, CrashPolicy untouched (next three onCrash = RESTART, RESTART, GIVE_UP), holder retained then released on destroy.

## TDD evidence
- RED 1: DshControllerTest with the new tests, main code stashed: `compileDebugUnitTestKotlin` failed (Unresolved reference RootfsNotInstalledException, holders, retain, release ...).
- GREEN 1: `DshControllerTest` 32 tests, 0 failures.
- RED 2: heartbeat tests written first: compile failure (Unresolved reference HeartbeatScheduler ...).
- Iterations: first heartbeat run 25 tests / 2 failed (timeout test: blocking body read ignored the timeout; cancel test: MockWebServer shutdown waited on a 10 s delayed handler) -> added `DshClient.postJsonForText`, shortened test delays; then DshClientTest cancellation failed with `SocketException` (own `call.cancel()` outranking the cancellation) -> convert to CancellationException; then all green.
- Final: `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks`: 235 tests, 0 failed, no output. `./gradlew :app:assembleDebug --no-daemon -q`: exit 0.

## Compile-verified only
- Real WorkManager scheduling after reboot / Doze / constraint behaviour (the test WorkManager only checks the request's interval, constraint and unique name).
- `setForeground` through the real `SystemForegroundService` on a device (type declaration in the merged manifest is verified by inspection of the merged manifest file, not by running it); the Android 12+ background-start refusal path is covered only by a stand-in exception.
- `DshService` crash/restart/GIVE_UP flow, including (c) `crashPolicy.reset()` after GIVE_UP (no test: CrashPolicy is not injectable and the flow needs a crashing dsh); DshMobileApp wiring beyond what Robolectric exercises (the app's `onCreate` does schedule in every Robolectric test without noise).

## Files
Main: heartbeat/{HeartbeatWorker,HeartbeatScheduler}.kt (new); service/{DshController,DshService}.kt, runtime/DshClient.kt, notify/Notifier.kt (ID_HEARTBEAT), DshMobileApp.kt, AndroidManifest.xml, gradle/libs.versions.toml and app/build.gradle.kts (androidx.work:work-testing 2.12.0, same version ref as work-runtime; both files are CRLF and kept so).
Tests: heartbeat/{HeartbeatWorkerTest,HeartbeatSchedulerTest}.kt (new), service/{DshControllerTest,DshServiceTest}.kt, runtime/{DshClientTest,FakeDshServer}.kt.

## Self-review findings (fixed)
- Blocking body read defeated the timeout (above); `ID_SERVICE` reuse would have dismissed the service notification (above); stopping-run could be handed to a new holder (above).
- ErrorLog lines never contain response bodies, reminder text or URLs.

## Concerns
1. `stopIfUnretained()` returns true also when nothing was running (the brief's "whether it actually stopped" is ambiguous); documented in KDoc.
2. Holders are names in a set, not a counter: `retain("x")` twice then one `release("x")` drops it. Intentional (owners are fixed names).
3. `DshService.onDestroy` still force-stops dsh even if another holder (heartbeat, later "ui") holds it: the user ending the service is an explicit stop. Task 11a may want `stopIfUnretained` there.
4. The heartbeat is skipped, not retried, when WorkManager has run it while dsh is mid-update (Task 9 `DshVersions` swaps) or busy (409): next period tries again, as specified.
5. `DshMobileApp.onCreate` schedules with the constant 45; Task 11b must call `schedule` with the setting (UPDATE keeps timing).
6. `DshClient.postJsonForText` was added (new method, existing signatures untouched).
