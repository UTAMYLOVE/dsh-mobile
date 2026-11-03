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
