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

- Branch with all code: `claude/dsh-mobile-cloud-handoff-vfhvdh` (feature work was done on a local `feat/agency` branch and
  merged onto it). Last pushed code commit: `37d2c91` (daily lessons and quizzes).
- Android app in `android/` (Kotlin + Compose). Pure-Kotlin rules/brain in the `:core` module.
- Green check: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon`.
  Last local result: core 547, app 269, all passing. Instrumented e2e (e01–e14) compile; e11–e14 never ran on an emulator.
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

## Still to build (in order)

1. Apply the owner's newest rule changes to existing code (details in his private document):
   - rest day = no delivery only (training still happens; may be a class day);
   - three meals, no fixed eating window (no "16:8"), lunch may go up to the single-meal cap, later meals adjust;
   - training block right after class (core + strength + a short cardio block 3×/week), latest departure time for delivery
     with a chase;
   - manager renamed 小葵 (he calls her 小葵姐; Taiwanese name 夏葵, Korean 해나);
   - the guard must allow same-day "next meal a bit smaller" but still ban skipping meals and next-day compensation;
   - only illness or injury may reduce training; progression is AUTOMATIC once truly stable (no confirmation);
   - first day after illness: reduced strength, full the next day.
2. Staged rollout: everything is built, but features switch on week by week. Week 1: lessons only (theory videos, quizzes,
   notes, "I want to learn X"). Week 2: + sign-in/out, meal photos, water, core + strength. Week 3: + morning stretch, vocal
   basics, cardio, posture, sunscreen, mood. Week 4+: + dance, weekly evaluation, surprise checks, theme weeks, earlier
   bedtime, first monthly evaluation. Next week opens only when the current week is stable. Features not yet open are not
   scheduled, chased or counted.
3. Camera inside the app (CameraX) + pick from gallery; images (meal, menu, body) may go to an image-capable model after
   downscale and EXIF strip; recordings never leave the phone except opt-in speech-to-text for lyrics.
4. Memo (「幫我記」「提醒我」), file import (CSV/XLSX/JSON + paste; bookkeeping, meals, weight, memos; preview, confirm, undo),
   encrypted backup export/import.
5. Per-subject level ladders (入門→基礎→中級→進階→專業, no level-down), learn-on-request (he names a topic → proposal →
   videos → lessons), learned lessons turn into small daily habits (one at a time, his yes).
6. Evaluations: weekly (Sunday), monthly (solo song, dance, one rotating item), five scores 1–5 (vocal, dance, visual,
   star quality, attitude), compare with last month, one personal task per month; ≥3 violations in a week → talk + no level
   progress that week; attendance photocards.
7. Daily freshness: manager plays short company-life scenes (no invented facts about him), surprise photo checks only outside
   class and delivery, theme weeks, special days. No daily challenges involving strangers.
8. Singing: on-phone pitch tracking (own YIN), pitch-curve chart, compare with a reference he imports, scoring defaults
   strict (not beginner).
9. 指揮室 pink restyle.
10. Then: whole-branch review (cross-feature), one fix wave, CI + emulator whole-day scenario, release dry run, give him the
    tag command and Chinese phone test steps.

## Usage budget

The owner is on a limited plan. Prefer small focused subagents, cheaper models for mechanical work, and one review at the
end of a large batch rather than after every step. Tell him plainly if something will not fit in the remaining usage.
