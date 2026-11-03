# Final whole-branch review: DSH Mobile (bf044b6..453da83)

Reviewer: final review (read-only). I worked in passes: ledger, constraints and spec first, then the current source of every
production component (gateway, controller/service/holder, MainActivity/Settings/Onboarding, versions, heartbeat, update,
exporter/logging, plugins, rootfs, CI/release). I read the diff package only where I needed a hunk's history. I ran no test
suites. I made one network check: the Termux package pool (see I-4).

## Strengths

- **Secret handling holds end to end.** The real keys live only in `AndroidKeyVault` (AES-GCM, Keystore key, provider bound as AAD). The gateway injects `x-api-key` and drops `authorization`/`x-api-key`/hop-by-hop headers. dsh only ever sees `dshm-proxy`. `ANTHROPIC_API_KEY` is set only when a Claude key exists, and `DshPatch` refuses an anthropic mention otherwise. `SessionToken.toString()` is masked, Ktor logs go to slf4j-nop, and the launch line is never logged. `ErrorLog` masks registered secrets, `token=…` and 64-hex runs at write time. `SecretScrubber` masks again on export. Exceptions are reported by class name. No `android.util.Log` anywhere.
- **Privacy is enforced in three layers.** The `--patch` rows (otel DISABLED, session-log off, plugin inventory off), `DSH_TELEMETRY_MODE=DISABLED`, and the gateway stripping `dsh_session_log`/`dsh_plugin_packages` and every `x-deepseek-harness-*` header.
- **The holder model is sound.** `retain/release/stopIfUnretained` is a sound contract between the service, the UI, the heartbeat and the in-flight start. `stopPending` covers the start window. The exit watcher and `start()` decide "published vs. failed start" atomically (the Task 13 fix), so a crash is never counted twice or not at all.
- **Version switching is well engineered.** `DshVersions` uses an atomic symlink swap, a 3-phase rollback with NonCancellable stop+switch, best-effort cleanup last, and a strict version regex before any fs or command use.
- **The release pipeline is careful.** The dry run is unsigned and needs no secrets. The keystore is decoded under umask 077 and deleted in an always() step. The APK is checked for foreign ABIs and key material. The update checker accepts a release only with an exact tag shape, a single .apk plus its .sha256, an allow-listed URL and a 64-hex hash, and the downloader verifies a streaming SHA-256.
- **The end-to-end suite runs the real stack.** It covers spec §9's five checks on a real emulator with the real proot/Debian/Node/dsh and the plugins, and it found real integration bugs (libandroid-shmem, a dsh dying during its start check, Ready shown before the page loaded).
- **The 9 permissions are exact.** The androidx `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is explicitly removed, and a WorkManager initializer guard test exists.

## Issues

### Critical (Must Fix)

None found.

### Important (Should Fix)

**I-1. Gateway path check is a blacklist; `%2f` and `;` still pass.** `android/app/src/main/kotlin/app/dshmobile/gateway/LocalGateway.kt:286-292` (`isUnsafePath`)
- **What's wrong:** `/<token>/deepseek/..%2fv1/chat/completions` and `/<token>/deepseek/..;/v1/...` pass both `isUnsafePath` and `isConfinedTo`. OkHttp keeps `%2F` encoded and treats `;` as a plain character, so `encodedPath` still starts with `/anthropic/`.
- **Why it matters:** An upstream front end that decodes `%2F` before resolving dot segments (nginx's normalised `$uri` does), or that treats `..;` as `..` (Tomcat/Spring), routes the request to `/v1/chat/completions` with the real key. That endpoint answers in a format the usage tap cannot read, so the cost meter, the alert and the hard cap are bypassed. Only a token holder (dsh, or a prompt-injected agent shell that reads `DSHM_GATEWAY`) can do this, but the meter exists precisely to cap a runaway agent.
- **How to fix:** Allow-list instead of blacklist. Reject any rest path with a character outside `[A-Za-z0-9._~/-]`, with `%` or `;`, or not matching `^(/v1(/[A-Za-z0-9._-]+)*)?/?$`. Add the `..%2fv1`, `..%2Fv1`, `..;` and `%252e` tests. This also closes the ledger's Task 5 deferred item. Move the stray KDoc at :280 onto `isUnsafePath`.

**I-2. The main-screen rollback runs in the Activity's scope and its supervise step can crash the app.** `android/app/src/main/kotlin/app/dshmobile/ui/MainActivity.kt:380-408`, `:344-349`
- **What's wrong (crash):** `rollback()` runs `ops.rollback()` in `lifecycleScope`. That is stop, switch, then `ensureRunning`, which can take 1–2 minutes with the launch timeout plus the health check. It then calls `superviseAndShow()`, which calls `DshService.start(this)`, which calls `startForegroundService`.
- **When it happens:** The user confirms the crash notification's rollback and leaves the app while it runs. After a give-up no FGS is running and the activity is stopped, so Android 12+ throws `ForegroundServiceStartNotAllowedException` from inside the coroutine and the process crashes, taking the just-restored dsh with it.
- **Second problem (destroy):** If the activity is destroyed mid-rollback, `ensureRunning` is cancelled after `stop()`, leaving dsh stopped. Task 11b fixed exactly this class of problem for Settings (applicationScope + NonCancellable + `runCatching { supervise() }`); the main screen never got it.
- **Third problem (no mutual exclusion):** The main-screen rollback is not exclusive with Settings jobs, so an update and a rollback can interleave stop/switch/start.
- **Ledger link:** The 11a deferred item "if ops.rollback() throws, finally only resets rollingBack" also belongs here. A start cancelled by a stop surfaces as a CancellationException and skips `showRealStateAfterRollback`.
- **How to fix:** Run the main-screen rollback through `app.settingsJobs.launch { withContext(NonCancellable) { … } }`. Wrap `DshService.start` in `runCatching` in `superviseAndShow` as Settings does. Have the screen observe the job result (it already reconciles from `isRunning`).

**I-3. The release is not gated on the emulator end-to-end suite, and the released dsh dependency tree is never tested.** `.github/workflows/release.yml:71-110`
- **Spec §9:** "測試在雲端 Android 模擬器實際啟動 dsh … 全過才發佈" (all must pass before publishing).
- **What's wrong:** A tag push runs unit, plugin and rootfs smoke tests only. It does not run the e2e job and does not check that ci.yml was green for the tagged SHA. It also rebuilds the rootfs fresh (`apt-get install` and `npm install` without a lockfile), so the published dsh dependency tree is not the one CI tested.
- **Also:** `gh release create` publishes immediately and is not `--draft`, so a half-failed upload leaves a visible broken "latest" release. There is no check that the versionCode exceeds the current latest.
- **How to fix (before the first tag):** Do one of these:
  - Make release.yml run the e2e job, with an x86_64 rootfs from the same build inputs, as a `needs:` dependency.
  - Gate publishing on `gh api repos/:owner/:repo/commits/$SHA/check-runs` showing ci.yml's e2e as success.

  Then create the release as `--draft`, upload, and flip with `gh release edit --draft=false`. Compare against `releases/latest`'s versionCode. Consider committing the `package-lock.json` the rootfs build already emits and using `npm ci`.

**I-4. The proot binaries come from a moving Termux pool, so release builds will break without warning.** `android/app/proot.gradle.kts:17-31`
- **What's wrong:** The `.deb`s are fetched from `packages.termux.dev/apt/termux-main/pool/main/...` by exact filename. I checked: the pool currently holds only `proot_5.1.107.102_aarch64.deb`. Termux keeps only the current version there.
- **Why it matters:** The next proot/libtalloc/libandroid-shmem bump in Termux makes `fetchProot` 404, and every CI and release build fails. The sha256 pin protects integrity, not availability.
- **How to fix:** Mirror the six debs (or the extracted `.so` files, about 1 MB total) somewhere you control: a committed `third_party/` directory, LFS, or a release asset of the private repo. Keep the sha256 pins, and fall back to Termux only if the mirror is missing.

**I-5. User data lives inside the replaceable rootfs: a future rootfs or plugin update wipes memory, conversations and dsh updates.**
Files: `RootfsInstaller.kt:33-40, 83-99`; `DshMobileApp.kt:83, 123` (workspace and dsh home are `filesDir/rootfs/root/...`); `build.gradle.kts:50` together with `rootfs/versions.env:3`.
- **What's wrong:** The plugins (`/opt/dshm/plugins`) ship inside `rootfs.tar.xz`. The device re-extracts only when the version marker changes. So:
  - shipping any plugin fix, Node or Debian change requires bumping `ROOTFS_VERSION`;
  - that bump sends onboarding into a reinstall whose `swapIn` deletes the old tree, including `/root/workspace` (MEMORY.md, USER.md, daily notes, workspace files), `/root/.dsh` (conversations, settings) and any dsh version installed via Settings;
  - there is no warning and no automatic backup;
  - forgetting the bump silently leaves old plugins next to new Kotlin.
- **Two version sources:** `ROOTFS_VERSION` is defined in two places (`build.gradle.kts` and `versions.env`) with no test tying them together. If they diverge, the marker check fails forever and onboarding can never finish.
- **Why it matters:** Nothing breaks today, since the first release has no prior state. Spec §7.1 asks that memory not "全部消失" (all disappear), and a reasonable user expects an app update to keep their data.
- **How to fix (cheapest before the first public release, because no migration is needed yet):**
  - Keep user state outside the rootfs and bind it in: `-b filesDir/home/workspace:/root/workspace` and `-b filesDir/home/dsh:/root/.dsh`.
  - Deliver plugins from APK assets to `filesDir/plugins` at each app version and bind them at `/opt/dshm/plugins`.
  - Derive `ROOTFS_VERSION` for BuildConfig from `versions.env` in Gradle.
  - Alternatively, keep the layout but preserve `root/workspace` and `root/.dsh` across `swapIn`.

**I-6. A downloaded update is lost if the user leaves the app during the ~130 MB download.** `MainActivity.kt:440-447`
- **What's wrong:** `apkInstaller.install()` starts the installer activity from wherever the coroutine is when the download ends. When the activity is stopped, Android's background-activity-launch rules block it silently: no exception, so no "ready" notification. The update notice was auto-cancelled.
- **Effect:** The user taps update, waits, switches apps, and nothing ever happens until the next day's check.
- **How to fix:** After the download, `if (!lifecycle.currentState.isAtLeast(STARTED)) notifier.showUpdateReady(info) else install(apk)`. A notification tap may start the installer. This was a Task 12 deferred item; I am raising its priority.

**I-7. The spec §7.1 workspace-file export is not demonstrably met.** `Exporter.kt:213`
- **What's wrong:** Spec §7.1 says "工作區內任何檔案…都能從 App 匯出" (any workspace file can be exported from the app). `shareWorkspaceFile` is not called anywhere. The only path is a dsh-UI download through `DownloadHandler`, which handles `http://127.0.0.1:<port>` URLs only. Blob downloads are unsupported, and nobody has verified that dsh's UI offers file downloads at all.
- **Related:** There is no `WebChromeClient`, so `<input type=file>` (attachments) does nothing, and `alert/confirm/prompt` are silently dismissed. If dsh's UI uses `confirm()` for, say, deleting a conversation, that action never happens.
- **How to fix:**
  - Add a Settings "Export workspace (zip)" next to the memory backup, reusing the symlink-safe walker.
  - Add a minimal `WebChromeClient` with `onShowFileChooser` (system picker), `onJsAlert`/`onJsConfirm`/`onJsPrompt` (native dialogs), and blob download support or a toast.
  - Verify on device which of these dsh actually uses.

**I-8. `START_STICKY` keeps dsh resident after a process death, against spec §11.** `DshService.kt:238`
- **What's wrong:** Spec §11 says "App 不在前景時 dsh 不常駐" (dsh does not stay resident when the app is not in the foreground). After the process is killed, the system restarts the service with a null intent, and it retains "service" and starts dsh. No UI hold or grace timer exists in the new process, so dsh and the ongoing notification stay until the user opens and leaves the app again.
- **Possible crash:** On Android 12+ a system restart that calls `startForeground` can also be refused (`ForegroundServiceStartNotAllowedException`). I can't confirm from here whether a sticky restart keeps its FGS allowance, so verify this on device.
- **How to fix:** Return `START_NOT_STICKY`. The UI's `onStart` starts the service and the heartbeat cold-starts dsh anyway. Alternatively, on a null-intent restart, schedule the UI grace immediately.

### Minor (Nice to Have)

- **M-1.** `HeartbeatWorker.kt:68,105` ignores `serviceGaveUp`. After a give-up the heartbeat restarts a known-broken dsh every period. If the Error screen is up (the UI holds "ui"), a dsh the heartbeat started successfully stays running unsupervised behind it. Fix: skip the run while `serviceGaveUp` is set.
- **M-2.** `SettingsJobs.kt:23-33` jobs do not retain a holder. If the 10-minute UI grace ends during a long update (npm install can take up to 15 min), `stopIfUnretained` cancels the new version's start and the update is reported as failed and reverted. Fix: `retain("settings-job")` for the job's duration.
- **M-3.** `OnboardingScreen.kt:201` keeps the typed key in `rememberSaveable`, so it enters the saved-instance Bundle held by system_server. Use `remember` (configChanges already prevents recreation).
- **M-4.** `DshMobileApp.kt:94,161` and `MainActivity.kt:247` use OkHttp clients for 127.0.0.1 that honour a system/Wi-Fi proxy. A proxied network fails the health check, counts as a crash and leads to a give-up. Set `.proxy(Proxy.NO_PROXY)` on loopback clients (Task 8 deferred).
- **M-5.** `AndroidKeyVault.get` (`KeyVault.kt:30-45`) does not catch `ProviderException`/`KeyStoreException`-as-runtime. In the gateway that becomes a 500 per request. Catch `RuntimeException` → null.
- **M-6.** `plugins/packages/memory/src/store.ts:78-93`: `loadStartupContext` is uncapped. MEMORY.md only grows through dream promotions, and it is in every request's system prompt, so cost and context grow without bound. Add a cap.
- **M-7.** `CrashPolicy` uses the wall clock (`DshMobileApp.kt:61`). Use `SystemClock.elapsedRealtime`.
- **M-8.** `UiHolder` uses a Handler (uptime) timer, so the 10-minute grace stretches while the device sleeps.
- **M-9.** `BuildConfig.DSH_PINNED_VERSION` (`build.gradle.kts:51`) is unused and duplicates `versions.env`. Remove it or derive it.
- **M-10.** The ci.yml rootfs cache key (`:64`) omits `.github/workflows/ci.yml`, where the staging step lives (Task 13 deferred).
- **M-11.** The exported `MainActivity` takes foreign intents (see triage). Add an `activity-alias` launcher with an unexported main activity, and `filterTouchesWhenObscured` on the rollback dialog.

## Deferred-minor triage

- **T0** (guard only in hello plugin): **can ship.** Carried into `memory/src/guard.ts` and verified live.
- **T1** (permission test debug-only, generic gradle error, CI triggers, no wrapper validation, hard-coded sdkmanager versions, Robolectric SDK unpinned): **can ship.** The release variant adds no dependency that brings permissions. Wrapper validation is a cheap follow-up.
- **T2** (size pruning, `apt purge || true`, `set -x`, staged ownership, out/ cleanup, `npm ls || true`, no lockfile/`npm ci`): **can ship.** The lockfile point is folded into I-3 for release reproducibility.
- **T3** (LD_LIBRARY_PATH leak; no PROOT_NO_SECCOMP / fake /proc / /dev/shm; progress granularity; deb parser; CRLF; silent special entries; stale rootfs.old; untested restore): **can ship.** The LD_LIBRARY_PATH leak is resolved (`env -u`), rootfs.old is cleaned at start, and the /proc and seccomp items need real-device verification (accepted arm64 gap).
- **T4** (requireAuth exception escape, RangeError, ANSI CSI-only, lenient token regex, missing parser tests, redundant main): **can ship.** All fail closed.
- **T5 `%2f` encoded slash:** **must fix before merge** (I-1). It is a one-line allow-list plus tests, and `;` has the same shape. The other T5 items (full body buffering, settle masking, per-line JSON parse, double start, test gaps, KDoc placement, ProviderException) **can ship**. ProviderException is M-5 and the KDoc is in I-1.
- **T6** (TOCTOU, tmp path, fence regex, oversize note, first-chunk backlog, uncapped startup context, tests, log text; absolute-form `%5c`, refusing any `\` under /api): **can ship.** The uncapped startup context is M-6, worth fixing soon for cost.
- **T7** (grapheme truncation, untested branches, port-1 assumption, duplicated esbuild, error strings): **can ship.**
- **T8** (wall-clock CrashPolicy, ErrorLog.secrets growth, OkHttp proxy for 127.0.0.1, CRLF, sleep-based tests, no DshService test, system icon): **can ship.** The proxy item is M-4, a cheap real-device hardening. The icon is already replaced.
- **T9** (untested cancel/stop-throws, symlink-following isDirectory, naming, `.previous` written before flip, uninterruptible npm, latch, registry downgrade; RolledBack KDoc, misreported cleanup exception, discarded switchError, unguarded stop in cancel branch): **can ship.** The 11b UI reconciles from `isRunning` after every outcome.
- **T10** (empty-holder window untested, reflection swap, GIVE_UP untested, sleep tests, quiet skip when rootfs missing, notification text, stale report; the `starting=true` sliver): **can ship.** I verified both screens call `uiHolder.onUiStart()` (retain) before any `ensureRunning`, so the sliver is unreachable.
- **T11c** (overlapping rootfs installs, raw `t.message`, per-class @Config, untested paths, `values()`, wording): **can ship.**
- **T11d** (NOFOLLOW share copy, absolute-guest symlinks, case-sensitive `token=`/JSON token, weak tests, sCache helper, transitive androidx.core, sync I/O, empty zips): **can ship.** ErrorLog masks at write time, before the scrubber runs.
- **T11a exported MainActivity:** **can ship.** The alias is a follow-up (M-11). What a foreign app can do:
  - `rollback`: needs the user's confirm dialog, and Android 12+ blocks untrusted overlay touch pass-through.
  - `export_error_log`: only opens the system chooser; the user picks the target, and the zip is scrubbed.
  - `install_update`: re-resolves the release from the fixed public repo; the hash plus the platform's same-signer check stop substitution, and the user approves the install. The worst case is an unwanted ~130 MB download on mobile data, or a settings page opening.
  - Foreign session ids: only append a log line, bounded by 1 MB rotation.
- **T11a, the other items** (launch-URL reuse — confirmed by the e2e; dark-mode background; download name/clobber/dedupe; per-activity OkHttp; test hygiene; orphan `sleep` patterns; runCatching teardown; `retry()` internal): **can ship.** The rollback-throws item is folded into I-2.
- **T11b** (env captured by the app-scoped job, main-thread reads, no CoroutineExceptionHandler, public MutableStateFlows, process kill between stop and start, mutation evidence, flaky log-mask test): **can ship.** Every job body catches `Exception`. A kill leaves dsh stopped, and the next `onStart` restarts it.
- **T12:**
  - Final redirect host unchecked, daily "no newer release" log line, null conflation, `open` members, weak RED evidence: **can ship.**
  - Installer blocked in background: **should fix** (I-6).
  - Not `--draft`, no monotonic versionCode: **should fix before the first tag** (I-3).
  - Actions pinned by tag, configuration-cache concern (not enabled): **can ship.**
  - Foreign `install_update`, pending flag armed by a foreign intent, toast-only failed resume, ready-retry needing network: **can ship.**
- **T13** (commit title, t3 accepting yesterday, t4 hitting GitHub, cache key missing ci.yml, report typo): **can ship.** The cache key is M-10.

## Declined to judge

- **DeepSeek/Anthropic prices, peak windows and search fees in `PriceTable`.** They come from the boss's dated findings, and I cannot verify current vendor pricing.
- **Model ids and dsh provider ids** (`deepseek-official`, `claude-*-5-5`). The ledger says they were confirmed live via `--dump-config`.
- **Whether `settings_enable_monitor_phantom_procs` is readable by a targetSdk 37 app.** It depends on device SettingsProvider behaviour I cannot test. A refusal maps to UNKNOWN, giving the conservative 6 subagents.
- **proot is not a security boundary.** The agent shell runs as the app's uid with `/dev` bound, so a determined native payload could in theory reach Keystore via binder. The spec's threat model (`env`, `/proc`) is met. Hardening (bind only the needed `/dev` nodes) is outside the spec.
- **The 120 req/min rate-guard constant, the 60 s launch and health timeouts and the 10-minute grace.** These are tuning values set by Rulings, not defects.
- **Agent CPU stalling with the screen off** (no wake lock is held for running agents). The spec is silent on background agent runs beyond "not resident".
- **UI wording, Chinese strings and exact sakura colour use beyond the token list.**
- **`.claude/settings.json` enabling the superpowers plugin.** It is developer tooling, not product.
- **Already-ruled items I have no reason to overturn:** no per-worker cheap model, the deep link opening home only, arm64/real-device not verified, the release.yml dry run not yet exercised, the dead-token e2e artifact, and the CI rootfs cache keyed on rootfs/plugins.
- **The OTel "DISABLED creates no transport" guarantee.** It relies on dsh documentation plus the no-connection observation in the findings. I did not re-derive it.

## Recommendations

1. **Before merge:** fix I-1 (gateway allow-list plus tests) and I-2 (main-screen rollback via SettingsJobs, guarded `DshService.start`). Both are small and local.
2. **Before the first `v*` tag:**
   - I-3: e2e gate, `--draft`-then-publish, versionCode check.
   - I-4: proot deb mirror.
   - I-5: move workspace and `.dsh` out of the rootfs and deliver plugins from the APK. This is cheapest now, while there is no installed base to migrate.
3. **Soon after:** I-6, I-7 (workspace zip export plus a minimal WebChromeClient), I-8, M-4 and M-6.
4. **First real-device run checklist:**
   - first start time under proot on arm64 (compare against the 60 s + 60 s budget);
   - whether `/proc/stat` and `/proc/loadavg` reads break anything in Node or dsh;
   - phantom-process setting readability;
   - npm install inside proot (DshVersions);
   - web search through `/deepseek/v1`;
   - whether a sticky-restart FGS is allowed (I-8);
   - behaviour with a Wi-Fi proxy set (M-4).

## Assessment

**Ready to merge?** With fixes

**Reasoning:** The architecture, the secret handling and the supervision contracts are solid, and I found no Critical defect. Two small code issues should land before merge: the gateway path blacklist that lets `%2f`/`;` through and could defeat cost metering (I-1), and the main-screen rollback that can crash the app after a give-up (I-2). The release-pipeline and on-device-state items (I-3 to I-5) must be fixed before the first public tag rather than before merge.
