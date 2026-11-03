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

## Fix round 1

Commit 27558c2 (`fix: shell clips pages under the header and fades them before the dock, detaches the session when dsh stops`).

### What changed
1. **App-level detach** (`DshMobileApp.kt:186-204`). When `shellSessions` is created it starts a collector on `controller.isRunning` in `applicationScope`, dispatched on `Dispatchers.Main` (the same thread as every attach, so a stop that is seen late can never detach the next instance's session). On false it calls `detach()`. MainActivity's own detach stays and does no harm.
2. **Page viewport below the header** (`FogShell.kt`).
   - The header and a stage `Box` (`Tags.SHELL_STAGE`, `weight(1f)`, `clipToBounds`) now sit in a column, as in the mockup's `#stage`. The header (`Tags.SHELL_HEADER`) takes its own top inset in layout.
   - There is a 12 dp `#FEDFE1` .9→0 fade at the top of the stage. Page top padding is now 8 dp, since the header is no longer inside the padding.
3. **Fade before the dock** (`FogShell.kt:235` `fadeAbove`).
   - The stage draws in an offscreen layer with a `DstIn` vertical gradient: opaque down to 24 dp above the top of the floating stack, transparent from there down. It is a mask, so it does not depend on blur.
   - The stack's and stage's positions are read only at draw time (`FloatingEdge`), never in composition.
   - The page bottom gap grew to fade + 6 dp, so a page's last control is never faded.
4. **Disabled send glow** (`Dock.kt:214-227`). `ModulateAlpha` alone still showed the square edge in the native render, so a disabled `RoundButton` now multiplies every colour (glow, fill, rim, icon tint) by .45 and uses no layer at all. The glow stays round.
5. The pill offset is read while placing: `offset { IntOffset(x.value.roundToPx(), 0) }` (`Dock.kt:307`).
6. **Keyboard.** Composition no longer reads `safeDrawing` (which includes the keyboard).
   - The page bottom padding uses the navigation bar, `WindowInsets.imeAnimationTarget` (which changes once per show or hide) and the stack's own measured height (`onSizeChanged`, no insets).
   - The dock still follows the keyboard in layout.
   - The stack's per-frame position during the keyboard animation only feeds the draw-time mask, so it redraws without recomposing.
7. **Settings busy.**
   - `ShellNav.canLeave` / `onLeaveRefused`: `select()` keeps the tab and calls the refusal instead.
   - MainActivity refuses to leave SETTINGS while `draft.localBusy` or `settingsJobs.busy` is set, with the toast 「設定正在處理中，完成後才能切換分頁」 (`shell_settings_busy`).
   - Unsaved fields now live in `SettingsDraft` (`SettingsScreen.kt:113`): editing, key input, key error, agent settings, alert, cap, messages, local busy. MainActivity owns it, outside the tab content. It is memory only and never in saved instance state, so a half-typed key never reaches a Bundle.
   - `SettingsScreen(draft = remember { SettingsDraft.from(env) })` stays the default for other hosts.
8. The title row 「設定」 (now tagged `settings-title`) and the close button show only when `onClose != null`.

### Tests (all RED-checked by breaking the code, then restored)
- `MainActivityTest.aDshStoppedWhileNoScreenIsStartedDetachesTheSession`: stop the activity, stop dsh, then `current == null`, the mux is CLOSED and the upgrade count no longer grows. With the app collector removed: `timed out`.
- `MainActivityTest.settingsCannotBeLeftWhileItsActionRunsAndSaysWhy`: a running settings job keeps the SETTINGS tab and shows the toast; once the job ends, the tab can be left.
- `FogShellLayoutTest` now also asserts, for every tab and state, that the stage top is at or below the header bottom, and that the last page control ends at or above the dock top minus the 24 dp fade.
- New `FogShellRenderTest` (`@GraphicsMode(NATIVE)`, 412 dp, `decorView.draw(Canvas)` read back pixel by pixel; the page is a black rectangle drawing 400 dp beyond its bounds):
  - `pagesAreClippedUnderTheHeaderAndFadeBeforeTheDockWithBlur` (FULL) and `pagesFadeBeforeTheDockWithoutBlur` (SAVER) check five points: black mid-screen, light just above the header bottom (clip), light just below it (top fade), black 30 dp above the dock, light 2 dp above the dock (mask). Without the mask, luminance at the dock was 6; without clip and mask, it was 0 at the header.
  - `aDisabledSendKeepsItsRoundGlow`: no colour jump across the send button's square edge. The old alpha layer gave a jump of 9 against a limit of 6.
- New `ShellNavTest` (2): back order, and the leave guard.
- `SettingsScreenTest`:
  - `unsavedFieldsSurviveTheScreenLeavingAndComingBack`: alert text, agent-teams switch and half-typed key survive the screen being disposed and shown again; nothing is saved.
  - The two close-button tests also check the title tag.
- Note: without `@GraphicsMode(NATIVE)`, `decorView.draw` gives an unusable bitmap in this Robolectric setup.

### Results
- Focused: `ui.shell.*` 18, `MainActivityTest` 48, `SettingsScreenTest` 50, all green.
- Full suite: 692 tests, 0 failures, 72 classes. `:app:assembleDebugAndroidTest` builds. The only Kotlin warnings are the 4 pre-existing ones.

### Screenshots
`/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t4-shots/after-*.png` (01, 01b scrolled, 02, 03, 04, 05, 05b scrolled, 06, 07, zoom-dock, zoom-header), rendered by a throwaway test that was not committed.
- Pages clip at the header and fade under it.
- Settings content dissolves above the dock, so no text shows through the tabs.
- The disabled send glow is round.
- The embedded settings page has no 「設定」 title.

### Deviations
- Item 4 uses fading colours rather than `graphicsLayer(ModulateAlpha)`: the layer version still cut the glow off square in the native render.
- `SettingsDraft` is a new public class; `BudgetMessage` became `internal` so the draft can hold it.
- The toast also blocks while an app-scope settings job (apply, update, rollback) runs, because the brief says "while settings are busy", even though those jobs survive the tab being left.
- `nav.openSession` (deep links, Task 8) and `nav.back()` do not ask the guard. While settings are busy, SettingsScreen's own `BackHandler` already swallows back.
