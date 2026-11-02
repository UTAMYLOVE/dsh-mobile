# Task 11a report: main WebView screen and the sakura theme plugin

Status: DONE_WITH_CONCERNS (see Concerns; most are real-device checks only the emulator CI of Task 13 can do).

## Plugin `@dshm/ui` (TypeScript)
- `plugins/packages/ui/{package.json,src/index.ts,src/sakura.ts,test/ui.test.ts}`; `name = 'dshm-ui'`, `inject = []` (the event is only ever emitted by the web server, headless compositions just never call it). `apply(ctx)`: `ctx.on('webserver/index-inject', table => table.push({ kind: 'style', text: SAKURA_CSS }))`, one info log without CSS.
- `SAKURA_CSS` (exported from `src/sakura.ts`):
  - light, selector `:root, html body`: the whole `--dsw-static-deepseek-50..900` scale as pinks (50 `#fff0f5`, 100 `#ffd6e3`, 200 `#fcb8cf`, 300 `#f98fb2`, 400 `#f06595`, 450 `#e24a82`, 500 `#d6336c`, 600 `#c2255c`, 700 `#a61e4d`, 800 `#8a1a41`, 900 `#6d1535`); `--dsw-alias-bg-base`, `-bg-layer-1/2/3`, `-bg-module-platform` = `#fff5f8`; `--dsw-alias-bg-document-selection: rgba(214, 51, 108, 0.4)`; `--dsw-focus-ring-color: #d6336c`.
  - `html body:not([data-ds-dark-theme]) { background-color: #fff5f8 }` (see decisions below for why not a plain `body`).
  - dark, selector `html body[data-ds-dark-theme]`: `--dsw-static-deepseek-400: #f06595`, `-450: #e64980`, plus `-800: #34415b` and `-900: #283142` restored to dsh's original values.
  - never touches `--dsw-alias-label-primary`, `--dsw-alias-brand-primary`, `--dsw-alias-brand-text`.
- Decisions that go beyond the brief's wording (all pinned by tests):
  1. `html body` instead of bare `body`: one type selector more specific, so the sheet wins whatever its position in the page relative to dsh's own (possibly runtime-injected) theme sheet, and custom properties set on `body` also beat ones dsh sets on `:root`. Dark rule `html body[data-ds-dark-theme]` outranks dsh's own dark rule. No `!important` (that would have beaten dsh's dark rule and painted dark mode light).
  2. The body background rule excludes the dark theme (`:not([data-ds-dark-theme])`); a plain `body { background-color }` would have made dark mode light.
  3. Because the scale is shared, replacing 800/900 with pinks in light would also change the dark theme's tertiary backgrounds (dsh dark uses 800/900 for `--dsw-alias-state-business-tertiary`, per findings §UI). The dark block therefore restores 800/900, which keeps "dark backgrounds unchanged".
- Build/CI: `plugins/package.json` `build` bundles ui to `packages/ui/dist/index.js` (1.4 KB); `.github/workflows/ci.yml` stages `out/staging/plugins/ui/index.js`; `rootfs/smoke-test.sh` checks `/opt/dshm/plugins/ui/index.js`; `pnpm-lock.yaml` gained the `packages/ui` importer. Local checks: YAML parses, `bash -n` ok (rootfs job itself is CI-only, as in Tasks 6/7).
- Kotlin `DshPatch.plugins()` inserts the third row `dshm-ui` -> `<pluginDir>/ui/index.js`.

### Live check against real dsh 0.2.1-rc.5 (scratch install)
The three bundled plugins (memory, notify, ui, copied into a dir without package.json) were loaded through a `--patch` yml; `curl` with the launch URL -> `303`, then `GET /` with the cookie -> `200`; the served `index.html` contains two `<style>` blocks and the sakura sheet (`--dsw-static-deepseek-500: #d6336c` and `html body:not([data-ds-dark-theme])` both found). dsh was killed afterwards (no process left) and the temp dir removed. Not verified live: that the pink actually wins the cascade in a real browser, and the rendered colours (the instrumentation test does this on the emulator).

## Kotlin app: final signatures
```kotlin
// ui/DshWebViewClient.kt
class DshWebViewClient(openExternal: (Uri) -> Unit, onLoaded: () -> Unit = {}, onMainFrameError: () -> Unit = {}, onRendererGone: () -> Unit = {}) : WebViewClient() {
    enum class Decision { ALLOW, EXTERNAL, BLOCK }
    @Volatile var dshPort: Int
    companion object { fun decide(url: String?, dshPort: Int): Decision }       // pure
}
// ui/DownloadHandler.kt
class DownloadHandler(exportDir: () -> File, http: OkHttpClient, dshPort: () -> Int, cookies: (String) -> String?, maxBytes: Long = 512 MiB) {
    sealed interface Result { Saved(file); Unsupported; Failed(reason) }
    suspend fun download(url: String, contentDisposition: String?, mime: String?): Result
    companion object { fun sanitizeFileName(raw: String?): String }
}
// ui/LaunchRequest.kt
sealed interface LaunchRequest { None; Rollback; ExportErrorLog; OpenSession(id); companion fun from(intent: Intent?): LaunchRequest }
// ui/UiHolder.kt
const val BACKGROUND_GRACE_MS = 600_000L
class UiHolder(runtime: DshHolderRuntime, holders: () -> Set<String>, stopService: () -> Unit, handler = main, graceMs = BACKGROUND_GRACE_MS, runAsync = Thread)
    { fun onUiStart(); fun onUiStop(); val graceScheduled: Boolean; companion const HOLDER = "ui" }
// ui/MainOps.kt
class MainOps(runtime: DshRuntime, previousVersion: () -> String?, rollbackVersion: () -> Boolean, errorLog: ErrorLog) {
    suspend fun ensureRunning(): RunningDsh
    suspend fun rollback(): RollbackOutcome   // RolledBack(version, running) | NoPrevious | Failed(reason)
    fun recordDeepLink(sessionId: String)     // ErrorLog tag "deeplink", only the id
}
// ui/MainScreen.kt:   enum MainUiState { Loading, Error, Ready };  @Composable MainOverlay(state, onRetry, onExportErrorLog, onOpenSettings)
// ui/MainActivity.kt: ComponentActivity (exported, singleTask); internal webViewForTest, internal openSettings()
// DshMobileApp: + exporter (lazy), dshVersions (lazy, ProotGuestCommand), uiHolder (lazy, process-level); proot runner hoisted to a private lazy
```

## Behaviour
- **Gate**: `onCreate` sends the user to `OnboardingActivity` and `finish()`es unless `OnboardingState.isComplete(rootfsInstaller.isInstalled(), keyVault.get(DEEPSEEK) != null)`.
- **Start**: `onStart` -> `uiHolder.onUiStart()` (= `retain("ui")` first, then cancel a pending grace) -> `DshService.start` -> `ensureRunning()` in `lifecycleScope` -> `webView.loadUrl(running.launch.url)` only if `decide(url, port) == ALLOW`. A launch URL that is already loaded (same dsh instance) is not loaded again when the app returns from the background. The launch URL is never logged and never put in an exception.
- **Overlay** (Compose, `SakuraTheme`, over the WebView in a FrameLayout; Ready draws nothing but the settings button so touches reach the page): Loading, Error ("重試" -> reload via `ensureRunning`, "匯出錯誤記錄" -> share the scrubbed zip), 32 dp semi-transparent "⚙" top-right opens `app.dshmobile.ui.SettingsActivity` by `ComponentName` string (Toast when the class does not exist yet, i.e. until 11b). State goes Ready on `onPageFinished` unless a main-frame error/HTTP >= 400 happened. The `isRunning` flow is collected while STARTED: dsh gone -> Loading and `ensureRunning()`, dsh back -> reload when the launch URL is new (service restart after a crash).
- **WebView**: JS + DOM storage on, `allowFileAccess=false`, `allowContentAccess=false`, `mixedContentMode=NEVER_ALLOW`, no multiple windows, no JS bridge, `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`. Edge-to-edge insets (system bars + IME) are applied as padding. Renderer death -> `recreate()`.
- **Navigation** (`decide`): ALLOW only `http://127.0.0.1:<dshPort>/...` (exact host, exact port, port known, no userinfo, scheme http); other http/https -> EXTERNAL (`ACTION_VIEW`+BROWSABLE, only when the request has a user gesture); `javascript:`, `file:`, `content:`, `intent:`, `data:`, `blob:`, `about:`, `mailto:` etc., other loopback ports, `localhost`/`*.localhost`, `127.x`, `0.0.0.0`, IPv6 literals, https to loopback, userinfo URLs, unparsable URLs -> BLOCK. `http://127.0.0.1.evil.com:<port>` is a normal host -> EXTERNAL.
- **Downloads**: `setDownloadListener` -> `DownloadHandler`: only an ALLOW URL; OkHttp with the `CookieManager` cookie, redirects not followed, 2xx only, size cap, written to `<name>.part` then moved to `cacheDir/export/<sanitized name>` (name from `URLUtil.guessFileName`, path separators and control chars removed, `.`/`..`/empty -> `download`, 120 chars max keeping the extension); then `Exporter.buildChooser` on `Dispatchers.IO` and `startActivity` on the main thread. Anything else (other origin, `blob:`) -> Toast "無法下載此類型的檔案". **Blob downloads are not supported.**
- **Intents** (`onCreate` when `savedInstanceState == null`, and `onNewIntent`; the extras are removed once handled): `action=export_error_log` -> `errorLogZip()` + `buildChooser` on IO, chooser on main; `action=rollback` -> confirmation dialog, then `MainOps.rollback()` (stop, `DshVersions.rollback()`, `ensureRunning()`, reload, Toast; no previous version -> Toast and nothing is touched; a failed switch restarts the version that was current); `sessionId` (only `[A-Za-z0-9_-]{1,200}`) -> ErrorLog tag `deeplink`, session id only, no JS injection (findings backup (b): the notification just brings the app to the front on dsh's home page). An action wins over a sessionId; unknown actions are ignored.
- **Back**: WebView history first, else the system default.
- **Grace timer** (`UiHolder`, owned by `DshMobileApp.uiHolder`, so it is process-level and survives Activity recreation): `onStop` -> `release("ui")`, then `Handler.postDelayed(check, 600_000)` (removes any older one first, so there is only ever one timer and every stop restarts the full ten minutes). `onStart` -> `retain("ui")` then removes the pending check. When it fires: if `"ui"` is held again, nothing; else `stopService(DshService)` (its `onDestroy` releases "service" and runs `stopIfUnretained`) and then `stopIfUnretained()` on its own thread (never stops while heartbeat holds dsh). Tested with a paused main looper and a fake `DshHolderRuntime`.
- **Manifest**: `MainActivity` exported, LAUNCHER, `singleTask`, `adjustResize`, `configChanges` for rotation/size/uiMode/density/font/locale/layout direction (no WebView reload). No new uses-permission (ManifestPermissionsTest green).
- **Build**: `testInstrumentationRunner` plus `androidTestImplementation` runner 1.7.0, rules 1.7.0, ext-junit 1.3.0, core (existing version ref), all pinned in `libs.versions.toml` (files kept CRLF).

## Tests
Whole suite: 390 tests, 0 failures, 0 errors, 0 skipped (was 313; 77 new), output pristine; `assembleDebug` and `assembleDebugAndroidTest` exit 0, output pristine. Plugins: 113 tests (97 + 16 new), typecheck and build clean.
New Kotlin tests: DshWebViewClientTest 15 (whitelist: dsh origin, other ports/default port, unknown port, look-alike hosts incl. `127.0.0.1.evil.com`, credentials, localhost and loopback variants, https to loopback, external web URLs, 17 dangerous/unknown schemes, null/malformed; client: gesture rule, blocked swallowed, port follows the dsh, no browser installed), LaunchRequestTest 7, UiHolderTest 11 (grace, cancel on return, process-level restart, single timer, heartbeat keeps dsh), MainOpsTest 7, DownloadHandlerTest 15 (sanitiser, source check, cookie header, disposition name, traversal name, 401, redirect not followed, unreachable, no URL in reason, overwrite, size cap), MainScreenTest 6 (Compose, `@Config(sdk = [36])`), MainActivityTest 16 (Robolectric: onboarding gate x3, hold/service/loadUrl against a real controller with a fake `sh` dsh, stop releases + grace scheduled + restart cancels, strict WebView settings, no JS bridge, export chooser via onCreate and onNewIntent, extra consumed, deep link logged, rollback asks first and does nothing on "cancel", confirmed rollback switches the symlink and restarts dsh (old process dead, new alive), nothing-to-roll-back Toast, settings component). DshPatchTest updated for the third plugin. Instrumentation `MainActivityTest` (2 tests) compiles only.

## TDD evidence
- RED plugin: tests first, `Test Files 1 failed | 6 passed (7)`, `Tests 97 passed`, `Failed to load url ../src/index.js` (implementation absent). GREEN: `7 files, 113 tests`.
- RED Kotlin: all test files (DshPatchTest third plugin, 7 new test classes) written before any main code; `compileDebugUnitTestKotlin` failed with unresolved references (`DownloadHandler`, `DshWebViewClient`, `LaunchRequest`, `UiHolder`, `MainOps`, `MainActivity`, `MainOverlay`, ...).
- GREEN iterations: (1) MainActivity tests 10/16 NPE (`applicationInfo.nativeLibraryDir` is null under Robolectric because `dshVersions` was built eagerly in `onCreate`) -> `MainOps` takes lambdas, `DshVersions` is built on first rollback (also better for launch time); (2) `127.0.0.1.evil.com` was caught by a `startsWith("127.")` loopback test -> proper dotted-quad regex; (3) all green. One run of the full suite had a single failure in `rollbackWithNothingToGoBackToSays...` (10 s wait for the Toast timed out); not reproducible (3 isolated runs of the class and 2 further full runs green; the final full run is the one reported above), wait raised to 30 s. See Concerns.

## Compile-verified only
The instrumentation test (needs a real dsh, the bundled rootfs and the three plugins): sakura `getComputedStyle(body).backgroundColor === rgb(255, 245, 248)`, external link does not navigate the WebView. Real WebView behaviour (Robolectric's WebView is a shadow): actual page load, 303/cookie handling in the real WebView, downloads through the real `DownloadListener`, back navigation, renderer death, insets/IME layout, the overlay's touch pass-through in Ready state, dark/light switching, notification action taps into `onNewIntent`, the grace timer with real time and a real `DshService`.

## Self-review findings (fixed)
- `repeatOnLifecycle` collector was launched from `onStart` and would have accumulated one collector per start/stop cycle; moved to `onCreate` (it restarts itself per STARTED).
- The first CSS draft would have painted dark mode light (body background, bg tokens) and changed the dark tertiary shade through the shared 800/900 steps; reworked (see decisions) and pinned by tests.
- Loopback detection by prefix wrongly classed `127.0.0.1.evil.com` as loopback.
- Defence in depth: `showDsh` refuses to load a launch URL that `decide` would not ALLOW.

## Concerns
1. **MainActivity is exported** (required by LAUNCHER), so another app can send `action=rollback` / `export_error_log`. Mitigations: rollback needs the user's confirmation (a deviation from "action=rollback -> stop, rollback..."), extras are validated and consumed, export only opens the share sheet with the scrubbed log. A stronger option (not done: the brief says exported=true on MainActivity) is an `<activity-alias>` as the launcher with MainActivity itself `exported=false`; notification PendingIntents of our own app would still work.
2. No `WebChromeClient`: `<input type=file>` uploads and JS `alert/confirm` dialogs are not handled (not in the brief). Subresource requests (images, fetch) to other hosts are not intercepted; only navigations are whitelisted.
3. Pink may not win the cascade for some token if dsh sets them with higher specificity or inline; only the emulator test (body background) and a screenshot can confirm. `html body` was chosen for margin, see above.
4. The settings button overlays the top-right corner of dsh's page (32 dp, 70 % opaque); whether it covers a dsh control needs a look on a device.
5. Blob downloads unsupported (Toast). The notification deep link only brings the app to the front.
6. The Toast-wait flake above (one occurrence in three full-suite runs, not reproducible).
7. `Exporter.buildChooser` (internal) is used instead of `share` so the file I/O can run on IO and only `startActivity` on the main thread, as the Task 11d review asked.

## Fix round 1
Two Important review findings fixed.

1. **The activity no longer starts dsh by itself** (`ui/MainActivity.kt`, `service/DshService.kt`, `DshMobileApp.kt`). The `isRunning` collector is now `combine(controller.isRunning, app.serviceGaveUp)`: running -> `startDsh()` (`ensureRunning()` just returns the running dsh, so a restart by the service gives a new launch URL and a reload); not running -> only Loading (Ready becomes Loading); not running and the service gave up -> the error screen ("重試" / "匯出錯誤記錄"). Retry (explicit user action) is the only thing that starts dsh again, besides the user opening the app (`onStart`, as before). New `DshMobileApp.serviceGaveUp: MutableStateFlow<Boolean>`: `DshService` sets it to true on GIVE_UP before `controller.stop()` and clears it in `onStartCommand`; the activity's `retry()` clears it too. CrashPolicy is therefore the only thing that decides about restarts while the app is in the foreground.
2. **Cleartext to 127.0.0.1 only**: new `res/xml/network_security_config.xml` (`base-config cleartextTrafficPermitted="false"`, one `domain-config cleartextTrafficPermitted="true"` with `<domain includeSubdomains="false">127.0.0.1</domain>`; no localhost, no other host), referenced by `android:networkSecurityConfig` on `<application>`. No new permission (ManifestPermissionsTest green). This also covers DshClient, HealthCheck, the heartbeat and downloads.

Covering tests: `MainActivityTest` +3 (`afterAStopTheActivityDoesNotStartDshAgain`: after `controller.stop()` no new process in 1.5 s, not running, WebView URL unchanged; `afterTheServiceGaveUpTheActivityShowsTheErrorScreenAndStaysStopped`; `whenDshComesBackElsewhereTheActivityReloadsTheNewLaunchUrl`: an external `ensureRunning()` with a new launch URL is loaded), new `NetworkSecurityConfigTest` 2 (the manifest's `networkSecurityConfigRes` equals the resource id; the XML has exactly base-config false and one domain-config true with the single non-subdomain domain `127.0.0.1`). Robolectric's `NetworkSecurityPolicy` shadow does not read the config, so the XML is asserted instead. The fake dsh launch URL now differs per start (port 45678, 45679, ...) so restarts are visible.

RED (new tests against the old code): `MainActivityTest` + `NetworkSecurityConfigTest`: 20 tests, 3 failed (`afterAStopTheActivityDoesNotStartDshAgain` failed on the second process; `NetworkSecurityConfigTest` 2 failed with the resource missing). The reload test already passed on old code (regression guard); the gave-up test needs the new API so it was added with the fix.
GREEN: `./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.ui.*' --tests 'app.dshmobile.NetworkSecurityConfigTest' --tests 'app.dshmobile.service.*' --tests 'app.dshmobile.ManifestPermissionsTest'`: all pass (MainActivityTest 19, NetworkSecurityConfigTest 2, DshServiceTest 3, ManifestPermissionsTest 1, ...).

Flaky tests found and fixed (cause of the Concern 6 flake of the first round): the full suite failed intermittently (`MainActivityTest.rollbackWithNothingToGoBackTo...` twice, once `DshServiceTest.destroyStopsDshWhenNobodyElseHoldsIt`) although every class passed alone. Cause: my fake dsh was `sh -c 'echo ...; sleep 60'`; `destroyForcibly()` kills only `sh`, the orphaned `sleep` keeps the pipes open for 60 s, so the controller's blocking reader coroutines stay parked on `Dispatchers.IO` threads, and with enough of them later `withContext(Dispatchers.IO)` calls in other tests starve. Fix in `MainActivityTest`: `exec sleep 60` (killing the shell ends the process and the pipes), and the activities are paused/stopped/destroyed in `@After`. Not changed: the same `sleep 60` pattern in the older DshServiceTest/DshControllerTest/Heartbeat tests (not mine; fewer processes). After the fix, 3 consecutive full runs passed.

Commands and counts: `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks` x3 in a row: 395 tests, 0 failures, 0 errors, 0 skipped each (was 390; +5), pristine; `./gradlew :app:assembleDebug --no-daemon -q` and `./gradlew :app:assembleDebugAndroidTest --no-daemon -q`: exit 0, pristine. Plugins untouched.

## Fix round 2
Three findings from the scoped re-review fixed.

1. **Retry runs under the service** (`ui/MainActivity.kt`): `retry()` (now `internal`) calls `DshService.start(this)` before `startDsh()`. After a GIVE_UP the service has stopped itself, so without this a dsh started by Retry had no `onExit` handler, no CrashPolicy decision, no notice and no restart.
2. **GIVE_UP wiring is covered** (tests only; `DshService` unchanged): real GIVE_UP driven through `DshService` with a fake dsh that exits right after coming up, three times.
3. **Stale flag on reopen** (`MainActivity.onStart`): `serviceGaveUp` is cleared at the top of `onStart`, before `super.onStart()` (that is where `repeatOnLifecycle` starts the collector, so clearing it after `DshService.start` was too late) and before the service starts.

Covering tests (all new):
- `DshServiceTest.threeQuickCrashesAreAGiveUpAndTheScreenIsToldAboutIt`: fresh start is not a give-up; three launches that die at once -> `serviceGaveUp == true`, service stopped by itself, exactly 3 processes, dsh stopped, give-up notification posted.
- `DshServiceTest.aServiceStartClearsAStaleGiveUp`: flag preset to true, `startCommand` -> false.
- `DshServiceTest.afterAGiveUpARetryRunsUnderTheServiceAgainAndALaterCrashIsHandled`: give up, destroy the old service, start a new one (what Retry does): flag false, `"service"` in `holders`; dsh #3 dies unexpectedly after 1 s and the new service's CrashPolicy path restarts it (#4 running, five processes, no second give-up, `"service"` still held). The fake controller helper now takes a per-launch script.
- `MainActivityTest.retryAfterAGiveUpClearsTheFlagAndStartsTheServiceAgain`: gave-up -> Error screen, `retry()` clears the flag and a `DshService` start intent is issued.
- `MainActivityTest.reopeningNeverFlashesAStaleGiveUpError`: stale flag preset; an Unconfined collector records every UI state through `create/start/resume`; the flag is false and `Error` was never seen.

RED (all against the unfixed code or with the line deleted, then restored):
- Retry without the service start: `retryAfterAGiveUp...` failed with `NullPointerException: Cannot invoke "android.content.Intent.getComponent()" because the return value of "ShadowApplication.getNextStartedService()" is null`.
- Stale flag: `reopeningNeverFlashesAStaleGiveUpError` failed with `java.lang.AssertionError` (the `assertFalse(app.serviceGaveUp.value)` after start).
- With `DshService`'s flag-set line (`serviceGaveUp.value = true // before stop()`) deleted: `threeQuickCrashesAreAGiveUp...` and `afterAGiveUpARetryRuns...` failed with `java.lang.AssertionError: condition not met in time` (the wait for `serviceGaveUp`).
- With the flag-clear line in `onStartCommand` deleted: `aServiceStartClearsAStaleGiveUp` and `afterAGiveUpARetryRuns...` failed with `java.lang.AssertionError` (the flag was still true after the service start).
GREEN: both lines restored (`git diff` of `DshService.kt` empty), the fixes above in place: `MainActivityTest` 21 and `DshServiceTest` 6 pass.

Commands: `./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.ui.MainActivityTest' --tests 'app.dshmobile.service.DshServiceTest'` (exit 0); `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks` (exit 0, 400 tests, 0 failures, 0 errors, 0 skipped; was 395, +5); `./gradlew :app:assembleDebug --no-daemon -q` and `./gradlew :app:assembleDebugAndroidTest --no-daemon -q` (exit 0, pristine). Plugins untouched.

## Fix round 3
Ruling: after a GIVE_UP only an explicit user action restarts dsh (Retry on the error screen, or the crash notification's rollback action). This reverses the round-2 handling of reopening.

What changed (`ui/MainActivity.kt` only):
- `onStart` no longer clears `serviceGaveUp`. It still does the UiHolder bookkeeping (`retain("ui")`, grace cancelled); when the flag is set it shows the Error screen and returns without `DshService.start` and without `ensureRunning`. Without the flag it behaves as before (service start + `startDsh()`).
- The `isRunning`/`serviceGaveUp` collector now gives the give-up precedence over "running" (a dsh that someone else, e.g. the heartbeat, brought up does not hide the Error screen; Retry stays the user's way out).
- The flag is cleared by `retry()` (which already started the service, round 2) and by the rollback handler: after `MainOps.rollback()`, if dsh runs again (rolled back, or the current version was restarted after a failed switch) the flag is cleared and `DshService.start` gives supervision back to the service. A rollback that leaves dsh stopped (nothing to roll back to while given up) keeps the flag and the Error screen. `DshService`'s own clear on `onStartCommand` is unchanged.
- The flag lives in memory, so a new process starts fresh.

Covering tests (`MainActivityTest`, replacing `reopeningNeverFlashesAStaleGiveUpError`, whose premise is reversed; with it went the `job.cancel()` request, the test no longer exists; no other test uses a collector job):
- (a) `aGiveUpSurvivesLeavingAndComingBackNothingRestartsDsh`: give up while open (flag, then `stop()`, Error shown), `pause/stop`, `restart/start/resume` (the Settings round trip): flag still true, Error screen, no `DshService` started, still exactly 1 process (no `ensureRunning` start), dsh not running, `"ui"` held.
- (b) `retryAfterAGiveUpAndReentryStartsTheServiceAndClearsTheFlag`: same setup, `retry()`: service start intent, flag false.
- (c) `aFreshOpenWithoutAGiveUpStartsNormally`: service started, page loaded from the launch URL, dsh running, `"ui"` held.
- (d) `theRollbackActionAfterAGiveUpClearsTheFlagAndRestartsDsh`: after re-entry the rollback intent (`onNewIntent`) + confirm: symlink switched to `0.2.0`, a second dsh process running, flag cleared, `DshService` started, WebView loads the new launch URL.

RED (new tests against the round-2 code): `MainActivityTest` 24 tests, 2 failed: (a) `java.lang.AssertionError: the gave-up flag is still set`, (d) `java.lang.IllegalStateException: timed out`; (b), (c) already passed (guards).
GREEN: `MainActivityTest` 24 and `DshServiceTest` 6 pass.

Commands: `./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.ui.MainActivityTest' --tests 'app.dshmobile.service.DshServiceTest'` (exit 0); `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks` (exit 0, 403 tests, 0 failures, 0 errors, 0 skipped; was 400, +3: the stale-flag test replaced by four); `./gradlew :app:assembleDebug --no-daemon -q` and `./gradlew :app:assembleDebugAndroidTest --no-daemon -q` (exit 0, pristine). Plugins untouched.
