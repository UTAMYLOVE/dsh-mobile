# Stage 1 Task 4 report: native shell, five tabs, floating dock; WebView leaves the main flow

Status: DONE_WITH_CONCERNS

## What changed (android/app/src/…)
New, `main/kotlin/app/dshmobile/ui/shell/`:
- `ShellTab.kt`: enum `ShellTab(zh, en)` OVERVIEW..SETTINGS, plus `needsDsh` (CHAT, AGENTS).
- `Tags.kt`: every brief constant, plus `DOCK = "dock"` (the floating stack: reconnecting chip, attach menu, dock), `ATTACHMENT_CHIP = "attachment-chip-"`, `ATTACHMENT_REMOVE = "attachment-remove-"` (needed so the layout test can find chips; the names are the ones Task 9's brief uses, so Task 9 must not add them again).
- `ShellNav.kt`: Compose-state `tab` (default OVERVIEW), `openSessionId`, `listOpen`; `select`, `openSession` (also switches to CHAT), `back()` (:32): list → open conversation → back to OVERVIEW → false.
- `ShellSession.kt`: `interface ShellRemote` (:14), `ShellSession` (:24; `close()` closes the remote and cancels its scope, idempotent), `ShellSessionHolder` (:40; `current: StateFlow`, `attach` reuses the same launch URL else closes the old one first, `detach`, plus read-only `launch: LaunchUrl?` so tests can check which dsh is attached).
- `Dock.kt`: `ComposerUi`, `AttachmentChip` (signatures as in the brief), `Dock` (:93, glass 30 dp: chip row, error line, input row attach/input/send-or-stop, tab row with sliding pill), `AttachMenu` (:143), `ChipRow`, `TabRow`.
- `StatusPanel.kt`: `StatusPanel(state, onRetry, onExportErrorLog)`: FogPanel with `main_loading` or `main_error_title/body` + RETRY + EXPORT_LOG; nothing in Ready.
- `FogShell.kt`: `LocalPagePadding` (:85), `FogShell` (:101) with the brief's signature. Root `semantics { testTagsAsResourceId = true }`; `FogBackground(rememberBackdrop())` tagged SHELL_BACKGROUND (no CompositionLocals provided, which is Task 14's job); header (DSH MOBILE, time, page name, five dots); pages in `AnimatedContent` (depth slide/fade/cut per `Effects`); LIBRARY always shows the placeholder; CHAT/AGENTS show `StatusPanel` while not Ready; the floating stack (chip, menu, dock) at the bottom. Composer enabled = `composer.enabled && status == Ready`. Reconnecting chip: `LaunchedEffect(delay)`, only after an unbroken `reconnectChipDelayMs`.
- `SettingsEnvFactory.kt`: `buildSettingsEnv(activity, app)`, moved from SettingsActivity.buildEnv.

Modified:
- `ui/MainActivity.kt`: rewritten with no WebView. `setContent { FogTheme(effectsFor(app.uiPrefs.effectLevel, systemReducedMotion())) { FogShell(…) } }`; SETTINGS tab = `SettingsScreen(env, onClose = null, contentPadding = LocalPagePadding.current)`; other tabs show an empty FOGCITY panel. `startDsh` (:230) → `showShell` (:251): `app.shellSessions.attach(running)`; Ready when the remote first reaches OPEN (immediately for a session this screen already saw open); `withTimeout(remoteOpenTimeoutMs = 60 s)`, and on timeout it logs `[remote] no connection (TimeoutCancellationException)` and shows Error. The collector (:129) calls `detach()` whenever `isRunning` is false (Ready→Loading as before). Back goes to `nav.back()` first (:116) and the callback is re-enabled after the system back runs. Lifecycle, UiHolder, give-up, Retry, rollback, app update, export and intents are unchanged. Test seams: `navForTest`, `uiStateForTest`, `remoteOpenTimeoutMs`.
- `ui/MainScreen.kt`: only `enum class MainUiState`.
- `ui/SettingsScreen.kt`: `onClose: (() -> Unit)? = null` (null = embedded: transparent background, no close button, no `safeDrawingPadding`), `contentPadding` (default 16/12).
- `remote/DshRemote.kt`: `: ShellRemote` (`override muxState`, `override close`).
- `DshMobileApp.kt` (minimal, +20 lines next to `uiHolder`/`updateChecker`, nothing near controller/service): `val uiPrefs`, `val shellSessions` (own `OkHttpClient` for loopback; each session gets a child scope of `applicationScope`).
- `AndroidManifest.xml`: SettingsActivity removed; MainActivity comment now reads 「configChanges：轉向、深色模式不重建殼」. Permissions untouched (ManifestPermissionsTest green).
- `res/values/strings.xml`: removed `main_settings_*` and `main_download_*`; added `shell_*` (hints 問點什麼… / DSH 啟動後才能傳訊息, 附件, 圖片, 檔案, 送出, 停止回覆, 移除 %1$s, 重新連線中…, DSH 狀態, the library sentence, brand).
- `app/build.gradle.kts`: androidTest gets the Compose BOM, `ui-test-junit4` and `espresso-core` (aliases already in `libs.versions.toml`, so the toml is unchanged).

Deleted: `ui/DshWebViewClient.kt`, `DshChromeClient.kt`, `DownloadHandler.kt`, `SettingsActivity.kt`; tests `DshWebViewClientTest`, `DownloadHandlerTest`, `SettingsActivityTest`, `MainScreenTest`, and `androidTest/.../ui/MainActivityTest.kt`.

Tests:
- New `test/.../ui/shell/FogShellTest.kt`: `FogShellTest` (9 brief cases), `FogShellLayoutTest` (`w412dp-h915dp-port`), and the shared `ShellHarness`. New `ShellSessionHolderTest.kt` (3).
- `runtime/FakeMux.kt`: `var upgradeGate: CompletableDeferred<Unit>?` (bounded at 30 s so a forgotten gate cannot hang shutdown).
- `ui/MainActivityTest.kt`: the fake dsh is now a new `FakeDshServer(mux = FakeMux())` per start, printing that server's launch URL. WebView assertions became `uiState`/`shellSessions` assertions. Deleted: WebView settings, JS bridge, file chooser ×3, JS dialogs, renderer priority (+ its shadow), overlay shrink, settingsOpens…. Ported: `aSecondStartOfTheSameDshBeforeTheRemoteOpensStaysLoading` (with upgradeGate), `aNewDshInstanceReattachesTheShellToItsLaunchUrl`. New: `readyOnlyAfterTheRemoteIsOpen`, `noWebViewAnywhereInTheContentTree`, `theErrorLogNeverHoldsTheLaunchUrl`, `backFirstClosesShellNavigation`, `startRetainsUiStartsTheServiceAndAttachesTheShell`, plus `aRemoteThatNeverOpensEndsInAnErrorWithTheReason` (60 s rule, timeout shortened to 1 s).
- `ui/SettingsScreenTest.kt`: `withoutOnCloseThereIsNoCloseButton`, `withOnCloseTheCloseButtonCloses`.
- androidTest `E2eSupport.kt`: WebView helpers removed; added `awaitShellReady(scenario)` (300 s) and `webViewsIn(activity)`. `E2eTest.kt`: `t01_theNativeShellIsReadyWithoutAWebView`, `t02`…`t06` renamed (t05 ends with `awaitShellReady`), t7 deleted, `t07_dshsFolderPickerHostListsTheWorkspace` keeps only the RPC assertions; the JS constants are gone.

## TDD evidence
- RED: `--tests 'app.dshmobile.ui.*'` failed to compile: `Unresolved reference 'ShellTab'`, `'shellSessions'`, `'navForTest'`, `'remoteOpenTimeoutMs'`, and `SettingsScreen` `onClose` type mismatch (`Function0<Unit>?` vs `Function0<Unit>`).
- First GREEN attempt: 3 failures, all in the tests:
  - RETRY sat under the dock on Robolectric's small default screen, so the test now scrolls the page to the end first.
  - The reconnect chip never appeared. A state written from the test thread reaches the composition only when snapshot notifications are sent. The tests now call `Snapshot.sendApplyNotifications()` before advancing `mainClock`. Probe output: effect starts at t=32 and fires at 2032; at +1999 there is no chip; +1 then one frame shows it.
- Two MainActivityTest fixes: a guard for an empty `servers` list, and the 1 s timeout needs `shadowOf(mainLooper).idleFor` because the Robolectric main-looper clock does not move with wall time.
- Mutation checks on the layout test (both reverted):
  - bottom padding fixed at safe+100: fails with `test-page-last ends at 815.0, below the dock's top 728.0`.
  - attach menu offset 40 dp down: fails with `attach-image … overlaps attachment-chip-a`.
- Focused GREEN: `ui.shell.*` 13/13, `MainActivityTest` 46/46, `SettingsScreenTest` 49/49.
- `./gradlew :app:assembleDebugAndroidTest` builds. A forced recompile (`--rerun`) shows no Kotlin warnings from new or changed files; the 4 it does show are pre-existing (AgentConfigWriter, DshVersionsTest, RootfsInstallerTest).

## Full suite
`./gradlew :app:testDebugUnitTest --max-workers=2`: 684 tests, 0 failures, 0 errors, 70 classes. The system-err output is the CloseGuard on WorkManager's SQLite and SettingsJobsTest's intentional "boom". Both are pre-existing: they also appear in untouched suites such as ErrorLogTest and HeartbeatWorkerTest.

## Deviations
1. `ShellSession.close()` cancels its `scope`. `DshMobileApp.shellSessions` therefore gives each session a child scope `CoroutineScope(applicationScope.coroutineContext + SupervisorJob(parent))` instead of `applicationScope` itself; cancelling the app scope on every dsh restart would be fatal.
2. `ShellSessionHolder.launch` (read-only) was added so the MainActivity test can assert "shellSessions.current's launch == the fake dsh URL" (ShellSession has no launch field).
3. Added `LocalPagePadding`, which the brief does not name: `page(tab)` has no padding parameter, so the shell provides it this way. The bottom padding is `max(safe+196 dp, measured height of the floating stack + 14 dp)`, so a chip row, attach menu or reconnecting chip never covers a page's last control.
4. Added Tags `DOCK`, `ATTACHMENT_CHIP`, `ATTACHMENT_REMOVE` (see above).
5. Reconnecting-chip test: the 2 s are counted from the frame in which the shell sees RECONNECTING. After +1999 ms no chip; +1 ms and one frame later it shows (the chip needs a frame to be composed).
6. Attach stays enabled when dsh is not Ready, because picking is local; input and send are disabled. The menu closes on a choice, a second tap on attach, or back. It stays open across tab switches and there is no tap-outside scrim, since a full-screen scrim would itself sit on top of every control.
7. Send/stop: the dock already swaps SEND for STOP when `running && text.isBlank()`. In MainActivity `onSend`/`onStop` are no-ops until Task 8.
8. Page transitions: depth slide + scale + fade, or fade, or cut, per `Effects`. The 2 dp sweep line is not drawn and is left to Task 14.
9. Ready after a remote-open timeout: the screen stays on Error until Retry, even if the mux opens later on its own (the brief only says Error).

## Concerns
- Robolectric measures text at nearly zero width (for example "DSH" is 4 px wide), so the 412 dp layout test proves the structure (stacking, padding, no overlap of boxes) but not how real text fits. Tab labels (OVERVIEW/SETTINGS at 9 sp, 64 dp cells) and the header need a look on the emulator in T15.
- Header time uses a minute-ticker `LaunchedEffect`. It is harmless in tests (it never blocks idling), but every page change recomposes the header.
- `DshMobileApp.kt` is also edited by Task 16 in parallel. My change is additive only (two lazies, four imports), so expect a trivial merge.
- `MainActivityTest` now starts a real `DshRemote` (WebSocket to MockWebServer) per test; teardown detaches the shell and closes every fake server. Runtime is about 34 s for 46 tests; 2 green runs so far (focused + full suite).
