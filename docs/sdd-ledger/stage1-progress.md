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
T7: fix round 1 implementer DONE (ed82360): conflated parse publishes while streaming, 500-row/item caps, surrogate-safe cut, marker width; 649 unit; RED weak (compile-only); re-review dispatched (sonnet)
T7: fix round 1/5 (4 addressed, 0 open; lane-t7 a6e508b..ed82360)
T7: complete (lane-t7 d143f12..ed82360, review clean after 1 fix round) — merge into the branch batched with wave A to save CI minutes
T7: minor (deferred): capped ordered list numbers the remainder item ("501. …還有 N 項"); short non-streaming text parsed twice
- Ruling: new Task 16 (background: finish work, then stop) added to the plan from the boss's decision "做完再停就好" (spec §5.11); runs after T2 is merged, on lane-t7's worktree (files disjoint from the UI tasks) — Cost if wrong: none beyond the task.
T3: fix round 1 implementer DONE_WITH_CONCERNS (e974277): caches + counters, background layer, white fade, position-change invalidation, padding 16/18/18 gap 14, all minors; 655 unit; meter gradient spans full bar (deviation); re-review dispatched (sonnet)
T3: fix round 1/5 (5 Important + 8 Minor addressed, 0 open; lane-t3 32a7d03..e974277)
T3: complete (lane-t3 d143f12..e974277, review clean after 1 fix round)
T3: minor (deferred → T14 perf review): CountUpText measures text every frame during the 950 ms count-up; FogDebug counters (ConcurrentHashMap lookup) in production draw paths; onGloballyPositioned may not fire on graphicsLayer-only moves (panel rise) — brief blur lag; Meter gradient spans the full bar (fill <100% ends lighter than mockup; fix via unit shader + setLocalMatrix); CountUpText ignores max constraints and defaults colour to Sakura.Text
Wave A merge: lane-t3 and lane-t7 merged into the branch (T1/T2 lane merges after T2)
T2: implementer DONE_WITH_CONCERNS (lane-t1 8d85b62..62ac66c, 669 unit); deviations: MuxConnection beforeConnect=ensureLoggedIn, two T1 test lookups filtered to POST, Failed.message safe code only, one test backoff 300 ms; 16 MiB bounded reader added; concerns: >16 MiB session/list fails with response-too-large; DshRemote without mux causes harmless 404 retries in tests; no client ping
- Ruling: lane-t1 (T1+T2) merged now and T4 + T16 dispatched while T2's review runs (parallel ruling); a T2 fix round, if any, lands on the branch — Cost if wrong: T4/T16 rebase over a T2 fix.
T2: review dispatched (opus — concurrency and reconnect semantics)
T2: minor (deferred): no client-side ping on the mux socket (add OkHttp pingInterval if T15 shows hung-socket stalls)
T4 dispatched (opus, wt-t3 lane-t4 from bb2ba1b); T16 dispatched (sonnet, wt-t7 lane-t16 from bb2ba1b)
T2: review = spec ✅, quality Approved (opus), 0 Critical/Important
T2: complete (lane-t1 8d85b62..62ac66c, review clean)
- Ruling: T2 Minors 1–4 and 6 get a small hardening round on lane-t2fix (remote/ only): clear clientId when mux leaves OPEN; close the socket when the scope is cancelled without close(); close() race after open(); reset failure count only after the socket stayed open longer than the last backoff; tests for not-connected timeout and chunked >16 MiB bodies — Why: reconnect robustness is Review Focus 1 and these are cheap now — Cost if wrong: one extra small round.
- Ruling (carry into T5/T6/T11 dispatches): collectors of control()/follow() must not suspend long inside emit (UNLIMITED buffers); hostEvents has no replay → consumers refetch their baseline on every muxState → OPEN / Reopened.
T16: implementer DONE_WITH_CONCERNS (lane-t16 bb2ba1b..adadc0a, 730 unit); no RED shown; onTaskRemoved explicit no-op; re-baseline could be skipped if RECONNECTING→OPEN conflates; review dispatched (sonnet)
T16: review 1 = spec ❌ (10-minute rule after done not honoured in two paths), quality Needs fixes (sonnet) — Important: busy can stick forever when $events reopens while mux stays OPEN (no re-baseline/watchdog); crash restart in background leaves an idle service resident
- Ruling: Minors 1 (restart 10-minute grace after done), 2 (no re-attach for an unchanged RunningDsh), 3 (document START_NOT_STICKY loss), 4 (no "done" notice after Stop), 5 (surrogate-safe clip + notification content asserts), 6 (non-vacuous swipe/guard tests) join round 1 — same files, and leaks cost the owner money.
T16: fix round 1/5 dispatched (resume implementer)
T2: hardening round implementer DONE (lane-t2fix d5934e9..8582f9a; 723 unit; 4 new tests RED→GREEN); re-review dispatched (sonnet)
T2: hardening re-review (sonnet) = all 5 addressed, 0 new Critical/Important
T2: hardening merged (lane-t2fix 36f606b..8582f9a)
T2: minor (deferred): conflated OPEN→RECONNECTING→OPEN could keep a stale clientId briefly (backoff ≥ 250 ms makes it negligible); lateinit in closeWhileTheUpgradeIsStartingCancelsTheNewSocket could flake in theory; first retry after a short-lived socket waits backoffMs[1]
- Ruling: T5 starts now on lane-t5 (wt-t1) in parallel with T4: it implements chat/ (state, reducer, store) and DshRemote's ChatRemote; the one-line ShellSession change (ShellRemote : ChatRemote) is deferred to the merge after T4 lands — Why: T5's substance doesn't depend on T4 — Cost if wrong: a small merge fix-up.
T16: fix round 1 implementer DONE_WITH_CONCERNS (996aaf0; 738 unit; 9 RED→GREEN); touches ui/UiHolder.kt (isForeground, onWorkEnded) — overlaps T4 lane; re-review dispatched (sonnet)
T16: fix round 1/5 (2 Important + 6 Minor addressed, 0 open; lane-t16 adadc0a..996aaf0)
T16: complete (lane-t16 bb2ba1b..996aaf0, review clean after 1 fix round) — merged
T16: minor (deferred): redundant baseline at startup/reopen (cancelled by collectLatest); a failed Stop followed by natural completion suppresses the done notice
T16 merge: WorkTrackerTest.aSubagentRunningKeepsItBusyAfterTheLeadIsIdle flaky on the merged head (1/3) — merge not pushed; deflake dispatched to the T16 implementer on lane-t16fix (296f3e9)
T5: implementer DONE_WITH_CONCERNS (lane-t5 c9e6212..168e3e7, 751 unit); deviations: internal approvals map, reduceContext param, firstSeq = min seq, session/page assumed snapshot-shaped (unverified), store ignores StreamSignal.Failed (retry left to shell); ShellRemote : ChatRemote change pending T4 merge; review dispatched (sonnet)
T5: review 1 = spec ✅, quality Needs fixes (sonnet) — Important: failed/ended follow leaves the chat silently dead (no error, no retry); replayed durable events regress approval cards and running
- Ruling: Minors 3 (loadOlder reports unexpected-answer), 4 (remove dead quadratic assistant-stream branch), 5 (pending bubble 60 s timeout → failed-send with retry) join round 1.
T5: fix round 1/5 dispatched (resume implementer)
T5: minor (deferred): cross-page tool pairs stay RUNNING if their result is in an unloaded page; O(n) list rebuild per event (bounded by maxMessages); close() cancels publishJob off the reduce context (benign)
T4: implementer DONE_WITH_CONCERNS (lane-t4 bb2ba1b..688ed19, 684 unit; 412 dp layout test proven by deliberate breaks); deviations: child scope per ShellSession; LocalPagePadding; extra tags DOCK/ATTACHMENT_CHIP/ATTACHMENT_REMOVE (T9 must reuse); after 60 s remote-open timeout stays on Error until Retry; review dispatched (opus) incl. a Robolectric native-graphics screenshot look (earlier ruling)
T5: fix round 1 implementer DONE (fcbb8e7..3343dd3; 759 unit; 8 new RED→GREEN; extra retrySend/dismissSend); re-review dispatched (sonnet)
T5: fix round 1/5 (5 addressed, 1 new conditional Important — 60 s timeout also fails healthy queued bubbles during long turns; commits 168e3e7..3343dd3)
- Ruling: the pending-bubble timer pauses while state.running and starts/restarts when running becomes false; idle sends unchanged.
T5: fix round 2/5 dispatched (resume implementer)
T5: minor (deferred): retrySend after a merely slow dsh can show the message twice (inherent)
T16 merge flake: cause = test race (events before first baseline overwritten by the fake's stale list); fixed on lane-t16fix 5ee1b0b (20/20 green). Full suite then showed MuxConnectionTest.closeWhileTheUpgradeIsStartingCancelsTheNewSocket flake (lateinit race predicted by the T2 re-review) — dispatched to the T2 implementer on lane-t16fix
T4: review 1 = spec ✅, quality Needs fixes (opus, with 412 dp native-graphics screenshots in scratchpad/t4-shots) — Important: mux reconnect loop leaks after dsh stops with no screen started; page content runs under the transparent header; page text shows sharp through the dock; disabled send glow clipped square
- Ruling: Minors 5 (pill offset at layout), 6 (keyboard-driven page padding recomposition), 7 (block tab switch while settings busy; keep pending fields outside the tab), 8 (hide embedded settings title) join round 1.
T4: fix round 1/5 dispatched (resume implementer)
T4: minor (deferred): settings page still Material-styled (stage-1 T13 restyles it); page-switch sweep line deferred to T14; Error persists after the 60 s remote-open timeout until Retry; FogShellLayoutTest uses a stand-in SETTINGS page; captureToImage() hangs under NATIVE graphics (use decorView.draw)
T5: fix round 2/5 (1 addressed, 0 open; commits 3343dd3..31eaad2; re-review haiku)
T5: complete (lane-t5 c9e6212..01a58a3, review clean after 2 fix rounds) — merged; ShellRemote : ChatRemote line pending the T4 merge
T5: minor (deferred): reopened() sets running=false so a long follow outage (>60 s) during a running turn can still fail a queued bubble; an idle-sent bubble unrecorded when another turn starts waits out that turn
- Ruling: pushed the local merges (T16, T5, WorkTracker flake fix) before the MuxConnectionTest flake fix lands — Why: CI coverage of the merged work and the flake is rare (load-only); a CI failure on that one test is re-run, not investigated — Cost if wrong: one red CI run.
Flake fixes complete: MuxConnectionTest closeWhileTheUpgradeIsStarting deterministic (aae3ad4, 20/20) — merged
T4: fix round 1/5 (4 Important + 4 Minor addressed, 0 open; lane-t4 688ed19..686c191; re-review sonnet)
T4: complete (lane-t4 bb2ba1b..686c191, review clean after 1 fix round) — merged; conflict resolved (ShellRemote : ChatRemote, duplicate DshRemote import); full unit suite + assembleDebug + assembleDebugAndroidTest green on the merge
T4: minor (deferred): a conflated fast stop/restart with no screen may leave the old session until next attach; PageTop 8 dp < TopFade 12 dp (first row under a faint fade); settings guard also blocks during app-scope jobs; nav.openSession/back skip canLeave
T6 dispatched (sonnet, wt-t1 lane-t6 from de1c788); T14 dispatched (sonnet, wt-t3 lane-t14 from de1c788; folds in T3 perf minors; adds buildSettingsEnv onEffectLevel ahead of T13)
T6: implementer DONE_WITH_CONCERNS (lane-t6 de1c788..86d98f8, 784 unit); extras: IndexRemote.eventsClientId, tags SESSION_GROUP/SESSION_CLOSE/RENAME_CANCEL/DELETE_CANCEL; inline menus (no Dialog windows); archived/replace frame shapes assumed; review dispatched (sonnet)
T6: review = spec ✅, quality Approved (sonnet), 0 Critical; reviewer's non-blocking "Important": status for an unknown session id dropped until next baseline; baseline-sync logic duplicated with WorkTracker
- Ruling: debounced SessionTree.refresh() on a status for an unknown id, search debounce + stale-result guard (collectLatest), and midnight regrouping in Asia/Taipei are carried into the T8 dispatch (T8 wires the list into the chat page). A shared BaselineSync helper for SessionTree/WorkTracker is deferred to the final review triage.
T6: complete (lane-t6 de1c788..86d98f8, review clean) — merged
T8 dispatched (opus, wt-t1 lane-t8 from 41393f1; folds in T6 follow-ups + T5/T7 rulings); T10 dispatched (sonnet, wt-t7 lane-t10 from 41393f1)
T10: implementer DONE (lane-t10 41393f1..eeb3f73, 805 unit; renders in scratchpad/t10-shots); deviations: promptOrigin isTopLevel param, Kotlin constant strings, 60% #FEDFE1 base under card gradient; TalkBack can still focus the dock under the scrim; review dispatched (sonnet)
T10: review 1 = spec ✅, quality Approved-with-follow-up (sonnet) — Important: sheet not modal/announced for TalkBack (dsh-stuck anti-goal for accessibility users)
- Ruling: TalkBack fix + theme error colour (not hard-coded) + 「連線中…」 line while waiting on a reconnecting mux go into fix round 1. The error red #B3261E stays (plan Global Constraints allow it as the only non-palette colour, for error text) — listed for the boss in the final rulings.
T10: fix round 1/5 dispatched (resume implementer)
T10: fix round 1/5 (1 Important + 2 Minor addressed, 0 open; eeb3f73..9a1f896; re-review haiku)
T10: complete (lane-t10 41393f1..9a1f896, review clean after 1 fix round) — merged
T10: minor (deferred): scrim focusable without label (hideFromAccessibility); whole card is a live region (narrow it if it re-announces on device); connecting derivation untested; import order nits
T11: implementer DONE (lane-t11 b70babf..0189e17, 826 unit); extra failed-stop param; 停止中 has no timeout; finished rows uncapped; label formula doubles 子代理人 for unlabelled children; review dispatched (sonnet)
T11: review = spec ✅, quality Approved (sonnet), 0 Critical/Important
- Ruling: Minor 1 (stop launched on rememberCoroutineScope is cancelled by leaving the tab) and the doubled 「子代理人」 label get a tiny round before merge; no timeout on 停止中 (reviewer: a timeout would mislead); finished rows uncapped for the simple version (stage 3 replaces the page).
T11: minor (deferred): no "nothing running" line when only finished rows exist; error line not a live region; Column+verticalScroll composes every finished row
T11: fix round 1/5 (2 addressed, 0 open; 0189e17..4806c40; re-review haiku)
T11: complete (lane-t11 b70babf..4806c40, review clean) — merged
T12: implementer DONE_WITH_CONCERNS (lane-t12 975e345..53814fa, 850 unit); no RED captured; unverifiedDshVersion stubbed until T13; month shows 2 decimals; heartbeat countdown can sit at 0 (WorkManager eligibility); review dispatched (sonnet)
T12: review = spec ✅, quality Approved (sonnet), 0 Critical/Important
T12: complete (lane-t12 975e345..53814fa, review clean) — merged
- Ruling: T12 Minor 1 (overview collector keeps ticking while the app is backgrounded) folds into T13's dispatch (collectAsStateWithLifecycle for the overview and settings collectors); Minor 2 (ReminderLog revision update outside lock, uncapped field lengths) and Minor 3 (EmptyPage flash) go to the final review triage.
T12: minor (deferred): ReminderLog revision/field caps; EmptyPage flash on first frame; old store mid-day split mismatch (per brief); month 2 decimals (disclosed)
T14: implementer DONE_WITH_CONCERNS (lane-t14 de1c788..2f36d93, 785 unit on its base); JankStats not exercised in Robolectric (frames fed via governorForTest); onEffectLevel added to buildSettingsEnv/SettingsEnv; LocalEffects provided in MainActivity's FogTheme; blur layers released off-screen (≤4 of 30); review dispatched (opus — perf-critical)
T14: review = spec ✅, quality Approved (opus), 0 Critical/Important; merge note: FogShell conflicts with T10's modal `hidden` semantics (must be kept)
- Ruling: Minors 1 (governor steps from max(cap, user floor), no no-op logs), 2 (observe effect locals unconditionally), 3 (CountUpText per-glyph cache), 6 (derivedStateOf for started), 7 (30-frame warm-up after start), 10 (toml back to CRLF, KDoc) plus merging main into lane-t14 (FogShell: keep T10 `hidden` in both places) go into one fix round — Why: lag is the owner's top complaint and each is small — Cost if wrong: one extra round.
- Ruling: page-switch sweep line (T4 deferral that no task owns) → T15. Blur cap: chat bubbles are glass fill without backdrop blur (blur only for panels + dock) — decided for T8 review/T15 PerfGate to enforce.
T14: fix round 1/5 dispatched (resume implementer a22b3ca67cf270533)
T14: minor (deferred): boundsInWindow per scrolled frame; RenderEffect re-alloc at viewport edge; reduced-motion-on-resume untested; MainActivity FogTheme wiring mirrored in test
T8: implementer DONE_WITH_CONCERNS (lane-t8 41393f1..a14fff7, 823 unit; shots scratchpad/t8-shots); review dispatched (opus)
T8: review 1 = spec ✅ except bubble-blur ruling, quality Needs fixes (opus) — Important: AI bubbles would backdrop-blur once T14's LocalBackdrop lands; scroll-follow gaps (send while scrolled up, no ↓ pill, no tests)
- Ruling: Minors M1–M7 (deep link vs canLeave, double parse, hard-coded strings, <48 dp targets, user-piece gradient, pending text colour, stale picked model) and the T5 live-reply blink on early `end` join round 1, together with merging main (T10/T11/T12) into lane-t8.
- Ruling: SettingsJobsTest "boom" leak (pre-existing T4) → T13 dispatch (it owns settings); 500-row table as one lazy item → T15 PerfGate; TalkBack on links → T15 emulator check.
T8: fix round 1/5 dispatched (resume implementer aa3af8252314a6d8c)
T14: fix round 1/5 implementer DONE (2f36d93..c16265a incl. merge of main 5a2037c; 883 unit, assembles green); re-review dispatched (sonnet)
T14: fix round 1/5 (7 Minors addressed incl. merge of main, 0 open; 2f36d93..c16265a; re-review sonnet Approved)
T14: complete (lane-t14 de1c788..c16265a, review clean after 1 fix round) — merged as 0366641 (code tree identical to c16265a, where the full unit suite 883/0 + assembleDebug + assembleDebugAndroidTest ran)
T14: minor (deferred): no direct test for locals observed off-screen, CountUpText draw alignment or unexpected glyphs; per-glyph drawing drops kerning
T13 dispatched (sonnet, wt-t3 lane-t13 from 0a657d9; carries effectsController.level read + immediate onEffectLevel, collectAsStateWithLifecycle for overview/settings, SettingsJobsTest boom leak fix)
T13: implementer DONE_WITH_CONCERNS (lane-t13 0a657d9..d04e01c, 900 unit); SettingsEnv.effectLevel StateFlow instead of uiPrefs; MainActivityTest rollback literals moved to 0.2.1-rc.5/0.2.2; OverviewSource +context, flowOn(IO); no screenshots; review dispatched (sonnet)
T13: review = spec ✅, quality Approved (sonnet), 0 Critical/Important
T13: complete (lane-t13 0a657d9..d04e01c, review clean) — merged (code tree identical to d04e01c: 900 unit + both assembles green there)
T13: minor (deferred): clearer message when current() throws during update; tapping the selected effect cell resets the governor cap (add no-op guard); OverviewSource IO context untested; lead-model radios show raw ids; no 412 dp settings screenshot (T15)
T8: fix round 1/5 implementer DONE (a14fff7..093b21c incl. merges of main a5ccff2 and 25e017f; 952 unit, assembles green); re-review dispatched (sonnet)
- Ruling: T9 starts from lane-t8 HEAD 093b21c (wt-t7, lane-t9) while T8's scoped re-review runs — Why: T9 builds on T8's composer and the re-review is small; waiting costs ~10 min of idle — Cost if wrong: if T8 needs a round 2, lane-t9 merges lane-t8 again.
T9 dispatched (sonnet, wt-t7 lane-t9 from 093b21c); fileUploads/upload shape to be confirmed against the installed dsh packages (scratchpad/dsh-scratch/node_modules, read-only): remote fallback takes (agentId, {data: base64, name?}) → {receiptId, file}
T8: fix round 1/5 (2 Important + 7 Minor + T5 follow-up addressed, 0 open; a14fff7..093b21c incl. merges of main; re-review sonnet Approved)
T8: complete (lane-t8 41393f1..093b21c, review clean after 1 fix round) — merged (code tree identical to 093b21c: 952 unit + both assembles green there)
T8: minor (deferred): releaseHeld is a second state update (one-frame overlap possible — fold into the reduce); page prepend drops a held reply (blink, bounded); no tests for page/reconnect drop, M4 targets, M7 chip reset; pill misses scroll within newest row; pill floats over list bottom; 500-row table one lazy item (T15); TalkBack on links (T15)
T9: implementer DONE_WITH_CONCERNS (lane-t9 093b21c..478d5f2, 969 unit); upload + prompt-part shapes confirmed against installed dsh packages (no change); PickMultipleVisualMedia; picked attachments in Activity fields (lost on recreate); file read twice (size count + send); review dispatched (sonnet)
CI: e2e red since the native shell (runs 73, 77, 80): t01 hangs ~270 s, then the emulator disappears ("device not found"); job logs carry no stack trace and artifacts can't be fetched from this session.
- Ruling: added a CI-only diagnostics step (on failure: print failing test bodies + logcat crash/ANR/LMK/app lines, secrets filtered) — Why: T15 needs the cause; the e2e rewrite is T15's job — Cost if wrong: a few extra log lines.
- Ruling: push ledger-only commits less often — each push cancels the running CI (concurrency cancel-in-progress), so e2e rarely finished.
T9: review = spec ✅, quality Approved (sonnet); reviewer "Important" (non-blocking): ~100–130 MB transient peak for a 20 MB file (double copy + base64), OOM uncaught on the send path; 9×5 MB images retained as base64 for retry
- Ruling: fix round 1 = exact-size single read, no raw bytes kept after encoding, OutOfMemoryError → composer error (nothing sent), friendly upload-failure reason instead of the class name, STOP shown while running with empty text even with chips — Why: low-end phones and the owner reads the errors — Cost if wrong: one small round. Streaming upload deferred (stage 2).
T9: fix round 1/5 dispatched (resume implementer)
T9: minor (deferred): send coroutine captures the Activity (recreate mid-upload, rare due to configChanges); pick-time full read doubles cloud downloads (by design); text-only refusal shown twice (dock + chat); retry retains base64 parts
T9: fix round 1/5 (memory + OOM + friendly reasons + STOP with chips; 478d5f2..1317dc3; re-review sonnet Approved)
T9: complete (lane-t9 093b21c..1317dc3, review clean after 1 fix round) — merged (code = 1317dc3 + ci.yml diagnostics; 974 unit there; assembleDebugAndroidTest re-run in T15)
T9: minor (deferred): DshRemote.upload encode change gives no memory benefit (KDoc overstates — correct it); no short-read/content test for read loop; grown-file array growth briefly doubles
T15 dispatched (opus, wt-t1 lane-t15 from 0883c19); Step 5 (CI green) runs via controller merge+push and relayed CI results (no emulator in this container); owns: CI e2e hang hypotheses (idling vs infinite animations, stale t01 signal, RenderEffect on swiftshader, memory), page-switch sweep line, TalkBack link check, 500-row table note
T15: implementer DONE_WITH_CONCERNS (lane-t15 0883c19..1294352, 979 unit, both assembles; e2e not yet run). Cause of CI e2e death (run 84 logcat): emulator dies ~0.2 s after the native shell's first frame draws — software renderer. Fixes: e2e setup at SAVER (t21/PerfGate switch to FULL), emulator -gpu swangle_indirect; createEmptyComposeRule's 60 s cap replaced by own 20-min rule; PerfGate launches via startActivity; e2e timeout 90 min; sweep line white/pink per Global Constraints (not aqua)
- Ruling: merge lane-t15 before its task review and push (CI e2e is the only place it can run; ~60 min wall clock) — review runs in parallel — Cost if wrong: a follow-up fix commit on the branch.
T15: review dispatched (sonnet) in parallel with the CI e2e run on bb25aa7
T15: review 1 = spec ✅ (CI-unverifiable parts ⚠️), quality Needs fixes (sonnet) — Important: t21 autoDegrade on + ActivityScenario at FULL; t20 passes vacuously (cumulative spend); waitForIdle stand-ins in t12/t18/t23/t24; t19 only checks STOP gone; t18 not independent and doesn't prove dsh deleted
- Ruling: all Important + listed Minors into fix round 1, alongside whatever the bb25aa7 CI run reports.
T15: fix round 1/5 dispatched (resume implementer)
CI: run 85 (bb25aa7) — every job failed in 2 s with no runner assigned and no logs (HTTP 404 on logs): GitHub Actions quota/billing block, not code. Asked the owner to check Settings → Billing (Actions minutes). T15 fix round resumed after the 5-hour usage limit reset.
