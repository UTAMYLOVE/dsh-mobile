# SDD ledger — plan: docs/superpowers/plans/2026-11-02-dsh-mobile.md

Spec: docs/superpowers/specs/2026-11-02-dsh-mobile-design.md (reachable)
Repo: C:\dsh-mobile (local, branch main; no remote yet — boss will create GitHub repos)
Boss approved (2026-11-02): 9 permissions incl. REQUEST_INSTALL_PACKAGES + ACCESS_NETWORK_STATE; downloads of dsh/pnpm/JDK21/Android SDK.

## Preflight scan

| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T0 → all | findings §Pin..§Search | ok |
| T1 manifest test ↔ T8/T10/T11d/T12 manifest edits | 9-permission set | ok; later tasks must not add permissions |
| T2 ↔ T4/T6/T7 | rootfs copies auth-gate.cjs + plugins built later | CONFLICT: T2 runs before artifacts exist |
| T3 ProotRunner ↔ T8 | command(args, env) | ok |
| T4 SessionToken ↔ T5/T8 | generate(), value | ok |
| T5 notify route ↔ T7 sendNotify | POST {gateway}/notify, DSHM_GATEWAY already includes token | ok |
| T5 CostMeter.consumeAlert ↔ Notifier.showCostAlert (T8) | nobody calls consumeAlert | GAP |
| T6/T7 health routes ↔ T8 HealthCheck | /dshm/health/{memory,notify} | ok |
| T6 heartbeat route ↔ T10 | POST /dshm/heartbeat with x-dshm-token | ok |
| T8 HealthCheck ↔ T9 | moved to T8 in plan | ok |
| T9 updateWithAutoRollback ↔ T11b | UpdateOutcome | ok |
| T10 "write error log" ↔ T11d errorLogZip | no ErrorLog component defined | GAP |
| T11c PhantomState ↔ T11b | 11b consumes | ORDER: 11c before 11b |
| T11d Exporter.share ↔ T11a downloads | 11a consumes | ORDER: 11d before 11a |
| T12 daily check in heartbeat worker | modifies T10 file not listed | GAP (files list) |
| Each task self-consistency | tests vs code vs files | ok for T0–T13 |

## Rulings
- Ruling: T2 build script copies `/opt/dshm` contents from a staging dir if present; smoke test checks only node/dsh/version/marker. T4, T6, T7 each add their artifact to the staging step in their own commit; T13 E2E asserts they're present in the rootfs — Why: T2 precedes the artifacts — Cost if wrong: small script rework.
- Ruling: T5 LocalGateway takes extra ctor param `onCostAlert: (usd: Double) -> Unit`, called after each `record` when `consumeAlert()` returns true; T8 wires it to `Notifier.showCostAlert` — Why: spec §5.6 alert had no caller — Cost if wrong: one param rename.
- Ruling: T8 also creates `app.dshmobile.log.ErrorLog(filesDir)` with `fun append(tag: String, msg: String)`, file `filesDir/logs/error.log`, rotate at 1 MB keeping one `.1`; dsh stderr is piped into it; T10 and T11d use it — Why: logging had no owner — Cost if wrong: small refactor.
- Ruling: execution order T0,1,2,3,4,5,6,7,8,9,10,11c,11d,11a,11b,12,13 — Why: interface dependencies — Cost if wrong: none.
- Ruling: T12 may modify HeartbeatWorker.kt to run ApkUpdateChecker on the first heartbeat of each day — Why: plan text says so but files list omitted it — Cost if wrong: none.

## Progress
Task 0: dispatched (base 54b76e5, model opus)

## Environment
- JAVA_HOME=C:\Program Filesclipse Adoptium\jdk-21.0.13.9-hotspot
- ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk (platform-tools, platforms;android-37.0, build-tools;37.0.0; licenses accepted)
- pnpm 11.28.10, node 24.22.1
- Remotes: origin=UTAMYLOVE/dsh-mobile (private); releases=UTAMYLOVE/dsh-mobile-releases (public, boss chose A). dshm.releasesRepo=UTAMYLOVE/dsh-mobile-releases. Work directly on main (new repo, boss approved setup).
Task 0: implementer DONE_WITH_CONCERNS (b4bdf7d), pinned dsh 0.2.1-rc.5; review dispatched (sonnet)
- Ruling: Auth — dsh web already has a per-launch random token (printed on stdout `dsh web: http://127.0.0.1:3080/?token=…`, exchanged for HttpOnly SameSite=Strict cookie) that guards / and /api + WS. Drop the `--require` auth-gate preload and `NODE_OPTIONS`. Task 4 becomes: Kotlin `DshLaunchLine.parse(stdoutLine): String?` (extract launch URL) + `SessionToken` (still used for LocalGateway) + TS helper `requireAuth(ctx, req, res): boolean` wrapping `ctx.get('connection').requestRejection(req)` that every /dshm/* route calls; tests: unauth plugin route → 401. App-side callers (HealthCheck, HeartbeatWorker) obtain the cookie by GETting the launch URL with an OkHttp cookie jar. — Why: spec §6 asks for exactly this and prefers built-in/plugin over preload; less code, survives dsh updates — Cost if wrong: re-add preload in Task 4.
- Ruling: Privacy default — set OTel telemetry off and have LocalGateway strip `dsh_session_log` and `dsh_plugin_packages` from request bodies for all providers, until boss decides otherwise — Why: spec says data stays on phone/keys local; conservative side per CLAUDE.md — Cost if wrong: DeepSeek-side feature that needs them breaks; one flag to flip. BOSS CONFIRMED 2026-11-02: keep all uploads OFF (no Ruling needed).
- Ruling: DeepSeek has peak/off-peak prices and web search is $10 per 1000 requests — Task 5 PriceTable supports time-of-day tiers (Asia/Taipei clock converted to DeepSeek's stated timezone) and per-request search fee from `server_tool_use.web_search_requests` — Why: findings — Cost if wrong: cost display off.
- Ruling: No pre-compaction hook — memory plugin listens to `session/event` type `compaction/start` and writes a flush note via ctx.llm; also re-inject on `agent/created` source=compaction — Why: findings §Plugins — Cost if wrong: some context lost at compaction.
- Ruling: dsh does not read MEMORY.md natively — memory plugin injects MEMORY.md/USER.md/daily notes via `ctx.systemPrompt.context`; "share with dsh native MEMORY.md" in spec is moot — Cost if wrong: none.
- Ruling: Notification deep link — App opens WebView at `/?dshm_session=<id>`; a small client plugin (memory plugin's frontend part) reads it and calls `ctx.uiWorkspace.openSession(id)` — Why: no per-conversation URL — Cost if wrong: notification opens main screen only.
- Ruling: Pink theme via a client plugin/`webserver/index-inject` overriding `--dsw-alias-*` tokens (incl. `--dsw-alias-state-business-primary` → #d6336c), not WebView JS injection — Why: official extension point, survives reloads — Cost if wrong: switch to WebView injection.
Task 0: review 1 = Approved quality, spec ❌ (4 Important: privacy decision, price tagging, config YAML, abs path); minors deferred: theme token list, x-api-key confirm, canonical model ids, hello package.json note
Task 0: fix round 1/5 dispatched (resume implementer) → 428bdda; re-review dispatched (sonnet)
- Ruling: LocalGateway adds a runaway-loop guard: max 120 model requests per minute per provider → 429 `{"error":"dshm_rate_guard"}` + one notification — Why: recon mock produced 3,672 requests in 200 s; spec anti-goal "大量燒錢 without notice"; doesn't conflict with "hard cap default off" (that is a daily $ cap) — Cost if wrong: very large teams throttled; constant is one line.
- Ruling: Key-save guard — memory plugin (or a tiny `dshm-guard` plugin bundled with it) returns 403 for RPC `credentials/set`, `credentials/unset`, `account/startSignIn` so keys can only be entered in the app's Keystore settings — Why: spec "keys only in Keystore"; verified in recon round 1 — Cost if wrong: user can't use DeepSeek account sign-in (not needed).
Task 0: fix round 1/5 (4 addressed, 0 open; commits b4bdf7d..428bdda)
Task 0: complete (commits 54b76e5..428bdda, review clean)
Task 0: minor (deferred): credential guard lives only in plugins/hello — must be carried into real plugin (covered by Ruling: key-save guard)
Task 1: dispatched (base 428bdda, model sonnet)
Task 1: implementer DONE (a544f8c..8336e5d), CI green run 41451421475; review dispatched
Task 1: complete (commits 428bdda..8336e5d, review clean)
Task 1: minor (deferred): permission test covers debug variant only; gradleProperty missing → generic error; CI runs on push+PR; no gradle wrapper-validation step; sdkmanager versions hard-coded in ci.yml; Robolectric SDK not pinned
Task 2: dispatched (base 8336e5d, model sonnet)
Task 2: implementer DONE_WITH_CONCERNS (6614b6c..d1f4ca8), CI 41456718227 green, arm64 120M/x86_64 123M; review dispatched
Task 2: review 1 = Approved w/ Important: git/rg missing, base image unpinned + no npm lock, dsh integrity unpinned, non-atomic output
- Ruling: Task 2 review finding 5 (smoke test set -u / lenient readlink) not sent to fix loop — reviewer's own text calls it harmless — Cost if wrong: none.
Task 2: fix round 1/5 dispatched (resume implementer)
Task 2: minor (deferred): ~120MB/arch pruning (md/map/ts/other-platform prebuilds); apt purge || true; set -x in inner-setup; staged file ownership to confirm when real plugins are staged
Task 2: fix round 1/5 (4 addressed, 0 open; commits d1f4ca8..3ef0c8b)
Task 2: complete (commits 8336e5d..3ef0c8b, review clean); sizes arm64 134M / x86_64 136M
Task 2: minor (deferred): lockfile/npm-ls copied to out/ before tar not cleaned on failure; npm ls || true can upload error text; no committed lockfile / npm ci
Task 3: dispatched (base 3ef0c8b, sonnet) then STOPPED by controller — 5h plan limit at 95%; re-dispatch fresh after reset (check git status for partial work first)
Task 3: re-dispatched fresh (base 3ef0c8b, sonnet)
- Ruling: Lean mode from Task 3 on (boss said "continue, hitting the limit is fine" without picking a mode; controller applied its recommended option 2): one task review per task on sonnet (opus for Task 5 gateway and the final review), re-reviews on haiku and only for Critical/Important fixes; low-risk same-shape tasks may be batched — Why: 5h plan window at 95% — Cost if wrong: fewer review seats on low-risk tasks.
Task 3: implementer DONE (d8fb307), CI android+plugins green run 41465992981; review dispatched
Task 3: PAUSED at usage limit — review package ready (review-3ef0c8b..d8fb307.diff), reviewer NOT dispatched yet. Named risk for reviewer: tar-slip guard rejects entries under extracted symlinks (may break real rootfs install); DNS /etc/resolv.conf inside proot for later task.

## Cloud session (2026-11-03 起，Linux 容器)
- Environment: JDK 21 (/usr/lib/jvm/java-21-openjdk-amd64), ANDROID_HOME=/opt/android-sdk (platforms;android-37.0, build-tools;37.0.0, platform-tools), node 24 at /opt/node24/bin, pnpm via corepack. Branch: claude/dsh-mobile-cloud-handoff-vfhvdh (cloud session must push here, not main). Review packages in git-ignored .superpowers/sdd/2026-11-02-dsh-mobile/.
- Task 3: review dispatched (sonnet, base 3ef0c8b head d8fb307)
- Ruling: Task 4 rewritten per auth Ruling into docs/sdd-ledger/task-4-brief.md (DshLaunchLine + SessionToken + @dshm/common requireAuth, fail-closed 503 when no connection service) — Why: plan Task 4 text describes the dropped preload — Cost if wrong: brief rewrite.
- Ruling: plugins live under plugins/packages/<name>/ (workspace glob is packages/*), bundled per plugin by esbuild into one ESM file with @dshm/common inlined; rootfs staging copies the bundles to /opt/dshm/plugins/<name>/ — Why: plan paths plugins/memory etc. predate the Task 1 workspace layout — Cost if wrong: path moves.
Task 3: review 1 = spec ✅, quality Needs fixes — Important: upgrade path deleteTree(rootfs) before move can leave a half tree that isInstalled() accepts (marker survives); fix = rename-aside swap (rootfs→rootfs.old, tmp→rootfs, delete old; stale rootfs.old cleaned at start)
- Ruling: guest /etc/resolv.conf + /etc/hosts (docker export leaves them empty/absent) — owned by Task 8: DshController writes etc/hosts (127.0.0.1 localhost, ::1 localhost) and etc/resolv.conf (Android LinkProperties DNS servers, fallback 1.1.1.1 / 8.8.8.8) into the rootfs before each launch — Why: reviewer named risk, not a Task 3 defect; launch time is when network info is known — Cost if wrong: dsh cannot reach api.deepseek.com by name → caught by Task 13 E2E.
Task 3: minor (deferred): LD_LIBRARY_PATH leaks into guest env (unset for guest in Task 8 launch, e.g. `env -u LD_LIBRARY_PATH`); no PROOT_NO_SECCOMP / fake /proc/stat,loadavg / /dev/shm binds (device-only, verify in Task 13); progress only between tar entries; hand-rolled deb tar parser ignores GNU long names/pax (re-check on pin bump); libs.versions.toml CRLF→LF churn; special tar entries skipped silently
Task 3: fix round 1/5 dispatched (fresh implementer, sonnet — original implementer was in the local session)
- Ruling: Task 5 brief rewritten into docs/sdd-ledger/task-5-brief.md folding in all gateway Rulings + findings: Provider = {DEEPSEEK, ANTHROPIC} (search uses the DeepSeek key/upstream under /deepseek/v1, no SEARCH provider); both providers get `x-api-key` (findings: dsh sends x-api-key, DeepSeek accepts it) instead of plan's Bearer; Usage extended with cache read/write + webSearchRequests; search fee DeepSeek $0.75/1000 and Anthropic $10/1000 (findings round 1; the earlier ledger line "$10/1000" for DeepSeek is superseded); unknown model → priced conservatively; China holidays ignored (overestimate); LocalGateway gains onRateGuard callback (rate guard Ruling's "one notification") — Why: plan Task 5 text predates findings — Cost if wrong: cost display off / one callback rename.
Task 3: fix round 1 implementer DONE_WITH_CONCERNS (0865470): restore-on-failure branch untested (needs a test hook); final rootfs.old delete is best-effort; re-review dispatched (haiku)
Task 3: fix round 1/5 (1 addressed, 0 open; commits 76d3881..0865470)
Task 3: complete (commits 3ef0c8b..0865470, review clean)
Task 3: minor (deferred): killed during final deleteTree(rootfs.old) leaves a full-size stale rootfs.old that only a later install() reclaims → Task 8/11c caller should delete a leftover filesDir/rootfs.old at startup even when isInstalled(); stale-dir deletes at install start run outside try (pre-existing pattern); restore-on-failure branch untested (failedUpgrade test fails at marker check before swapIn)
Task 4: dispatched (base cf45696, sonnet)
Task 4: implementer DONE (e1d9de3); LaunchUrl(url, port) data class from DshLaunchLine.parse; @types/node added; review dispatched (sonnet, base 009c1d9)
Task 4: complete (commits 009c1d9..e1d9de3, review clean)
Task 4: minor (deferred): requireAuth lets exceptions from ctx.get/requestRejection escape (still fail-closed); writeHead RangeError on out-of-range status (theoretical); ANSI strip covers CSI only; token regex \S+ lenient; no tests for IPv6/port 0/https/trailing text; redundant main in common/package.json
Task 5: dispatched (base 4b70c10, sonnet implementer; review on opus per lean Ruling)
Task 5: implementer DONE_WITH_CONCERNS (478642e): extras Provider.kt, 502 dshm_upstream_unreachable, 401 dshm_invalid_key, Accept-Encoding identity, 15-min read timeout, dot-segment 404, Ktor idle 3600s; AndroidKeyVault untested; review dispatched (opus)
Task 5: review 1 (opus) = spec ✅, quality Needs fixes — Important: `\` path segments (e.g. `..\v1`) bypass hasDotSegment; OkHttp resolves them so a token holder reaches unmetered DeepSeek endpoints (e.g. /v1/chat/completions) with the real key → cap/alert bypass
- Ruling: three review Minors enter fix round 1 as Important — (M1) drop connectionIdleTimeoutSeconds=3600 (it does not affect in-flight streams; it only lengthens unauthenticated socket hold); (M2) drop every `x-deepseek-harness-*` header by prefix (findings + spec privacy decision say strip the family, brief listed two examples); (M3) OkHttp Dispatcher maxRequests=64, maxRequestsPerHost=32 (spec default is 16 concurrent subagents; default 5/host would silently queue them) — Why: each is backed by spec/findings, not polish — Cost if wrong: three small edits.
Task 5: minor (deferred): request body fully buffered (large /v1/files upload → heap); settle in finally can mask the original exception if onCostAlert/record throws; every SSE data line JSON-parsed (pre-filter message_start/delta); start() twice leaks first server, server not @Volatile; tests don't assert hop-by-hop drop / Accept-Encoding identity, client-cancel test can't show upstream cancel; AndroidKeyVault.get doesn't catch ProviderException
Task 5: fix round 1/5 dispatched (resume implementer)
Task 5: fix round 1 implementer DONE (52e06ad); confinement check untested on its own (textual check catches all forms first); re-review dispatched (haiku)
Task 5: fix round 1/5 (4 addressed, 0 open; commits 1d926b7..52e06ad)
Task 5: complete (commits 0ad5c78..52e06ad, review clean)
Task 5: minor (deferred, FINAL REVIEW MUST TRIAGE — recommended fix before merge): `%2f`/`%2F` in the rest path is not rejected; OkHttp keeps it encoded so encodedPath stays under base, but an upstream that decodes %2f before normalising could reach /v1/...; fix = reject %2f case-insensitively + `..%2fv1` test. Also: KDoc above isDroppedRequestHeader belongs to isUnsafePath; task-5-report concern 4 / test count stale after fix round.
Task 6: dispatched (base 5344d42, sonnet)
- Ruling: Task 8 brief written (docs/sdd-ledger/task-8-brief.md): dsh launched with `--port 0` (port read from launch line) instead of fixed 3080; RunningDsh gains `launch: LaunchUrl`; HealthCheck.check(launch) logs in via DshClient (cookie jar) instead of check(port, token); new DshPatch (gateway baseURLs + privacy + browse picker + plugin inserts; Task 11b extends it), GuestNetworkFiles (hosts/resolv.conf Ruling), DshClient (shared with Task 10), DshMobileApp singleton; guest launched via `env -u LD_LIBRARY_PATH` (Task 3 minor); controller deletes stale rootfs.old (Task 3 minor); Notifier targets MainActivity by ComponentName string (Task 11a creates it) — Why: plan Task 8 predates the auth Ruling and Tasks 3–7 interfaces — Cost if wrong: small signature changes in Task 10/11.
Task 6: implementer DONE_WITH_CONCERNS (dd110ca): live dsh check passed (401/ok/403); dream uses ctx.agentDefaultModel.currentSelection + ctx.llm.prepareCall/stream (success path unit-tested only); webServer mounts after apply → ctx.inject(['webServer'], cb) (carried into Task 7 brief); CI rootfs edits CI-verified only; compaction handler fake-ctx only; review dispatched (sonnet)
Task 6: review 1 = spec ❌ (guard misses backslash form), quality Needs fixes — Important: credential guard normalizedPath doesn't fold `\`; dsh routes on WHATWG URL pathname which turns `\` into `/`, so authenticated `POST /api\credentials\set` reaches the RPC (verified live by reviewer)
Task 6: minor (deferred): TOCTOU between realpath check and read/append (agent-shell only); dream state .tmp not path-checked; fence regex rejects preamble+fence replies; oversize single note sent whole / unreadable notes advance state; chunked backlog returns first chunk only; loadStartupContext uncapped (needs a cap eventually); test gaps (failed MEMORY append keeps state, stale-lock takeover, append via symlinked memory/, HEAD without auth); build script hard-codes memory entry (Task 7 generalises); index.ts logs provider e.message
Task 6: fix round 1/5 dispatched (resume implementer)
Task 6: fix round 1 implementer DONE (ac08c53): guard uses WHATWG pathname + legacy fold, refuses \ and %5c under /api; live 403 confirmed; re-review dispatched (haiku)
Task 6: fix round 1/5 (1 addressed, 0 open; commits 0735334..ac08c53)
Task 6: complete (commits e4c3baa..ac08c53, review clean)
Task 6: minor (deferred): isApiBackslashForm misses absolute-form targets `http://h/api%5c…` (not reachable: dsh doesn't route %5c); any /api URL with `\`/%5c anywhere (incl. query) is refused — documented trade-off; task-6-report narrative lines 9/28 stale after fix
Task 7: dispatched (base 9d8783d, sonnet)
- Ruling: CI gets `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }` (controller infra commit after Task 7, which also edits ci.yml) and the controller pushes only at task boundaries — Why: every docs-only ledger push started a full 2-arch rootfs build; runs were piling up — Cost if wrong: a cancelled run hides one intermediate result.
Task 7: implementer DONE (5bc869b): live health 401/ok; sessionId from exec.agent?.id (ToolRunContext); notify_user returns error strings (not isError); review dispatched (sonnet)
Task 7: complete (commits 0612c9b..5bc869b, review clean)
Task 7: minor (deferred): code-point (not grapheme) truncation can split ZWJ/flag clusters (plan-conformant); untested: tool "unexpected error" branch, timeout string from tool, real-timer abort vs hanging server; tests assume 127.0.0.1:1 refuses; build script duplicates esbuild command per plugin; notify_user returns error strings (not isError) per brief
Task 8: dispatched (base 3f56b8b, sonnet)
- Ruling: Task 9 brief (docs/sdd-ledger/task-9-brief.md): injectable GuestCommand (ProotGuestCommand writes resolv.conf first); strict version regex before any fs/command use; host-side atomic `current` symlink swap with absolute guest target; `.previous` file; UpdateOutcome as sealed interface — Why: plan Task 9 had no runner abstraction or input validation — Cost if wrong: small API shape change for Task 11b.
- Ruling: Task 10 brief (docs/sdd-ledger/task-10-brief.md): DshController gains retain/release/stopIfUnretained holder set; DshService retains "service"; heartbeat retains "heartbeat" and calls stopIfUnretained — Why: plan's "was running → don't stop" races with the user opening the app during a cold-start heartbeat and would kill their session (Review Focus 3) — Cost if wrong: one small API in DshController.
Task 8: implementer DONE_WITH_CONCERNS (59bd52e): 154 tests; DshService/DshMobileApp/real proot launch compile-verified only (Task 13); controller takes healthCheck lambda + notify lambdas; system icon placeholder; start failure (e.g. rootfs missing) counted as crash; review dispatched (sonnet)
Task 8: complete (commits 2b519fb..59bd52e, review clean)
- Ruling: Task 8 minors 1 (patch file left on launcher failure), 2 (rootfs-not-installed counted as crash → misleading rollback give-up), 3 (no CrashPolicy reset after GIVE_UP), 5 (stop() during start is a no-op / isRunning published outside lock) are carried into Task 10 (appended to task-10-brief.md) — Why: Task 10 already modifies DshController/DshService and its holder logic depends on correct stop-during-start — Cost if wrong: none.
Task 8: minor (deferred): CrashPolicy uses wall clock (use elapsedRealtime); ErrorLog.secrets only grows; OkHttpClient honours system proxy for 127.0.0.1 (set Proxy.NO_PROXY in DshClient); AndroidManifest CRLF→LF churn; controller tests rely on `sleep 1` timing; no DshService Robolectric test; Notifier uses a system icon until Task 11 adds res
Task 9: dispatched (base f438642, sonnet)
- Ruling: Task 11c/11d/11a/11b briefs written (docs/sdd-ledger/task-11*-brief.md). Key decisions: 11c introduces Compose + SakuraTheme + launcher/notification icons; 11a theme via new `@dshm/ui` plugin (`webserver/index-inject`, per earlier theme Ruling) instead of WebView CSS injection; notification deep link uses findings fallback (b) — bring app to front + dsh home, sessionId only logged (no unverified JS injection); MainActivity releases its holder on stop with a 10-min background grace before stopping DshService (spec "not resident in background" vs not killing running agents); instrumentation tests compile in 11a and run in Task 13 CI (no KVM in this container); 11b: no per-worker model key in dsh 0.2.1 → only Lead model configurable (spec §5.5 gap, report to boss), summarization preset rewrite deferred, teamSize clamped to maxConcurrent, Agent Teams bundle added to existing profiles/web/package.json only — Why: plan text vs findings/earlier Rulings — Cost if wrong: UI rework in 11a/11b.
Task 9: implementer DONE_WITH_CONCERNS (93d32e6): DshRuntime interface added to DshController (no behaviour change); real npm-in-proot not runtime-verified. Ruling: after RolledBack delete the failed version dir and clear .previous (else manual rollback reinstates the broken build; pre-update previous is already pruned by keep-two) — sent back to implementer before review
Task 9: follow-up DONE (af660ac): failed version dropped + .previous cleared after RolledBack; review dispatched (sonnet, base 4b3d2b5)
Task 9: review 1 = spec ❌ (rollback robustness), quality Needs fixes — Important: one try covers stop/switchTo(old)/dropFailedVersion/ensureRunning; an IOException from cleanup or a throw from stop/switch skips restarting the old version and mislabels the outcome
- Ruling: Task 9 Minor 1 (rollback file work cancellable between stop and switchTo) joins fix round 1 — same lines, same failure class.
Task 9: minor (deferred): cancel-restore branch and stop()-throws untested; installLocked uses symlink-following isDirectory; param named runtime not controller; switchTo writes .previous before flip (lost target if flip throws); blocking npm not interrupted by cancellation (up to 15 min); un-timed latch awaits in a test; downgrade offered if registry latest moves back
Task 9: fix round 1/5 dispatched (resume implementer)
Task 9: fix round 1 implementer DONE (e3fd117): 3-phase rollback (NonCancellable stop+switch, restart, finally best-effort drop); test seam internal var dropTree; re-review dispatched (haiku)
Task 9: fix round 1/5 (2 addressed, 0 open; commits 8c2a78c..e3fd117)
Task 9: complete (commits 4b3d2b5..e3fd117, review clean)
Task 9: minor (deferred): RolledBack KDoc/reason don't say dsh is stopped on the switch-back-failed path (Task 11b UI must show dsh status after any outcome — carried into 11b brief); non-IO exception from cleanup after flip misreported as switch-back failure; caller cancellation during NonCancellable block discards switchError; lateinit job read in cancellation test; cancel branch (:171-176) has unguarded stop() and silently swallowed switchTo failure
Task 10: dispatched (base 38f6111, sonnet)
- Ruling: Task 12 brief written (docs/sdd-ledger/task-12-brief.md): rootfs bundled via Gradle props dshm.rootfsAbi/dshm.rootfsFile (generated assets, abiFilters) instead of flavors; noCompress xz; release ships arm64 only; tag v<name>+<code> → dshm.versionName/versionCode; APK verified by streaming SHA-256 then installed via FileProvider (cache/update/) + ACTION_VIEW; release.yml has workflow_dispatch dry_run (unsigned, no secrets) for controller verification — Why: flavors would rename every task/CI command; spec needs no secrets handled by Claude — Cost if wrong: build-script rework.
Task 10: implementer DONE_WITH_CONCERNS (c912cc9): 235 tests; extras: SystemForegroundService specialUse declaration + ID_HEARTBEAT, DshClient.postJsonForText (timeout-respecting); Ruling: DshService.onDestroy must release("service")+stopIfUnretained() not force stop() (holder Ruling) — sent back before review
Task 10: follow-up DONE (6fac543): onDestroy releases hold; 237 tests; review dispatched (sonnet, base f2bc370)
Task 10: review 1 = spec ❌, quality Needs fixes — CRITICAL: default WorkManagerInitializer still in merged manifest → DshMobileApp's Configuration.Provider/HeartbeatWorkerFactory never used → HeartbeatWorker (3-arg ctor) can't be instantiated on device; tests inject the factory so they can't see it
- Ruling: Task 10 Minor 1 (stopIfUnretained doesn't mark stopPending when holders empty during an in-flight start → dsh may finish starting with no holder) joins fix round 1 — Review Focus 3 territory.
Task 10: minor (deferred): race test never exercises the empty-holder window; DshServiceTest swaps controller via reflection on controller$delegate; GIVE_UP stop/reset untested (CrashPolicy not injectable); sleep/wall-clock based tests; heartbeat logs every run while rootfs missing (skip quietly); heartbeat FGS notification reuses service text; task-10-report top half stale on onDestroy; import order in DshMobileApp
Task 10: fix round 1/5 dispatched (resume implementer)
Task 10: fix round 1 implementer DONE (a5a9664): WorkManagerInitializer removed + guard test; holderless in-flight start cancelled; 242 tests; re-review dispatched (haiku)
Task 10: fix round 1/5 (2 addressed, 0 open; commits 7b6b82c..a5a9664)
Task 10: complete (commits f2bc370..a5a9664, review clean)
Task 10: minor (deferred): `starting = true` set after early checks in ensureRunning (holderless stop in that sliver not recorded; unreachable today since callers retain first — 11a MainActivity must retain("ui") BEFORE ensureRunning); stopIfUnretained KDoc imprecise; fix report lacks exact RED command
Task 11c: dispatched (base 5c62ff0, sonnet)
- Ruling: CI push trigger ignores docs/** — Why: ledger-only pushes kept cancelling the 20-min rootfs jobs (no full green run since Task 5) — Cost if wrong: a docs-only push isn't CI-checked (nothing to check).
Task 11c: implementer DONE_WITH_CONCERNS (dcd9bf2): 279 tests; Compose BOM 2026.01.01, compiler plugin 2.2.10; Compose tests need @Config(sdk=[36]) (espresso vs Android 17 jar) — 11a/11b must do the same; isComplete(rootfsInstalled, keyStored) + clear(step); permission dialog/settings intents compile-only; review dispatched (sonnet)
Task 11c: complete (commits e35411f..dcd9bf2, review clean)
Task 11c: minor (deferred): blocking non-cancellable rootfs install can overlap a relaunched install (RootfsInstaller has no lock → spurious tmp wipe; retry recovers) — consider a process-level mutex; catch(Throwable) shows raw t.message; per-class @Config(sdk=[36]) (consider robolectric.properties); untested: notification-denied path, AndroidOnboardingPlatform, phantom continue button visibility; Step.values()→entries; onb_key_body "not uploaded" wording loose (key goes to DeepSeek via gateway)
Task 11d: dispatched (base BASE11D, sonnet)
