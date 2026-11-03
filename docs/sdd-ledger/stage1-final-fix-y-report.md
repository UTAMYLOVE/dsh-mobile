# Stage 1 final fix wave, lane Y: rendering cost and CI (report)

Branch `final-fix-y` (worktree /home/user/wt-t7), from main 721e9e8. Local only, nothing pushed.
Commits: 721e9e8..6353793 (7 code commits, plus this report).

| commit | what |
|---|---|
| deb213d | B1-I1 (blur layer bounds and cap), B1-M5 (test-only blur semantics) |
| 18f908b | B1-I2 (idle tick), B1-M2 (LocalIdle as State), B1-I3 (grid), B1-I4 (background layers), plain-background switch |
| 5c09564 | B1-I4 (ring and dock layers), B1-M6 (status spinner) |
| b4072f1 | B1-I6 (page switch), B1-M1 (band-only dock fade) |
| b1a099e | B1-M3, M4, M8, M9, M10 |
| 7bb020a | A-I5 (ci.yml, advisory PerfGateTest, plain-background argument in E2eSupport) |
| 6353793 | B1-I1 follow-up: the dock outranks page glass at the cap |

Final verification: full unit suite `:app:testDebugUnitTest --max-workers=2`: 1006 tests, 0 failures, 0 skipped.
`:app:assembleDebug :app:assembleDebugAndroidTest` succeed. No new compiler warnings. The only warnings are older ones in
AgentConfigWriter, DshVersionsTest and RootfsInstallerTest, which are not lane-Y files.

## Findings

### B1-I1: blur layers unbounded
- **What changed (fog/Glass.kt):**
  - `GlassNode` (:288) keeps its window bounds, which already account for parent clips, from `onGloballyPositioned`.
  - It cuts the bounds at the dock with the new `LocalBlurFloor`/`BlurFloor` (:151). FogShell provides the floor to the page stage only, set from the floating stack's window top.
  - `drawBlurredBackdrop` (:464) records only that visible rect, snapped out to `LayerSnapPx` = 64 px (:491). This stops a slow scroll from resizing the layer every frame.
  - It draws the layer at that rect's offset.
  - A surface with nothing visible above the floor holds no layer.
- **Cap:** `BlurLayers` (:100) allows at most `MAX` = 4 live layers.
  - A surface turned away draws the no-blur (lifted) fill.
  - It re-tries as soon as a layer is released.
  - Follow-up 6353793: the dock is drawn after the page, so four page panels used to starve it. Glass outside a page (no floor: dock, attach menu, reconnecting chip) now takes the newest page holder's layer at the cap.
  - The blur semantics follow the real state, so a surface denied at the cap reports `Blur = false`.
- **Stopgap** (no blur above screen height): not needed. Each layer is now at most the visible part of the window.
- **Tests (GlassLayersTest):**
  - `noMoreThanFourBlurLayersLiveAtOnce`
  - `aTallPanelRecordsOnlyWhatTheWindowShows`
  - `glassBelowTheDockFloorHoldsNoLayer`
  - `theFloatingChromeOutranksPagePanelsAtTheCap`
- **RED:** run with MAX=100 and the visible-rect cut disabled. Three failed: the counts were 6 instead of 4, the layer was 300x3000 for a 300 px viewport, and a surface under the floor still held a layer. For the priority test, a constant priority made it fail (the dock's Blur was false). All are GREEN after the fix. The existing tests still pass.

### B1-I2: background redraws at full rate when idle
- **What changed:**
  - `rememberFogClock` (FogBackground.kt:84) waits `FogMotion.IDLE_TICK_MS` = 66 ms between ticks while idle, instead of ticking every frame.
  - The idle State is read inside the clock coroutine, not in composition.
- **Tests (FogBackgroundTest):** `anIdleBackgroundTicksAboutFifteenTimesASecond` (10..18 ticks in 62 frames) and `aBusyBackgroundTicksEveryFrame`. The tests count clock ticks, because each tick invalidates the draw lambdas. The Robolectric software `View.draw` re-runs the lambdas on every frame, so counting draws would not show the tick rate.
- **RED:** without the delay, 62 ticks per second.
- **Not done:** freezing the ambient motion during a list fling. The finding marks it optional, and it would touch the chat and overview lists, which are lane X's files.

### B1-I3: perspective grid
The controller's ruling is followed in intent, with one deviation, explained here.
- **Why the ruled fix (a) does not work as written:**
  - A graphicsLayer's `rotationX` puts the camera at the transform origin (the slab's bottom edge).
  - The mockup's `perspective: 340px` on `.grid` sees the slab from its centre.
  - So even with `cameraDistance = 340.dp.toPx()/72`, the far edge would sit about 70 dp above the bottom instead of the mockup's 169 dp.
- **What is done instead:**
  - The new `GridSlab` (FogBackground.kt:195) computes the mockup's exact plane projection as a 3x3 projective matrix:
    - slab 200% x 42%;
    - `rotateX(68deg)` about the bottom edge;
    - camera 340 dp from the slab's centre;
    - ceiling = floor mirrored (`scaleY(-1)`).
  - The 10%..85% mask and the .55 opacity are folded into the line colours, so no level needs an offscreen layer.
- **FULL and BALANCED** (`Effects.perspectiveGrid = true`): the lines are drawn under the matrix and flow one cell every 6 s. This is the 3D perspective of option (a).
- **SAVER** (`perspectiveGrid = false`): option (b). The same lines' end points are projected once and drawn flat with gradient or per-line alpha. There is no 3D transform, no offscreen layer, and no clock.
- **New field `Effects.perspectiveGrid`** (FogTheme.kt:62, defaults to true):
  - It is a deviation from "five flags only". It is not a motion flag: it describes how the grid is drawn.
  - Reduced motion keeps it, so FULL plus reduced motion still draws a static 3D grid.
  - It is needed because SAVER plus reduced motion and BALANCED plus reduced motion are otherwise indistinguishable.
  - `effectsFor` is the only constructor, so no caller changes.
- **Tests:**
  - EffectsTest: `onlySaverDrawsTheGridFlat`.
  - FogBackgroundTest: `saverDrawsTheGridFlatAndNothingTicks`, `fullDrawsTheGridInPerspective` and `balancedDrawsTheGridInPerspective` (FogDebug counters `background.grid.depth` / `.flat`).
  - Geometry: `theFloorsNearEdgeSitsOnTheScreensBottomAndItsFarEdgeRecedesUpAndNarrower` (far edge 169 ±2 px above the bottom at 412x915, centred, under 60% of the near edge's width), `theCeilingIsTheFloorMirrored`, `theMatrixIsTheSameProjection` and `theMaskFadesFromTenToEightyFivePercent`.
  - RED: with the old SAVER effects, `saverDrawsTheGridFlatAndNothingTicks` drew the 3D grid 34 times.
- **Screenshots:** see the screenshots section below.

### B1-I4: local animations re-record big layers
- `HudRing`'s draw box has a `graphicsLayer {}` (Hud.kt:131).
- The FogBackground box has one before its `drawWithContent`, and the glows and the grids each have their own.
- The dock column has one (Dock.kt:120).
- No direct test, because these are structural. FogDrawCacheTest and FogShellRenderTest still pass.

### B1-I5
Lane X (ShellNav.kt and MainActivity.kt). Not touched.

### B1-I6: page switch is the heaviest moment
- **Rising panels** (FogPanel.kt:61): `compositingStrategy = ModulateAlpha`.
- **Pages** (FogShell.kt:387): the fade is now the page's own alpha. It is a `transition.animateFloat` on the switch's EnterExit transition, applied through a ModulateAlpha graphicsLayer. It replaces AnimatedContent's fadeIn/fadeOut layers, so the two page-sized offscreen layers are gone. The depth slide and scale are unchanged.
- **Sweep:** the new `SweepLook` (FogShell.kt:457) draws the two glows as pre-built horizontal gradients (`GlowBand`). Each gradient has 17 stops sampled from the CSS-blurred band's profile, through a small `erf`. There is no mask filter under the skew.
- **"Slide pages without the fade":** considered, but not dropped. The fade costs nothing extra now.
- **Test:** `SweepLookTest.theSweepGlowsWithGradientsAndNoMaskFilter` (NATIVE graphics). It checks that no `maskFilter` is built (`cssBlurFilter` now counts one), that the core is drawn, that the white glow at +16 px and the pink haze at +50 px fade out with distance and are symmetric, and that nothing is drawn at +160 px.
- **RED:** `SweepLook` did not exist; the old code built two `BlurMaskFilter`s.
- **Regressions:** FogShellSweepTest, FogShellEffectsTest and FogShellRenderTest still pass.

### Minor findings
- **M1:**
  - `fadeAbove` (FogShell.kt:333) records the stage once into a cached layer.
  - It draws the layer as is above the band.
  - It save-layers only the 24 dp band for the destination-in mask, instead of the whole stage offscreen.
  - It rebuilds the brush only when the band moves.
  - FogShellRenderTest, which checks clipping and fade pixels at FULL and SAVER, stays GREEN.
- **M2:** `LocalIdle` is a `State<Boolean>` (FogTheme.kt:81). FogShell provides `idle.state` (:200) and never reads it in composition. The clocks read it, and FogShellEffectsTest was adapted.
- **M3:**
  - The scrim's alpha is read in `drawBehind` (PromptSheet.kt:148).
  - When the card opens it moves keyboard focus to the scrim (`parking`, :120-141).
  - A `LocalFocusManager.clearFocus(force = true)` was tried first. The test showed the window handing focus straight back to the text field.
  - Test: `ShellAccessibilityTest.aPromptClearsTheComposersFocus`, RED then GREEN.
- **M4:**
  - The scrim has `clearAndSetSemantics {}` and a consuming `pointerInput`, and it is no longer clickable.
  - Test: `theScrimIsSilentButStillEatsTouches`. It checks that no clickable node is left with nothing to read, and that a tap above the card does not reach the page. RED (the unlabelled clickable scrim) then GREEN.
- **M5:**
  - Glass is a `SemanticsModifierNode` only through `GlassSemanticsElement`/`GlassSemanticsNode` (Glass.kt:502), and only while `FogSemantics.exposeBlur` is set (FogBackground.kt:51).
  - The flag defaults to true under Robolectric (`Build.FINGERPRINT == "robolectric"`) and false in the app. Tests can also set it.
  - Reason for the Robolectric default: ChatScreenTest, in lane X's area, reads `FogSemantics.Blur` and keeps working without an edit.
  - Test: `glassReportsBlurOnlyWhileTheTestFlagIsOn`.
- **M6:**
  - The StatusPanel spinner is indeterminate only with `Effects.spin` on the current page. Otherwise it is a still quarter arc.
  - It has its own `graphicsLayer` (StatusPanel.kt:65) and the new tag `Tags.STATUS_SPINNER`.
  - Tests (StatusPanelTest): FULL, SAVER, reduced motion, and a page that is not current.
- **M7:** not in lane Y's list.
- **M8:**
  - The English tab label has `clearAndSetSemantics {}` (Dock.kt:376), so the tab reads only `總覽` and the other Chinese names.
  - Test: `aTabReadsOnlyItsChineseName`, RED (`[總覽, OVERVIEW]`) then GREEN.
  - FogShellTest's `fiveTabsInOrderWithChineseAndEnglishLabels` became `fiveTabsInOrderWithChineseLabels`.
- **M9:**
  - The hint `shell_composer_hint_disabled` is now `DSH 尚未就緒`, which is true both while dsh is loading and after an error. The suggested `DSH 啟動中…` would be wrong after an error.
  - Test: `theNotReadyHintFitsAt360dpWithALargeFont` (360 dp, font scale 1.3, NATIVE text measuring).
  - RED: with the old string the line was ellipsized (155.7 of 160 px). FogShellTest's expected text was updated.
- **M10:** the reconnecting chip is a polite live region (FogShell.kt:548). Test: `theReconnectingChipIsALiveRegion`, RED then GREEN.

### Plain-background debug flag
- `FogSwitches.plainBackground` (FogBackground.kt:72) draws only the pink ground: no glows, no grids, no clock.
- E2eSupport's `prepareApp` sets it from the instrumentation argument `plainBackground=true`.
- ci.yml has a `workflow_dispatch` input `plain_background`, passed as `-Pandroid.testInstrumentationRunnerArguments.plainBackground=…` through a step env variable, not inlined into the script.
- Test: `FogBackgroundTest.thePlainBackgroundSwitchDrawsNoGlowsAndNoGrid`. RED: one glows cache was built with the switch ignored.

### A-I5: CI e2e and perf gate (ci.yml, PerfGateTest, E2eSupport)
- The emulator runs with `ram-size: 3072M`.
- The connected step runs Gradle with `-Dorg.gradle.jvmargs=-Xmx1536m`, on that step only.
- **PerfGateTest is ADVISORY** on the CI emulator.
  - Each scenario logs `perf-gate <s>: frames=… janky=… fraction=… max=… verdict=within|over|no-verdict`.
  - The "List the instrumentation test results" step turns those lines into a markdown table in the step summary, or a "(no measurement)" row.
  - Going over the threshold, or measuring fewer than 30 frames, never fails the run.
- **What still fails the run:**
  - a crash or exception while driving the shell;
  - a shell that never shows;
  - the effect level stepping down although auto-degrade is off (a setup error).
- **Where this is stated:** the class KDoc and a ci.yml comment say that on-device numbers decide the gate later, and that E2eTest stays release-gating.
- E2e still runs at SAVER except t21 and the perf gate, as before. Its KDoc now says SAVER is the true safe mode: no blur, no 3D or offscreen grid, nothing ambient.
- **Secrets:** ci.yml prints none. The new lines echo only the perf numbers, and the logcat grep pattern is unchanged.
- The t21 KDoc line was left to lane X, as instructed.

## Screenshots (B1-I3 check)
Robolectric renders them with `@GraphicsMode(NATIVE)` and `decorView.draw(Canvas)`, at 412 dp wide and with reduced motion so the panels sit still. RenderEffect blur does not render under Robolectric, so FULL and SAVER differ here only in the grid's drawing path.

Files are in `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/final-fix-shots/`. The test that made them is saved there too as `ZzShotsTest.kt.txt`. It is not committed, because it builds lane X's OverviewState and ChatHarness, whose signatures may change in the merge.

- `overview-full.png` and `overview-saver.png`:
  - The ceiling grid shows behind the header: the vertical lines converge downward toward the centre, and the horizontal lines get closer together and fade out about 140 dp down.
  - The spend ring, the agents and heartbeat panels and the reminder panel are intact.
  - The two images are nearly identical, as intended: SAVER draws the same projection flat.
- `chat-full.png` and `chat-saver.png`: the same ceiling grid behind the chat header, with bubbles, a table and the dock unaffected.
- `background-full-xhdpi.png` and `background-saver-xhdpi.png`:
  - An empty page (agents tab, blank) at xhdpi, so the grid is clearly visible: the ceiling slab fans out from the top edge and recedes toward a centred vanishing point.
  - The floor slab sits behind the dock, as in the mockup; only its outer lines show beside the dock.
  - The three glows sit where the plan puts them.

## Deviations
1. **B1-I3:** an exact projective matrix (camera at the slab's centre, as in the mockup) instead of `graphicsLayer` with `cameraDistance/72`, which cannot place the camera there. No offscreen layer at any level, because the mask and the opacity are folded into the line colours.
2. **`Effects.perspectiveGrid`:** a sixth, non-motion field, so that SAVER can be told apart.
3. **`FogSemantics.exposeBlur`** defaults to true under Robolectric, so ChatScreenTest, in lane X's area, needs no edit.
4. **Blur cap:** at the cap the dock outranks page glass. Without this, the first-come cap removed the dock's blur on the overview.
5. **M3:** focus is parked on the scrim instead of calling `clearFocus`, which the window undid.
6. **M9 text:** `DSH 尚未就緒` rather than `DSH 啟動中…`, because it is also true after an error.
7. **Tags.kt:** one new tag, `STATUS_SPINNER`. Tags.kt is not on lane X's list, but both lanes may touch it, so it is one line next to `STATUS_PANEL`.

## Concerns
- **Merge with lane X:**
  - The `LocalIdle` type changed from Boolean to `State<Boolean>`. Any page of lane X's that reads `LocalIdle.current` must use `.value`. On main only FogShell, HudRing and FogBackground read it.
  - `strings.xml` changed one string, and FogShellTest's expected hint changed with it.
- **Not verified on a device:**
  - RenderEffect blur.
  - The real cost on the GPU, since the CI emulator is advisory and there is no device run.
  - The pixels of the projective grid under HWUI. HWUI supports perspective matrices on rect ops, and the Robolectric NATIVE render matches the mockup's geometry.
- **Fling freeze (optional in B1-I2):** not done.
