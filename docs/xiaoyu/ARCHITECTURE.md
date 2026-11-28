# 小雨娛樂 1.0 — architecture (the rewrite)

This repository is PUBLIC. Nothing personal about the owner may appear in code, tests or docs: no body figures, targets,
timetable, food list, school, income figures. Every such value lives in `Rules` (see below), is blank by default, and is
filled in on the phone. Tests use invented data only (`Fixtures.rules()` in `:core` testFixtures).

## Why a rewrite

The previous app (0.9.x, still in git history at tag `v0.9.1+19`) failed for the owner: too many buttons, rigid flows, a
manager that was not as bright as an ordinary chat assistant, and a canned-sentence guard. 1.0 is:

- **Chat first.** Almost everything is done by telling the manager 小葵 in plain words. She acts through tools; code checks and
  computes. Buttons are only a fallback.
- **One home screen**: the conversation, one 「下一件事」 button, three small status stickers. Everything else folds away
  (tap to open, tap to close).
- **A normal agent loop**, not a router: full context + tools + thinking, like any good chat assistant.
- **Rules are still hard.** Numbers are always computed by code. The guard never swaps her words for a canned sentence: on a
  failed check she is asked to rewrite (see Guard).
- **Staged rollout** counted from the day he joined. A feature that is not open is never scheduled, chased, counted, or
  suggested by her; a feature that is open always has a place to do it.

## Hard rules (owner-set)

- API keys only in Android Keystore. Never in files, logs, prompts, commits. Never read `.env`.
- No telemetry. No chat upload. All data encrypted on the phone. Encrypted backup export/import.
- One network exit (`NetworkGate`), allowlist only: `api.deepseek.com`, `api.anthropic.com`, `api.github.com`.
- Photos go to a model only after downscale + metadata strip. Recordings never leave the phone (the one exception is the
  opt-in lyric check).
- UI strings: Traditional Chinese, no Latin letters (exceptions: level letters C/B/A, idol names, the Korean name 해나).
- Official/trusted libraries only. GPL/AGPL projects: ideas only.
- Gradle always `--no-daemon`. Never machine-wide `pkill`. No model/assistant name in any file as an author.

## Modules

- `android/core` — pure Kotlin/JVM (`app.agency.core.*`), no `android.*`. All rules, planning, the agent loop, tools.
- `android/app` — Android (`app.dshmobile.*`, applicationId stays `app.dshmobile` so the phone updates in place).

Kept from 0.9.x (infrastructure, already verified against the live APIs): `core/llm`, `core/net`, `core/metering`,
`core/backup`, `core/singing`, app `secure/` (Keystore wrap), `media/` (vault, photo cleaning, camera, recorder),
`log/ErrorLog`, alarm receivers, release check. Everything else is new.

### `:core` packages

| package | what |
|---|---|
| `base` | `Clock`, logical day (starts 06:00 local), JSON helpers |
| `store` | `Store` interface (entries, docs, messages), `MemStore`, typed entry kinds (`Kinds.kt`) |
| `rules` | `Rules` (every owner number; blank defaults), validation, the import-from-pasted-text extractor |
| `stage` | `Feature`, `Stages` (what opens when), weekly pass check, advance/repeat |
| `day` | `DayState` derived from a day's entries; nutrition; violations |
| `plan` | `DayPlanner` → `DayPlan` (items, next thing, today's adjustments = the "links") |
| `week` | weekly result, level, photocards, income vs target, progression of training |
| `lessons` | curriculum, lesson lifecycle, quiz validation + grading, subject levels |
| `agent` | persona prompt, context block, tool specs + executor, the loop, guard, judge, memory |
| `remind` | what notification is due now and when the next alarm is; chase; quiet while riding |
| `eval` | weekly / monthly evaluation records and scores (stage 4) |

### `:app` packages

| package | what |
|---|---|
| `data` | `SqlStore` (SQLCipher, three tables), settings, key vault access |
| `platform` | alarms, notifications, camera, recorder, speech, backup files, update check, old-data archive |
| `ui` | theme (小卡手帳), home, sheets, pages |
| `AppGraph` | manual wiring; a replaceable clock for tests |

## Data model (`store`)

Three tables, all JSON payloads, one SQLCipher database (`xiaoyu.db`; the 0.9 database file is left untouched as the old-chat
archive).

- `entry(id, day, at, kind, data)` — the append-mostly log of everything he did. `day` is the logical day.
- `doc(key, json)` — singletons: `rules`, `handbook` (soft knowledge extracted from his pasted document), `rules_text`
  (the pasted original), `profile`, `stage`, `memory`, `summary` (rolling conversation summary), `levels`, `settings`.
- `message(id, at, role, text, cards, meta)` — the conversation as shown.

Entry kinds are typed with kotlinx.serialization (`Kinds.kt`). Derived state is always recomputed from entries by pure
functions; nothing derived is stored (except caches the code can rebuild).

## Rules (`rules`)

`Rules` holds every number and time. All blank by default. Sources: onboarding (he pastes his private requirements document;
a model call extracts it into the `Rules` JSON schema plus a short `handbook` of soft knowledge; he confirms a summary), or
the settings page (company rules need two confirmations). Chat can never change `Rules`.

A feature whose rule values are missing is treated as "not set up": its plan items are omitted and the context tells 小葵
which values are missing so she can ask him to fill them in 設定.

## Stages (`stage`)

| stage | opens |
|---|---|
| 1 | lessons (videos, quiz, notes), "I want to learn X", memo, money notes |
| 2 | + check-in/out, three meals with photo, water, core, strength, delivery out/back, weekly weight |
| 3 | + morning stretch, vocal basics, cardio, wall posture, sunscreen, mood, brisk walk, singing analysis |
| 4 | + dance/vocal/expression practice, weekly evaluation, surprise checks, theme weeks, earlier bedtime, monthly
      evaluation, real-person task, grooming, Sunday body photo |

A stage lasts 7 logical days. On the first activity of day 8 the week is judged: completion ≥ 90 % of counted items, zero
violations, and (only once open) the real-person task done → next stage opens; otherwise the same stage repeats for another 7
days and 小葵 says what was short. The UI says 「第 N 階段 · 第 D 天」, never a calendar week. After stage 4 the same weekly
judgement drives the trainee level (C → B → A).

Voluntary reports for a feature that is not open yet are still recorded (he may tell her anything) but are not scheduled,
chased or counted.

## Day plan (`plan`)

`DayPlanner.plan(rules, stage, day, entries, now)` → `DayPlan`:

- `items`: today's things in order, each with status (`DONE`, `PENDING`, `LATE`, `MISSED`, `OFF`), a time or window, whether
  it counts, and what tapping it does (`Tap.Say(text)`, `Tap.Open(screen)`, `Tap.Direct(tool,args)`).
- `next`: the one thing for the 「下一件事」 button (first actionable pending item by time; never an item of a closed feature).
- `adjustments`: today's automatic changes with reasons (late wake → first meal later; big lunch → smaller dinner budget;
  rest day → no delivery items; sick → reduced strength; riding → single-sound reminders). Shown under 「今天改了什麼」.

Day types: class day / free day by `rules.classWeekdays`. Rest day (max one per week) removes delivery only. On a delivery
day training items are placed after 「收工」; on a non-delivery day any time.

## Agent (`agent`)

One loop per owner message (`Agent.reply`):

1. Build the request: system = persona + handbook + compact rules + long-term memory (stable, cacheable); then one context
   message (now, stage, open features, today's plan and records, last 7 days in one line each, due memos, pending proposals);
   then the rolling summary + the recent messages.
2. Call the model with tools and thinking on. Execute tool calls (code validates, computes, writes entries, returns facts +
   optional cards). Loop until she answers in text (max 6 rounds).
3. Guard (code): every number she states about him must appear in this turn's facts; no forbidden advice (skip a meal,
   compensate tomorrow, grant a training reduction herself, shaming, invented places/videos); nothing about closed features.
   If it fails she gets the reason and rewrites (max 2). Still failing → the offending sentences are dropped and the code's
   own card carries the numbers. Never a canned replacement sentence.
4. Judge (second check, small model): only when the reply touches food amounts, weight, or training load.
5. Store the reply + cards. Before the history is compacted, a memory flush lets her write what should be remembered.

Tools are gated by stage: a tool of a closed feature is not offered for scheduling, only for a voluntary record.

Proactive side (the "heartbeat"): alarms wake the app; `remind` decides in code whether anything is due. Nothing due → stay
silent. Something due → a notification worded by 小葵 when online (short model call) or by a varied code line when offline.

## UI

Style 「小卡手帳」: cream paper with faint dots, lavender / pink / mint stickers, dark-plum outlines with a hard offset shadow,
a rounded handwriting-like face. Home: big avatar header (drawn, never his photo), three stickers (tap 「今天完成」 to unfold
today's handbook page with 「今天改了什麼」), conversation with cards (score dots, meal card, proposal, video list, quiz, timer),
the 「下一件事」 pill, quick-reply chips that follow the moment, and the input. Stickers, handbook and chips each fold with one
tap.

The input is a full chat input: text, a mic (on-device speech only), and a 「＋」 menu with 拍照 (in-app camera), 相簿 (photo
picker, several at once), 檔案 (document picker: text, CSV, JSON, Markdown). A photo or file pasted from the clipboard or the
keyboard is attached the same way. Attachments show as chips above the input before sending. Photos are cleaned (downscaled,
metadata stripped) and kept encrypted in the vault; files are read to text on the phone (size-capped) and go with the message.

## Tests

- `:core` unit tests per package; `Sim14Test`: fourteen scripted days on `MemStore` with a scripted model and a moving clock,
  asserting no dead ends (an open feature always has a doable item; a closed one never appears; next is never stuck).
- `:app` Robolectric tests for `SqlStore`, view-models, screens; instrumented e2e on the CI emulator with the fake model.

## Wiring (`app.dshmobile.wire`)

The layer that joins the pure company to the platform and the screens. Built once by `DshMobileApp`; every member is lazy.

- `ProviderWiring`: the `Providers` bundle. `ALL_CLAUDE`: chat and small on Claude at the chosen tier. `CHAT_DEEPSEEK`: chat and small on
  DeepSeek, except a turn with photos goes to Claude when the DeepSeek chat model cannot see images. Search only with a DeepSeek key.
  Mode, tier and keys are read at call time; everything goes through the one `Transport`.
- `SpendMeter`: the usage sink and the spend guard. Doc `spend` holds per-day and per-month US-dollar totals; the monthly cap of the
  settings (kept in US dollars, shown in yuan at a fixed 32 to 1) refuses calls with `Fail.CAP`. `CallChecks` carries the 30-per-minute budget.
- `WiredCompany`: the one `CompanyApi` + `CompanyAdmin` the screens see; tells the wake handler after everything that changes the plan.
- `CompanyWake`: rollover, reminders, notifications, doc `reminder_log`, the next exact alarm. Runs on an alarm, a reboot, the app coming to
  the front and after every sentence or tap. A NOTE written by the rollover raises one quiet notification when no screen is in front.
- `wire/ui/*`: one adapter per door of `ui/UiInterfaces.kt`; `CameraActivity` is the in-app camera screen; `ActivityHost` starts pickers and
  permission questions through the screen in front; `LegacyKeys` carries 0.9's API keys over once.
