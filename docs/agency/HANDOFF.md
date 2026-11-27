# 小雨娛樂 — handoff for a new Claude Code session

This repository is PUBLIC. This file holds no personal data on purpose. The owner keeps a private design document
(「小雨娛樂設計文件-最新版」) with his numbers (body figures, timetable, meal times, targets) and will paste or attach it.
Ask him for it at the start; never commit its contents here.

## Talking to the owner

- He reads Traditional Chinese only, no English. Every reply, and every subagent's final reply, in short plain 繁體中文.
- Ask one question at a time; give options as 甲／乙／丙; recommend one.
- He is not technical: no jargon, no code in replies.

## Hard rules (owner-set, never break)

- API keys only in Android Keystore; never in config files, logs, prompts or commits. Never read `.env`.
- No telemetry, no chat or usage upload. All data encrypted on the phone (SQLCipher DB + MediaVault).
- Tests use invented data only; never his real data.
- Push only to branch `claude/dsh-mobile-cloud-handoff-vfhvdh`. No force push. No PR unless he asks.
- Only he creates release tags (he runs the tag command you give him after a release dry run).
- UI strings: Traditional Chinese, no Latin letters (exceptions: his idol names, level letters C/B/A; Hangul allowed for the
  manager's Korean name).
- GPL/AGPL projects: ideas only, never their code.
- Never run machine-wide `pkill`. Gradle always with `--no-daemon`.
- No model names anywhere except the commit trailer lines the system gives you.

## Where the work is

- Branch with all code: `claude/dsh-mobile-cloud-handoff-vfhvdh` (feature work is done on a local `feat/agency` branch and
  merged onto it with `integ-ci.sh`). Batches A, B and C and the final test-and-fix rounds are pushed.
- Android app in `android/` (Kotlin + Compose). Pure-Kotlin rules/brain in the `:core` module.
- Green check: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon`.
  Last local result (0.9.1): core and app unit tests all passing (see the 0.9.1 report for counts).
- Emulator e2e (CI job `e2e`, `AgencyE2eTest` e01–e15 + `ReminderE2eTest` e07–e09): every rollout week open
  (`RolloutSpec.ALL_OPEN`), notification and microphone permissions granted before each test, a failing test prints the
  company's last lines. e15 is the whole-day scenario across the features on a test clock (`TestOverrides.clock`,
  `E2eClock`); the same day runs on Robolectric (`ui/agency/WholeDayScreenTest`, the emulator's 320×640 screen). e13 runs on
  a fixed clock, waits for the company's reply before a tap and retries a tap that landed while the layout moved (`tapUntil`).
  On a fresh install 入社建檔's page opens by itself: `open()` leaves it with 返回 unless the test keeps it (e01, e06).
  Robolectric screen tests turn that off (`TestOverrides.autoOnboarding`, false in `AgencyTestKit.overrides`).
- 0.9.1 redesign (owner's mockups, `ui/kit` tokens): one palette in `kit/SakuraTheme.kt` (`Palette.Light`/`Palette.Dark`,
  bright pink + lilac + thin gold lines; his 代表色 replaces the pink and is darkened or lightened for WCAG AA), type scale
  `kit/Type.kt`, line icons `ui/art/Icons.kt` (package `ui.kit`). Home = one-line trainee card (`IdolLine`, the idol card
  opens over the screen: `IdolCardSheet`), the 今天 card (`ui/agency/today/TodayCard.kt`: week 1 the lesson row and one
  「… 第 N 週開放」 line; later 完成 N／M 項, next-up with one button, tiles that carry the old buttons' tags), the
  conversation (avatar and name only on the first line of a run), quick chips and the composer. Pickers and proposals stay
  above the 今天 card in `AgencyTags.PANELS` (at most 45% of the room, scrolling). 入社建檔 is its own page
  (`ui/agency/onboarding/OnboardingScreen.kt`, five cards, saved at once, 完成建檔 posts her one line); the chat no longer asks
  the items. 花費 moved from the war room to 設定 › 花費上限; the war room has a 這一週 card.
- Public repository: no example or test number matches his body (examples use 身高 186 體重 94, targets 91、84); the code
  ships no target weights (`CompanyRules.goalsKg` is empty until he sets 設定 › 規章); the old importer's default goals and the
  legacy fixtures use invented values. With no target set the card shows no goal line and a weigh-in says it is not set
  (a different line from 「都到了」). The strength program's prescribed reps and the safety floors (52 kg, 65 kg) are rules, not
  measurements, and stay. Still open: the daily intake and the timetable defaults (see the 0.9.1 report).
- Database: Room schema version 6.
- CI runs on push (GitHub Actions); the emulator e2e job gates the release (`release.yml`; 0.9.0 was tag `v0.9.0+18`,
  this round is 0.9.1, versionCode 19).
- Logic and safety fixes of 0.9.1: a sign-out after the day boundary (before 12:00, before today's sign-in) closes last
  night late; no sign-out at all is a breach; a rollout week opened mid-week never rewrites earlier days
  (`RolloutSpec.openOn`); on a delivery day the training block waits for 收工 at any hour; the alarm handler always arms the
  next alarm (a safety one in a `finally`) and the wording call no longer holds the brain lock; older backups are migrated
  through Room's exported schemas (packaged as assets) before a restore; real-person tasks start with a meal with a friend.
  The guard bans skipping the 21:00 portion, drink-only meals, training cuts without code's illness (`Evidence.reduceOk`),
  shaming and attachment phrases; the second-pass judge never fails open (no answer = template), has insult and
  training-cut classes, and also reads gentle turns, nudges on breach or over-quota days and the 月評 basis.

## Built so far (batches, all committed)

1. Encrypted DB + media vault, legacy import, providers (DeepSeek, Claude, OpenAI-compatible), network gate (single egress,
   allowlist), structured caller, output guard + second-pass judge, personas, memory.
2. Onboarding (photos, a-cappella recording, fitness numbers); old dsh UI and dsh removed.
3. Trainee card, company rules + day planner, the "Nerve" link engine (signals → day state → links; effects: adjust-today
   automatically / proposal needs his yes / notify / record), reminders with a 30-minute chase (one sound while riding).
4. Chat loop with tools, proposals, discussion mode (one question), memory UI, thinking kept for manager replies, offline
   "not sent / resend".
5. Meals (code-computed kcal, ban list, photo per meal), nearby food via DeepSeek search (real results only), water.
6. Guided core/strength/stretch timers, progression, morning score, bedtime review, weekly result.
7. Daily lessons: search-only videos he approves, code-checked quizzes, day-two review.

8. Batch A: the owner's newest rules (rest day, three meals, training after the run, 小葵, guard, automatic progression,
   staged rollout by week, delivery income, earlier bedtime, attendance cards, release notice).
9. Batch B: in-app camera (CameraX) and gallery, photos read by the image model after EXIF strip, memo, file import, encrypted
   backup.
10. Batch C:
   - Practice items of rollout weeks 3–4 (`core/engine/Practice.kt`, `PracticeDesk`): morning stretch, vocal basics, posture,
     sunscreen, dance + vocal practice on no-class days, camera expression twice a week, Sunday body photo timeline, weekly
     evaluation (song recording + dance video, one take), monthly evaluation (five scores: attitude and vocal by code, the
     looked-at ones suggested by the image model with a guarded basis, else his own; compare with last month; interview after
     two months without progress; the weakest item becomes the month's 10-minute personal task), surprise checks (2–3 a week,
     only after class before the run or no-class daytime, 10 minutes, hidden until they start), theme weeks, one-tap mood
     at sign-out (three low days → the manager asks, L30).
   - Per-subject levels 入門→專業 (`engine/Levels.kt`, plan section `LEVELS`): two weeks, ≥3 lessons, quiz average ≥80%,
     practicals, reviews; no level-down; weeks of the L26 interview do not count; search words and quiz difficulty follow the
     level; card shows levels; ceremony screen and level card (L32).
   - 學了就用 habits (`engine/Habits.kt`, L33): one at a time, offered after a pass only when the last habit was steady two
     weeks, added on his yes.
   - Daily company scene and special days (`engine/Scenes.kt`), code lines only, at the first alarm from the wake time.
   - Singing (`core/singing/`): own YIN, facts, metadata-free PNG chart, UltraStar / MIDI / demo-take references,
     octave-free scoring (一般 35/100 cents, 嚴格 20/50; timing 100/250 ms; never 入門 by default), a-cappella key shift; the
     teacher's comment must use code's numbers (output guard) and never judge the sound; diction via DeepSeek speech to text
     (`POST /v1/tools/asr`, WAV ≤120 s) only with lyrics and his one-time consent, through the gate, capped and metered.
   - 指揮室 restyled as a pink backstage planner (`ui/warroom/WarRoomScreen.kt`, `engine/RoleActivity.kt`).

## Still to build (in order)

1. Not done in batch C: the manager's scenes are code templates (no model wording yet, so the second-pass judge is not
   used for them); monthly evaluation's dance score has no model look at the video beyond two still frames; the singing
   screen draws the PNG without note labels; no in-app editor for the reference melody; the weekly/monthly hand-ins and the
   singing screen never ran on a real phone (MediaCodec decode, camera video).
2. Done: CI + emulator whole-day scenario, release dry run (unsigned APK), the whole-branch logic and safety reviews and
   their fixes (0.9.1). Left: he runs the tag command from the 0.9.1 report once CI is green, then tests on his phone.
3. The screens the mockups restyle only lightly so far: 理論課 (hero card, stepper), 訓練計時 (ring), settings sub-pages;
   they use the new tokens through the kit. The intake and timetable defaults still match his plan (needs his choice of
   neutral examples and a broad test update).

## Usage budget

The owner is on a limited plan. Prefer small focused subagents, cheaper models for mechanical work, and one review at the
end of a large batch rather than after every step. Tell him plainly if something will not fit in the remaining usage.
