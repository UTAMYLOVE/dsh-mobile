# Stage-1 Task 16 report: dsh finishes its work in the background, then stops

## Changes
- `service/WorkTracker.kt` (new): `WorkRemote` interface (+ `DshRemote.asWorkRemote()` adapter), `RunningWork`, `WorkTracker` (busy, running, stopAll). Built from `hostEvents`; rebuilt from `listSessions()` each time `muxState` becomes OPEN (events seen while the list is in flight win over the list); unknown running ids trigger one metadata refresh so subagents are interrupted, not cancelled. Retries the baseline every 2 s on failure.
- `service/DshService.kt`: holder `WORK_HOLDER = "work"`; tracker attached after each successful `ensureRunning` (a crash restart replaces it and drops its hold); working notification, `stop_work` action in `onStartCommand`; `onTaskRemoved` explicit no-op; `onDestroy` releases "work" and closes the tracker. When work ends: release, normal notification, `Notifier.showWorkDone` if app not in foreground, then `stopWhenNobodyIsLooking()` (stopSelf if not foreground, no UI grace pending, no "ui" holder).
- `notify/Notifier.kt`: `workingNotification(work)` (title "DSH 正在工作…", text first conversation clipped to 40 chars, "等 N 件", action 「停止」 -> `PendingIntent.getService` to DshService, extra `action=stop_work`), `showWorkDone(sessionId, title)`, `ACTION_STOP_WORK`.
- `DshMobileApp.kt` (wiring only): `isInForeground` (ActivityLifecycleCallbacks counter), `workRemoteFactory` test seam (default builds DshRemote on the running dsh), `uiHolder.stopService` skips stopping while "work" is held.
- Tests: `WorkTrackerTest` (5), `FakeWorkRemote` helper, `DshServiceTest` (+6 incl. crash-policy-while-work-held), `NotifierTest` (+2).

## Tests
Full unit suite: 730 tests, 0 failures. No new permissions.

## Deviations / concerns
- TDD RED was not shown: I wrote implementation and tests in one pass, then ran them (2 test-side races fixed: late collector, StateFlow conflation of a too-fast RECONNECTING->OPEN in the test).
- `onTaskRemoved` idle "as before" = no reaction (the service never had one); idle and working tests differ in holders/assertions only.
- Production risk: a RECONNECTING->OPEN flip faster than the collector could be conflated and skip a re-baseline; real reconnects have backoff delay.
- Foreground notification title when idle is unchanged ("DSH Mobile 執行中").
