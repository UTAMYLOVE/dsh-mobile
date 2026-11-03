# Stage 1 Task 12 report: overview page (總覽)

Branch `lane-t12`, based on 975e345.

## What changed
- `gateway/CostMeter.kt`: `todayUsd(Provider)`, `monthUsd()` (Asia/Taipei calendar month), `revision: StateFlow<Long>` (bumped by `record`, `alertUsd`, `hardCapUsd`). New prefs keys `day_usd_deepseek`, `day_usd_anthropic`, `month`, `month_usd`; the old `day`/`day_usd` keys and `todayUsd()` (the total) are unchanged. Old stores report 0 per provider and 0 for the month.
- `heartbeat/HeartbeatScheduler.kt`: `HeartbeatScheduler.nextRunAt(context): Flow<Long?>` over `getWorkInfosForUniqueWorkFlow`; null when nothing is scheduled.
- `overview/ReminderLog.kt` (new): keeps 10, JSON in prefs `dshm_reminders`, `revision`. Written with kotlinx JsonElement because the serialization compiler plugin is not applied in this module. A damaged value is read as empty.
- `overview/OverviewSource.kt` (new): `OverviewState` (with `nextHeartbeatAtMs`, `heartbeatIntervalMin`, `unverifiedDsh`; Rulings 7 and 24) and `OverviewSource`. It combines the meter and reminder revisions, the minute tick, and the heartbeat, agents and recent flows. It is cold: it computes only while collected.
- `ui/overview/OverviewScreen.kt` (new): banner, spend panel (HudRing 240 dp, count-up US$ with 2 decimals, `今日花費 US$%.4f` description), three cost cells that shrink digits by length, agents panel (tap goes to the agents tab, dots, "—" without dsh), heartbeat panel (Meter with ProgressBarRangeInfo), reminders panel, recent panel (3 rows, tap opens).
- Minimal additive edits to shared files:
  - `DshMobileApp.kt`: `reminderLog`, `overviewSource`, and `HeartbeatDeps.notify` now logs the reminder and then notifies. The gateway `onNotify` is untouched.
  - `ui/MainActivity.kt`: an `ShellTab.OVERVIEW` branch in the page `when`. The state is collected inside the page lambda, so only while the overview is current.
  - `ui/shell/Tags.kt`: the seven new tags.
  - `res/values/strings.xml`: `overview_*` strings.

## Tests
- Added: CostMeterTest (4), ReminderLogTest (2), OverviewSourceTest (5), OverviewScreenTest (11, including the 412 dp layout test: no overlap, three cells inside the width, last row above the reserved dock band when scrolled to the end), HeartbeatSchedulerTest (`nextRunAtReportsTheScheduledTime`).
- Focused run green. Full suite: see the final message.
- RED evidence: the tests were written before the implementation, but I did not run them before the implementation existed. The first run after implementing had 6 failures (serialization plugin missing; leading spaces trimmed from string resources; a layout test that did not scroll to the end; an exact-match range-info matcher). All were fixed and are green.

## Deviations
- The month figure uses 2 decimals (the mockup shows 1) so the three cells are consistent.
- The agents limit is read each time the state is recomputed, from `AgentSettingsStore.load(...).maxConcurrent`, the same source as the agents tab.
- `unverifiedDshVersion = { null }` until Task 13.
- Android trims leading spaces in string resources, so the separating space is added in code.

## Screenshots (Robolectric NATIVE, 412x915 dp, throwaway test not committed)
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t12-shots/overview-normal.png
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t12-shots/overview-normal-end.png (scrolled to the end; the last card clears the dock)
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t12-shots/overview-large-numbers-banner.png (banner, 1358.01 on the ring, 12345.67 in a cell, no dsh)

## Concerns
- `nextRunAt` is when WorkManager makes the work eligible; Android may run it later, so the "n minutes" figure can show 0 for a while.
