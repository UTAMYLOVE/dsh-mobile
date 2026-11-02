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
