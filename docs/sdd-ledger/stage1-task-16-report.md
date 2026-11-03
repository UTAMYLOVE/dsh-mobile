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

## Fix round 1

Method: tests written first; RED = new tests run against the round-0 main sources (main tree restored from HEAD; the fake's `override` and the `watchdogMs` argument temporarily removed so it compiled), then GREEN with the fixes. RED run: 90 tests, 9 failed.

| Item | Fix | Test (RED -> GREEN) |
|---|---|---|
| I1 | `WorkRemote.eventsClientId` (DshEvents' clientId); the tracker re-baselines on every mux OPEN and on every clientId change; a watchdog calls `listSessions()` every 45 s while busy (`watchdogMs`); reconciles are serialized | `WorkTrackerTest.anIdleEventLostWhileEventsReopenedClearsBusyOnTheNextBaseline`, `theWatchdogReconcilesAStuckBusyWithoutAnyReopen` (RED -> GREEN) |
| I2 | `WorkTracker.synced`; `detachTracker` marks `endCheckPending`; the new tracker's first synced idle answer runs the normal end rule (quiet, no notice) | `DshServiceTest.aCrashRestartInTheBackgroundThatFindsNoWorkAppliesTheNormalEndRule` (RED -> GREEN) |
| M1 | `UiHolder.onWorkEnded()` (new, plus `isForeground`) starts the 10-minute grace instead of an immediate stop; `stopWhenNobodyIsLooking` removed; the "完成" decision uses `uiHolder.isForeground` (so the app's activity counter is gone from DshMobileApp) | `swipingTheTaskAwayWhileWorking...` (grace restarts, then stops), `workEndingInTheBackgroundPostsTheNoticeAndStartsTheCountdown`, `workEndingWhileTheScreenIsUpPostsNoNoticeAndNoCountdown` |
| M2 | `attachTracker` skips an unchanged `RunningDsh` | `anotherStartCommandForTheSameDshKeepsTheWatcher` (RED -> GREEN) |
| M3 | KDoc on `DshService`: START_NOT_STICKY means work is lost if the system kills the process; behaviour unchanged | none (documentation) |
| M4 | `stopRequested` flag set by 「停止」 while working; the end does not announce | `pressingStopPostsNoDoneNotice` (RED -> GREEN) |
| M5 | `clip` drops a high surrogate at the cut | `NotifierTest.aClipNeverSplitsASurrogatePair` (RED -> GREEN); `theWorkingNotificationClipsALongTitleAndLeaksNoUrlOrToken` (40-char clip, "等 3 件", no http/token; already GREEN against round-0 clip) |
| M6 | Swipe tests go through `app.uiHolder.onUiStart/onUiStop`, the 10-minute countdown (looper `idleFor`) and `app.stopService` (recorded by Robolectric); assert the holder set; both branches of the stopService guard (work held: not stopped; idle: stopped) and both foreground/background branches of the end rule; main-thread waits use the looper instead of sleeps (two short negative-assertion sleeps remain, 150/300 ms). Mutation check: replacing the guard in DshMobileApp with `true` makes `swipingTheTaskAwayWhileWorking...` fail | as listed |

`swipingTheTaskAwayWhenIdleBehavesAsBefore` passed in the RED run too, as intended (it pins the unchanged idle behaviour).

DshMobileApp.kt now has only: `workRemoteFactory` seam and the `uiHolder.stopService` work guard (activity counter removed). UiHolder.kt (ui/) got two small additions at the end of the class (`isForeground`, `onWorkEnded`); the coordinator ruled M1 through a uiHolder method, but note the possible merge overlap with Task 4.

Full suite: 738 tests, 0 failures.
