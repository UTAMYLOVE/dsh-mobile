# SDD ledger — plan: docs/superpowers/plans/2026-11-03-dsh-mobile-fogcity-ui-stage1.md

Spec: docs/superpowers/specs/2026-11-03-dsh-mobile-fogcity-ui-design.md (approved 2026-11-03). Execution: subagent-driven (boss approved plan 2026-11-03 "可以 開始做"). Branch claude/dsh-mobile-cloud-handoff-vfhvdh. Task briefs: .superpowers/sdd/stage1/task-N-brief.md (extracted verbatim from the plan).
- Ruling: insert follow-up 2 (dsh web layout at phone width → 0.1.3) before stage-1 Task 1 — Why: the boss uses 0.1.x daily now and the desktop layout squeezes the chat to one character per line; stage 1 is days away — Cost if wrong: stage 1 starts ~2 h later.
- Ruling: follow-up 2 cancelled at the boss's request ("不用出小版本 直接在大版本改") — the native stage-1 UI replaces the dsh web layout; agent stopped before any change. Device lesson carried into stage 1: every screen must be laid out for ~412 dp portrait width (no desktop two-column layouts, no vertical text), and no floating control may overlap another control.

## Pre-flight scan
Table and 26 findings: .superpowers/sdd/stage1/preflight.md (sonnet scan of plan vs spec vs repo). Rulings (applied to the plan text by the planner before Task 1):
- Ruling 1: PerfGateTest (instrumented) moves from T14 to T15; T14 keeps the governor/JankStats code and its unit tests — Why: T14 must not consume T15's helpers.
- Ruling 2: T4 sets `testTagsAsResourceId = true` on the shell root.
- Ruling 3: t21 first forces animator scale 1 (UiAutomation shell `settings put global animator_duration_scale 1`), asserts animating at FULL, then false after the toggle.
- Ruling 4: PerfGate pins EffectLevel FULL during measurement (test hook disables auto-degrade); gate = clamp(measured×1.5, 2%, 10%).
- Ruling 5: add `next` to the session model selection fields (SessionSummary/SessionIndex) so T8 can use `next ?: lastUsed`.
- Ruling 6: ComposerUi exposes `onAttachImage` and `onAttachFile`.
- Ruling 7: OverviewState/OverviewSource gain `heartbeatIntervalMin` and `nextHeartbeatAtMs` (from HeartbeatScheduler).
- Ruling 8+9: T14 owns effect wiring: app-scoped `EffectsController` with `StateFlow<EffectLevel>`; Settings writes it, FogShell reads it; T14 provides LocalBackdrop/LocalPageActive/LocalIdle in FogShell (FogShell added to T14 Files).
- Ruling 10: the optional SessionSummary fields become nullable, matching the text and test.
- Ruling 11: T13 adds tests for target selection, previous-version filtering and that the crash-notification rollback never targets an unverified version.
- Ruling 12: T9's failure test goes through the send path and asserts `prompt` is never called; add picker-cancel and image-rejected tests.
- Ruling 13: `PendingAttachment` is a plain class holding uri/name/mime/size; bytes are read at send time (no ByteArray in a data class).
- Ruling 14: the attachment remove ✕ uses ink #5E4445 at 70% alpha (palette).
- Ruling 15: T10's card takes `originOf: (sessionId) -> String?` (default null → shows 「子代理人」 for non-top-level ids); T11 wires it.
- Ruling 16: T6 extracts a shared `SessionTreeReducer`; T11 reuses it; T2's DshRemote Interfaces must expose `hostEvents` and `muxState` under exactly those names.
- Ruling 17: T4 extends FakeMux with a delayed upgrade and an injectable clock.
- Ruling 18: snapshot and `Reopened` reset `live` and `running` in T5 (add test).
- Ruling 19: T2's redelivery test uses grace > 0; the error-code sanitiser also covers mux `Failed` and `$events` errors.
- Ruling 20: the dock composer is disabled with a short hint while dsh is not Ready (T4 + T8 test).
- Ruling 21: T14's perf-summary test feeds frames explicitly.
- Ruling 22: T7 adds a 100 000-character reply test (cap stays 200 000).
- Ruling 23: T15 tests are independent or explicitly ordered with zero-padded names (t01..t23) under NAME_ASCENDING.
- Ruling 24: minors fixed by the planner (drop unused STEER, unique tag prefixes, T13 depends on T6 and lists MainOps.kt, naming, complete test list, interface type in ShellSession, pin OFL hashes, clarify Effects).
- Ruling 25: execution is strictly sequential (SDD never runs implementers in parallel); the plan's parallelism note is removed.
- Ruling 26: T15's grep targets `android.webkit.WebView` (Exporter's MimeTypeMap stays).
— Cost if wrong (all): rework inside the affected task, caught by its task review.
- Ruling: boss asked for more parallelism ("多用一點子代理跑快一點"). Wave A runs three implementers in separate local git worktrees on local-only branches (never pushed): lane 1 = T1→T2 (remote/), lane 2 = T3 (design system), lane 3 = T7 (Markdown) — disjoint files; the controller merges each lane into claude/dsh-mobile-cloud-handoff-vfhvdh after its task review, and only that branch is pushed. Later tasks stay sequential (shared MainActivity/Tags/strings/DshRemote) but each task's review overlaps the next task's implementation. Max 3 concurrent agents — Why: the 5-hour usage limit stalled all agents for ~2 h today — Cost if wrong: a merge conflict between lanes, resolved by the controller.
