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
