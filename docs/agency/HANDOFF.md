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
  merged onto it). Last pushed code commit: `37d2c91` (daily lessons and quizzes). Batches A, B and C (below) are committed on
  `feat/agency` and NOT pushed yet.
- Android app in `android/` (Kotlin + Compose). Pure-Kotlin rules/brain in the `:core` module.
- Green check: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon`.
  Last local result (batch C): core 639, app 291, all passing (a few Robolectric screen tests can time out when the machine
  is busy and pass alone). Instrumented e2e (e01–e14) compile; none of the batch A–C screens has run on an emulator yet.
- Database: Room schema version 6 (batch C added no table: new record kinds are enum values stored as text, new settings are
  the plan sections `PRACTICE` and `LEVELS`).
- CI runs on push (GitHub Actions); the emulator e2e job is the integration test.

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
2. Whole-branch review (cross-feature), one fix wave, CI + emulator whole-day scenario (add the batch A–C screens), release
   dry run, give him the tag command and Chinese phone test steps.

## Usage budget

The owner is on a limited plan. Prefer small focused subagents, cheaper models for mechanical work, and one review at the
end of a large batch rather than after every step. Tell him plainly if something will not fit in the remaining usage.
