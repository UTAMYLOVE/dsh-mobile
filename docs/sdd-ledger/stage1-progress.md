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

## Execution
Wave A (base d143f12): lane-t1 T1 dispatched (opus, worktree /home/user/wt-t1); lane-t3 T3 dispatched (sonnet, /home/user/wt-t3); lane-t7 T7 dispatched (sonnet, /home/user/wt-t7). Lanes commit locally only; controller merges after review.
T1: implementer DONE_WITH_CONCERNS (lane-t1 d143f12..8d85b62, 643 unit tests); concern: DshClient.postJsonForText caps responses at 1 MiB → large session/list or session/page would fail
- Ruling: the 1 MiB cap is raised for DshRemote's calls in T2 (DshRemote gets its own response reader capped at 16 MiB; DshClient's cap for health/registrar stays) — Why: novels make session/page large; T2 already edits DshRemote — Cost if wrong: very long sessions fail to load until fixed.
T1: review dispatched (sonnet); T2 dispatched on lane-t1 in parallel (review overlaps next task per parallel ruling)
T7: implementer DONE_WITH_CONCERNS (lane-t7 d143f12..a6e508b, 643 unit); concerns: licence copyright kept verbatim (Robin Stocker, brief said Atlassian); soft breaks as newlines; square inline-code background; MarkdownView is a plain Column; rememberMarkdown throttle untested; links use body colour + underline
- Ruling: licence text stays verbatim from the 0.32.0 tag — Why: the upstream file is authoritative — Cost if wrong: none.
- Ruling: T8 must not put a whole long reply into one lazy item: it hosts MarkdownParser blocks as separate LazyColumn items (carry into the T8 dispatch) — Why: novels of 100k chars in one Column item would jank the chat list — Cost if wrong: scroll jank on long chapters.
T7: review dispatched (sonnet)
T3: implementer DONE_WITH_CONCERNS (lane-t3 d143f12..32a7d03, 649 unit); concerns: look unchecked on a screen; glass shows no blur until T14 provides LocalBackdrop; glass silently skips blur if RenderEffect throws; co-author trailer names the serving model (Sonnet) — accepted
- Ruling: a visual check of the design system is added to T4's review (Robolectric native-graphics screenshot of the shell at 412 dp) rather than waiting for T15 — Why: catching a wrong look early is cheap — Cost if wrong: one extra screenshot test.
T3: review dispatched (sonnet)
T1: review = spec ✅, quality Approved (sonnet), 0 Critical/Important
T1: complete (lane-t1 d143f12..8d85b62, review clean)
T1: minor (deferred): concurrent 401s each re-login (harmless); deeply nested JSON could StackOverflow in parse (loopback, capped); fileUploads/upload arg shape unverified live → T9/e2e must confirm
T7: review 1 = spec ✅, quality Needs fixes (sonnet) — Important: rememberMarkdown discards uninterruptible parses under fast streaming → long replies never update until stream end
- Ruling: Minors 1 (cap tables/lists at 500 rows/items), 2 (surrogate-safe truncation) and 4 (3–4 digit list markers) join round 1 — same file, and huge tables hit the lag the boss reported.
T7: fix round 1/5 dispatched (resume implementer)
T7: minor (deferred): single 200k-char code line untested
T3: review 1 = spec ❌ (panel padding top/sides swapped, heading gap 12 vs 14), quality Needs fixes (sonnet) — Important: per-frame allocations in ring/meter/background/glass draw paths; animated background lacks its own layer (invalidates ancestors); radial fades through grey; blurred backdrop not re-recorded when a panel moves
- Ruling: all Minors join round 1 (same files; lag is the owner's top complaint): CountUpText draw-time read, one CSS-matching blur helper, glow offset, Segmented PILL_MS spring, 48 dp touch targets + selectableGroup, lazy GraphicsLayer, invalidateSemantics, Material body/title/label styles on the system font (Chakra Petch only for numbers and English labels per brief).
T3: fix round 1/5 dispatched (resume implementer)
