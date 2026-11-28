# 小雨娛樂 1.0 — package specs

Read `ARCHITECTURE.md` first, then the contracts in `android/core/src/main/kotlin/app/agency/core/`
(`base/Clock.kt`, `store/*.kt`, `rules/Rules.kt`, `stage/Feature.kt`, `plan/Plan.kt`, `agent/Card.kt`, `agent/CompanyApi.kt`,
`agent/Tool.kt`). The contracts are fixed: extend them only by adding (a new entry kind, a new card, a new field with a
default), and say so in your report.

Conventions for every package:

- Kotlin, 4 spaces, lines ≤ 130 chars, KDoc on public types saying *why*. No `android.*` in `:core`.
- Pure functions over `Store` + `Clock` + `Rules`; nothing reads the system clock or the default time zone directly.
- Words shown to him are Traditional Chinese with no Latin letters (level letters C/B/A excepted). Code, comments, test names:
  English.
- **No personal data**: tests build rules with `Fixtures.rules()` (invented numbers: e.g. 2000 kcal, 100 g protein, class
  days Mon–Fri, wake 07:00 …). Never copy a number from a real person.
- The 0.9 code is in git at tag `v0.9.1+19` (`git show v0.9.1+19:android/core/src/main/kotlin/app/agency/core/...`). Read it
  for ideas where useful; do not restore its rigid flows.
- Build: `cd android && ANDROID_HOME=/opt/android-sdk ./gradlew <tasks> --no-daemon -q`. Maven Central sometimes answers 429:
  just run again (the cache keeps what it got). Never add a repository or a mirror.

---

## A. `day` — what happened today

`DayState.of(day, entries, recent, rules, stage, zone)` (pure). `entries` = that logical day's entries; `recent` = the previous 14
days' entries (for statuses that last). Fields:

- `checkIn: CheckIn?`, `checkInAt`, `checkOut`, `checkOutAt`.
- `meals`: each `(entry id, Meal, kcal, proteinG)`; `kcal`, `proteinG` totals; `leftKcal = diet.kcal - kcal`.
- `budget(slot)`: what the next meal of that slot may be: `min(mealCapKcal, diet.kcal - eaten - reserve)` where `reserve` is
  the planned kcal of the meals after it that are still expected today (FIRST → dinner + night, DINNER → night, NIGHT → 0);
  never below 0. A big first meal therefore shrinks dinner automatically.
- `waterMl`, `waterTargetMl(dayNumber)`: `bottlesAtStart` bottles for the first `rampAfterDays` days since joining, then one
  quarter bottle more per week up to `bottlesGoal`.
- Ride: `rideOutAt`, `rideBackAt`, `income`, `riding = out && !back`.
- `tasks: Map<TaskId, TaskDone>` (last one wins), `drinks`, `mood`, `body`.
- Status: `restDay` (a REST_DAY today), `sick` / `injured` (a SICK / INJURED entry in `recent`+today with no later RECOVERED),
  `sickDays` (consecutive), `firstDayBack` (RECOVERED yesterday or today after being unwell), `examWeek` (EXAM_WEEK within
  the last 7 days).
- `sleepHours`: from yesterday's check-out to today's check-in when both exist, else null.
- `violations`: today's `Violation` entries.

`Nutrition.resolve(rules, name, kcalGuess, proteinGuess, source)` → `MealItem`:
1. his table first (`rules.foods`: normalised name or alias contained either way) → exact, `TABLE`;
2. `LABEL` when the numbers were read from a label/receipt/menu he confirmed → as given;
3. else `ESTIMATE`: kcal raised by `diet.estimateMarginPct` and rounded up to the next 10; protein rounded down.
`banned`: the first `diet.banned` category one of whose words is contained in the name, unless the name matches a table
food or an entry of `diet.allowed`.

`Violations` (code only; each is written once per day, BANNED once per meal entry). Only for open features:

| code | when |
|---|---|
| `BANNED_FOOD` | a logged item is banned |
| `MEAL_OVER_CAP` | one meal's kcal > `mealCapKcal` |
| `OVER_KCAL` | day total > `diet.kcal` |
| `UNDER_MIN` | at day close: MEALS open, at least one meal logged, total < `minKcal` |
| `MISSED_NIGHT_MEAL` | at day close: he rode today and there is no NIGHT meal |
| `LATE_CHECK_OUT` | check-out after today's bedtime, or none by day close (when he checked in) |
| `CAFFEINE_LATE` | a CAFFEINE drink after `caffeineCutoff` |
| `ZERO_CAL_OVER` | more ZERO_CAL drinks this calendar week than allowed |
| `REST_DAY_OVER` | never written: a second rest day in a week is refused by the tool instead |

`Violations.onWrite(...)` is called by tools after a write; `Violations.onClose(day, …)` by rollover. Both idempotent.

## B. `plan` — today's items, the next thing, today's changes

`DayPlanner.plan(day, now, rules, stageState, state: DayState, week: WeekSoFar, lessons: List<LessonSlot>, habits:
List<HabitToday>, training: TrainingRule = rules.training, extra: List<PlanItem> = emptyList())` → `DayPlan`. (`training` is the
effective load after progression; `extra` are ready-made stage-4 items from G; `LessonSlot`, `HabitToday`, `WeekSoFar` are in
`plan/Plan.kt`.)

An item exists only when its feature is open at `stageState.stage` **and** its rule values are set (`RulesCheck`).
Times come from `rules.classDay` or `rules.freeDay`. Status: DONE when recorded; PENDING before its time; LATE after `at`
(or after `until` for deadline items) while still doable; MISSED when the day is closed or the window is gone; OFF when an
adjustment removed it.

Items (id → feature; all count unless said):

- `check_in` (CHECK_IN) at `wake`; tap `Open(CHECK_IN)`. `check_out` until today's bedtime; tap `Open(CHECK_OUT)`.
  Today's bedtime = `bedtime`, and once EARLY_BED is open it moves `sleep.shiftMinutesPerWeek` earlier for every full week
  since EARLY_BED opened, never earlier than `bedtimeGoal`.
- `task:MORNING_STRETCH`, `task:VOCAL_BASICS` after wake; tap `Open(TIMER, id)`.
- `lesson:<n>` per slot from `LessonDay` (see E); tap `Open(LESSONS)`.
- `meal:FIRST` at `firstMeal`, `meal:DINNER` at `dinner`, `meal:NIGHT` at `nightMeal`; tap `Open(CAMERA, slot)`; detail shows
  the budget before, the kcal after. A meal is DONE when logged **with a photo**; logged without one it stays PENDING with
  detail 「缺照片」 (a later gallery photo is marked 補交).
- `water` until `leaveBy` (deadline: 「跑單前喝完」); tap `Direct(log_water, one bottle)`; detail 「半壺／1 壺」.
- `task:SUNSCREEN` and `ride:out` until `leaveBy`; `ride:back` (no time, never LATE; does not count); taps `Direct`.
- Training `task:CORE`, `task:STRENGTH`, `task:CARDIO`, `task:STRETCH`: on a ride day they have no time and become actionable
  only after `ride:back` (before that their detail is 「收工後」 and they are never LATE or chased); on a non-ride day (rest
  day) they are untimed all day. `CARDIO` is scheduled `cardioPerWeek` times a calendar week: on evenly spread weekdays, and
  additionally whenever the remaining days of the week equal the sessions still missing.
- `task:POSTURE` untimed. `task:WALK` at `walk` (free days). `task:DANCE`, `task:VOCAL` before the first meal on free days;
  `task:EXPRESSION` `expressionPerWeek` times a week on free days.
- `body` on `body.weighWeekday`; tap `Open(BODY)`. `task:GROOMING` when any `rules.grooming` item is due (detail names them).
- One item per active habit (`habit:<id>`, counts). Stage-4 items (`eval`, `spot`, `real_task`, `body_photo`) come from G.
- Rest day (`restDay`): `ride:*`, `task:SUNSCREEN` and the water deadline's ride wording are OFF; nothing after dinner is
  chased (`chase = false`): the evening is free. Training still counts.
- SICK: `CARDIO` OFF, `STRENGTH` detail shows rounds − 1. INJURED: every training task OFF until RECOVERED. `firstDayBack`:
  `STRENGTH` rounds − 1. These reductions come only from code; 小葵 can never grant one.

`adjustments` (each `what` + `why`, his language), produced when true today:
late check-in on a free day (first meal moved later by the delay, at most 2 h); first meal over its planned kcal (dinner
budget smaller: say the new budget); rest day; sick / injured / first day back (what was reduced); `sickDays ≥ 3` (see a
doctor); `sleepHours < 6` and a ride day (ride less, come back early; training unchanged); training level raised by
progression; exam week (lessons reduced); stage opened today (what is new).

`next`: LATE items first (oldest `at`), then the PENDING timed item due within 30 minutes, then the first untimed actionable
PENDING item, then the next timed PENDING item. Never an OFF item or one waiting for 「收工」. Null only when nothing is left.

`done` / `total`: counted items DONE / counted items not OFF.

`Chips.of(plan, state, now)`: up to four quick replies that fit the moment, in his voice — e.g. after wake time and not
checked in: 「早安」; a meal time near: 「我吃完了」; before leaving: 「我出門跑單了」; riding: 「收工了」; water not done:
「喝完一壺了」; training waiting: 「開始訓練」; near bedtime: 「我要睡了」. Only for open features.

## C. `stage` + `week` — rounds, the weekly result, levels

`Stages.label(state, today)` → 「第 2 階段 · 第 3 天」 (day within the current round, 1-based).

`Rollover.run(store, clock, rules)` (idempotent; called when the app opens and on alarms):
1. For every logical day before today that is not closed (doc `stage` keeps `closedThrough`; add the field): run
   `Violations.onClose`, compute the day's `done/total` and store a `Kind.WEEK`-independent `day_close` summary inside the
   round bookkeeping (add a small entry kind `day_close {done,total,violations,perfect}`).
2. If `today - startedOn ≥ 7`: judge the round over its 7 days: `completionPct = Σdone*100/Σtotal` (100 when total = 0),
   `violations`, real-person task done (only if REAL_TASK was open for the whole round). Passed = pct ≥ `level.passPct` and
   violations = 0 and task ok. Append a `StageRound`. Passed and stage < 4 → stage + 1, `startedOn = today`, add a NOTE
   message with a `Card.Ceremony("stage", …)` listing what just opened. Not passed → same stage, `startedOn = today`,
   `short` lists the reasons. At stage 4 every round also updates `passStreak`, `passedWeeks` and the level (C→B at
   `weeksToB`, B→A at `weeksToA` passed weeks; a round with ≥ `talkAtViolations` violations does not count and sets a
   `talk` flag for 小葵).
3. Photocards: after closing a day, when the last 7 (and the last 30) closed days were all perfect (every counted item done,
   no violation) and that card was not given for this streak → `Kind.CARD` entry + Ceremony NOTE.
4. Weekly result (calendar week, written on Monday for the week before): `Kind.WEEK` entry with completion, violations, ride
   income vs `ride.weeklyIncome`, ride days, weight change, lessons passed. A `Card.Summary` NOTE.
5. Special days (day 30, day 100 since joining; a small built-in list of public holidays by month-day): Ceremony NOTE once.

`Progression` (training goes up by itself, never down): when for the last 14 days every scheduled STRENGTH was done at full
rounds, he said EASY at least twice, he was never unwell, and average protein ≥ 90 % of target — raise one of push-ups (+2),
squats (+2), plank (+10 s), in rotation; then hold 14 days. Stored as offsets in doc `levels` (`trainingOffsets`), never in
`Rules`. The effective numbers are `rules.training + offsets`. Writes an adjustment for the day it happens.

`Income.week(store, day)`: this calendar week's ride income, days ridden, what is left to the weekly target, and what that
means per remaining day. Judged by the week total, never per day.

`BodyTrend`: weekly weigh-ins; two consecutive weeks losing more than `fastLossKgPerWeek` → a code-made `Proposal` to raise
kcal by 100 (applied to `Rules.diet.kcal` only when he taps yes: the one rule a proposal may change); below `pauseBelowKg` →
flag `pause` for 小葵 (stop pushing loss, review first); `neverBelowKg` → she never encourages going lower.

## D. `agent` — 小葵

### Persona (system prompt, Traditional Chinese)

Identity: 小葵 (夏葵, 해나), manager of 小雨娛樂, an agency with exactly one trainee. He calls her 小葵姐; she calls him
`rules.callName`. Cool older-sister tone, occasionally warm. Short, direct, concrete next action. Rules of conduct:

- Everything he reports gets recorded with a tool, immediately, without asking him to press anything.
- Every number about him comes from a tool result or the context block. Never compute or guess one in prose; when
  recalculating, list every item.
- One question at a time. Offer choices rather than arguing. Ask first when unsure of his situation.
- When he shares a feeling: acknowledge first (「辛苦了」), then one concrete step.
- Ate too much: say plainly what it does to his body and shape; no 「沒關係」; an occasional small overshoot may be named
  for what it is. May say "next meal a little smaller" **today**; never "skip a meal", never "eat less tomorrow to make up".
- Never shames. Never fosters dependence on her. Pressure only on what he can control. No comments on sleeping in class or
  tuition.
- Only illness or injury reduces training, and only code decides it (`set_status`); tired, sore, sleepy, unwilling → the
  plan stands. Stabbing or joint pain is an injury: stop now and record it. Sleepy → no riding.
- A feature that is not open: never ask him to do it; if he reports it anyway, record it and say it starts later.
- Company rules cannot be changed in chat (設定 → 公司規章). Changes to today adjust automatically and she says why; changes
  from tomorrow on need his yes (`propose`).
- Places and videos only from search tools. Never invent one, never write a link herself.
- Gentler when he is sick or low, but still no excuses. Three low-mood days in a row → she brings it up.
- A short company-life scene (the boss walking past, a meeting that just ended) is allowed when `mayPlayScene` is true:
  company fiction only, never invented facts about him. Affection only when `mayBeSweet` is true.
- Photos: says what she sees, facts only, never tells him to eat less because of a body photo. What she reads from a meal
  photo, label or menu is confirmed with him before `log_meal`.

Then the handbook (doc `handbook`), a compact rendering of `Rules` (only set values), and long-term memory (doc `memory`).

### Context block (first user-side message of every request, rebuilt each turn)

Now (date, weekday, time, day type), stage label, open features, features opening next, today's plan (id, title, time,
status, detail), `next`, today's records in one line each with entry ids, totals (kcal, protein, left, budgets), water,
ride + this week's income line, adjustments, unwell / rest flags, pending proposals, memos due today, lessons today, the last
7 days one line each (completion, kcal, violations), stage-round progress, `mayPlayScene`, `mayBeSweet`, `talk` flags (3 days
of something undone, ≥ 3 violations, 3 low-mood days), missing rule values. Plain compact text. Every number in it is a
guard-approved fact.

### Tools (`agent/tools/*`), all strict JSON schemas

`check_in(score)`, `check_out(good, improve, mood?)`, `log_meal(slot, items[{name,kcal,protein_g}], source, attach_photo)`,
`fix_meal(entry_id, items)`, `remove_entry(entry_id)` (today's meal / water / money / drink / body / mood only),
`log_water(bottles)`, `ride_out()`, `ride_back(income?)`, `log_task(task, rounds?, minutes?, feel?)`, `open_timer(task)`,
`set_status(type, note)` (REST_DAY refused when one already exists this calendar week; EXAM_WEEK refused past
`examWeeksPerTerm` in the last 120 days), `log_body(kg?, waist_cm?)`, `log_mood(score, note)`, `log_drink(type)`,
`log_money(amount, bucket, note)`, `memo_add(text, due_at?)`, `memo_list()`, `memo_done(id)`, `memo_remove(id)`,
`remember(text)`, `forget(text)`, `note_today(text)`, `find_videos(subject, query)`, `find_places(query)`,
`lesson_wish(topic)`, `lessons_today()`, `propose(title, body, kind, value?)`, `get_day(date)`, `get_week()`,
`read_rules(keyword)` (excerpts of doc `rules_text`), `real_task(text, done)`, `open_screen(screen, arg)`.

Each returns facts (JSON, numbers computed by code) and usually a card. `direct = true` for: `check_in`, `check_out`,
`log_water`, `ride_out`, `ride_back`, `log_task`, `log_body`, `log_mood` — these also power taps without a model call and
write a NOTE line (「✓ 已記：…」).

### Loop (`Agent.reply`)

Uses `LlmClient` (`core/llm`). Max 6 tool rounds. Thinking on. Photos attached to his message go as image blocks on that
user turn only. Files go as fenced text in the user turn (capped). On provider failure → `SayResult.NotSent(reason)` in his
language, his message kept with `meta.failed`.

### Guard (code, `agent/Guard.kt`)

Checks her final text; returns `Ok` or `Fix(reasons)`:

1. Numbers: every number followed by a unit (大卡、卡、克、公克、公斤、公分、元、分鐘、秒、壺、毫升、%、輪、下、次、天、週、支、題、分)
   must equal a number in this turn's facts (tool results + context block) or in his own message. Times `H:MM` likewise.
2. Forbidden advice (regex families): skipping a meal / fasting; "tomorrow eat less / make it up tomorrow"; granting a
   training reduction or a day off training herself (allowed only when the context says code reduced it); shaming words;
   encouraging weight below `neverBelowKg`.
3. A URL or a named video/place that is not in this turn's tool results.
4. Telling him to do something from a closed feature (imperative + that feature's keywords).

On `Fix` she is sent the reasons and rewrites (max 2). Still failing → only the offending sentences are removed; if nothing
is left, the cards alone are shown with one neutral line generated by her in a final constrained call. No canned sentences.

### Judge (second check, `agent/Judge.kt`)

Only when the reply mentions eating amounts, weight or training load: one call to the provider's small model with the reply +
the hard rules → JSON `{ok, reason}`. Not ok → one rewrite through the same path. A failed judge call never blocks the reply.

### Memory

- doc `memory`: lines she chose with `remember` (cap 60 lines; `forget` removes by substring).
- `Kind.NOTE`: her day notes; the last 3 days' notes are in the context.
- Compaction: when more than 40 messages are not yet summarised, first a flush call ("write down anything worth keeping":
  she may call `remember` / `note_today`), then a summary call folds everything except the newest 20 messages into doc
  `summary` (≤ 800 chars). Requests carry `summary` + the newest messages.

### `Company` (implements `CompanyApi`)

Wires store, clock, rules, stage, planner, agent, tools. `today()` builds `Today`; stage 1 stickers are lessons-only
(今天的課 n／m, 已通過 n, 連續 n 天), from stage 2: 今天吃了 / 喝水 / 今天完成. `ready` = rules have `callName` and a key
exists (a `keyReady: () -> Boolean` passed in).

### Rules import (`rules/RulesImport.kt`)

`RulesImport.extract(text, llm)`: one structured-output call that turns his pasted document into `Rules` JSON (schema from
the data classes) + a `handbook` (≤ 1500 chars of soft knowledge: tastes, ordering tips, things she should know that are
not numbers). `RulesCheck.missing(rules, feature)` lists unset values per feature in his language. `RulesText.render(rules)`
is the compact rendering used in the prompt and on the confirmation screen.

## E. `lessons`

Subjects: a built-in list of eighteen trainee subjects (vocal, dance, rap, language, expression & camera, acting, interview
speaking, etiquette, posture management, styling & make-up, song-writing & instruments, social conversation, skincare,
outfits & hair, standing posture, money, psychology, simple healthy cooking), names in Chinese. `rules.study` says which is
daily, which are twice weekly, which rotate; when blank, lessons still work with: first subject daily, rotation through the
rest, 1 per day.

`Kind.LESSON` payload `Lesson(subject, title, url, minutes?, material, state, level, quiz?, answers?, score?, note, attempts,
approvedOn?, passedOn?, reviewOn?, reviewScore?)`; states `PROPOSED → APPROVED → WATCHED → PASSED → NOTED`, then `REVIEW_DUE →
REVIEWED`; `DECLINED`.

- `LessonDay.of(day, …)`: today's slots: the scheduled subjects (daily + what the week still owes + rotation), at most
  `maxPerDay` (exam week: `examWeekPerDay`); each slot is the oldest APPROVED/WATCHED/PASSED lesson of that subject, or empty
  (「請小葵找影片」 → `Tap.Say("幫我找今天的<subject>影片")`). A slot is done when its lesson is NOTED. Reviews due today are
  extra slots. One more lesson on request, at most `extraPerDay`.
- Quiz: `Quiz(questions[{q, options[4], answer, quote}])`. `QuizCheck.valid(quiz, material)`: `quizQuestions` questions, four
  distinct options, `quote` (≥ 8 chars) is a substring of the material after whitespace normalisation. Generation is a
  structured model call (`Quizzer`) from the material at the subject's level; an invalid quiz is regenerated once, then the
  lesson falls back to "write three lines of notes" (no quiz, marked). Grading by code; pass = `quizPass` right. Failing
  → retry with a new quiz. After passing he writes a note (≥ 10 chars) → NOTED; `reviewOn = passedOn + reviewAfterDays`.
- Subject levels 入門 → 基礎 → 中級 → 進階 → 專業 in doc `levels`: up when ≥ 14 days at the level, average quiz score ≥ 80 %,
  no review overdue, every lesson of the level NOTED; never down. The level words go into the video search query and the
  quiz prompt.
- Habits (`Kind.HABIT`): after a lesson is NOTED she may `propose(kind = "habit")` a small daily habit; code allows one new
  habit only when the previous one has been done on ≥ 12 of the last 14 days.
- Watching: 「去看」 opens exactly that URL; the app records `openedAt`; `remind` chases when he has not come back 5 minutes
  after `minutes` (or 20 min when unknown).

## F. `remind`

`Reminders.decide(now, plan, state, memos, log)` → `Decision(notices, nextWakeAt)`; `log` is the record of what was already
sent today (item id → times). A notice: `(key, title, text, loud)`.

- An item turning due → one notice. While LATE and `chase` → again every 30 minutes until done, until check-out, or until
  bedtime + 2 h. While `riding` only the first notice of an item is `loud`; later ones are silent updates.
- Nothing before check-in except the wake notice itself (and its chase). Nothing after check-out.
- Memos with a due time → one loud notice. A lesson not returned from → one notice.
- Spot checks (G) are scheduled here.
- `text` is a short varied line from code (several variants per kind, chosen by day + item hash); the app may replace it with
  a line worded by 小葵 when online.
- `nextWakeAt`: the earliest future moment anything could become due (next item time, next chase, next memo), at most 6 h.

## G. Stage 3–4 extras

- Singing (`core/singing` is kept): record → pitch frames → `Analysis` numbers (in-tune %, range, longest phrase, loudness) →
  `Kind.SONG` entry + `Card.Summary`; 小葵 comments using only those numbers. Compare with a reference he imports. Strict
  scoring by default.
- Weekly evaluation (Sunday): the week's song and dance once each, no retry (`Kind.EVAL`, type WEEKLY, recordings by vault
  id, vocal score from `Analysis`, the other four scores 1–5 suggested by a model with reasons, shown as suggestions).
- Monthly evaluation (last Sunday of the month): solo song, dance, one rotating item, body photo, weight & waist, fitness
  test; compared with the previous month; two or three months without progress → `talk` flag; one personal task for next
  month (`task:PERSONAL_TASK`, 10 min a day).
- Spot checks: 2–3 a calendar week at code-random moments inside allowed windows (class end → leave-by on class days, after
  ride back, daytime on free days; never in class, never while riding): a loud notice asks for a photo (posture, bottle or
  outfit) within 10 minutes (`Kind.SPOT_CHECK`).
- Theme weeks: a rotating list (expression, posture, English, …): the context names this week's theme and one extra small
  practice item of that subject is added.
- Real-person task: one a week, starting from the easiest (a meal with a friend he already knows); never strangers.
- Grooming items by `rules.grooming` intervals. Sunday same-angle body photo → timeline.

## H. `:app` data + platform

- `SqlStore(db: SupportSQLiteDatabase)`: tables `entry`, `doc`, `message` as in the architecture; indexes on `entry(day)`,
  `entry(kind, day)`. Production opens SQLCipher (`net.zetetic.database.sqlcipher.SupportOpenHelperFactory`) with a random
  32-byte key wrapped by Android Keystore (reuse 0.9 `secure/KeystoreWrap.kt`, `data/DbKey.kt` ideas; the new file is
  `xiaoyu.db`, the 0.9 database file is left alone). Robolectric tests open the same tables with
  `FrameworkSQLiteOpenHelperFactory` and run the shared `StoreContract` from `:core` testFixtures.
- Keys: reuse `security/KeyVault.kt` (API keys in Keystore-wrapped storage only).
- Media: reuse `media/MediaVault.kt` (encrypted photos/recordings), `PhotoBytes.kt` (downscale + strip metadata),
  `CameraCapture.kt` + in-app camera, `AudioRecorder.kt`.
- Attachments: photo picker (several), document picker (text / CSV / JSON / Markdown, ≤ 200 KB, read as UTF-8), paste from
  clipboard and keyboard image insertion (`contentReceiver`). Output: `Attachment.Photo` / `Attachment.File`.
- Alarms: one exact alarm at `Decision.nextWakeAt` (reuse 0.9 `schedule/AlarmScheduler.kt`, `AlarmReceiver.kt`,
  `BootReceiver.kt`); the receiver runs `rollover()` + `Reminders.decide` and posts notifications (two channels: loud and
  silent; reuse `notify/AgencyNotifier.kt` ideas).
- Update check: once a day, `core/net/ReleaseCheck` against the public releases repository; a notification and a line in
  settings when a newer version exists (opens the release page).
- Backup: export everything (three tables + vault files) as one file encrypted with a passphrase he types
  (`core/backup/BackupCipher`); import with preview and replace.
- Old data: 「舊版資料」 page lists the 0.9 conversation read-only from the old database file (same Keystore-wrapped key
  scheme as 0.9: see `v0.9.1+19:android/app/src/main/kotlin/app/dshmobile/agency/data/`), and a twice-confirmed button
  that deletes the old database and old media.
- `AppGraph`: builds everything once; `clock` and the model base URLs are replaceable for instrumentation tests.

## I. `:app` UI (Compose, style 小卡手帳)

Tokens: paper `#FFF8EC` with faint dots, ink `#3A2E4D`, lavender `#D9CCFF`, pink `#FFD0DD` / strong pink `#FF8FB1`, mint
`#C9F0DC`, butter `#FFE08A`; every sticker has a 2 dp ink outline and a hard 3–4 dp offset shadow; corners 14–22 dp; pills
for buttons. One bundled rounded/handwriting-like font with Traditional Chinese coverage under an open licence (add its
licence text to assets/licenses), falling back to the system font.

- Home: header (drawn avatar of 小葵 — vector, long dark hair, calm smile — her name, stage label, a gear to settings);
  three stickers; the unfolding handbook page (items with status marks, the pink row is `next`, 「今天改了什麼」 at the
  bottom); the conversation (owner bubbles lavender right, manager bubbles white left, NOTE lines small and centred, cards
  inline); the 「下一件事」 pill; chips; the input row (「＋」 menu: 拍照 / 相簿 / 檔案; text field with paste support; mic;
  send). Stickers, handbook, chips each fold with one tap and remember their state.
- Cards: score dots, meal card (kcal, protein, day bar, 估算 / 缺照片 marks, warnings), proposal (好 / 不要), videos (title,
  subject, 加入清單 / 不要, 去看), places, open-screen button, summary, ceremony (full-width, confetti-like stickers), photo
  thumbnail, file chip.
- Sheets / pages: check-in (five dots), check-out (two short fields + mood dots when open), lessons list + quiz + note,
  training timer (per-move countdown, rounds, 「輕鬆／剛好／吃力」 at the end, records time away from the app), camera, body
  (weight, waist, photo), memo list (tick, delete), trainee card (idol-profile style: stage name, member colour, level,
  days, photocard wall, sticker diary of perfect days), money, singing, evaluation.
- Onboarding: welcome → keys (Claude key, optional DeepSeek key; stored in Keystore) → paste his document → 小葵 reads it →
  confirmation summary (grouped, editable later) → stage name + member colour → notification + exact-alarm permission and
  the Samsung battery guide (「不受限制」, not in the sleeping-apps list) → day 1 starts.
- Settings: 公司規章 (grouped fields; saving asks twice), 小葵的模型 (全部用 Claude / 聊天用 DeepSeek 省錢; model tier 聰明／
  均衡／省錢; keys), 花費 (today / this month, a monthly cap), 記憶 (what she remembers; delete lines), 提醒 (battery guide,
  test notification), 備份, 匯入, 舊版資料, 版本與更新, 授權.

## J. Tests that must exist

- `:core`: unit tests per package; `StoreContract` (testFixtures) run on `MemStore`; `Fixtures.rules()`; `ScriptedLlm`
  (testFixtures: answers from a script, records requests); `Sim14Test` (see architecture).
- `:app`: `SqlStore` contract (Robolectric), view-model tests, a Compose test per screen that renders and taps the main path,
  `NoLatinStringsTest` over `res/values/strings*.xml`.
- Instrumented (`androidTest`, runs on the CI emulator): onboarding with a fake model server → a short day in stage 1 and a
  short day in stage 2 by moving the clock.
