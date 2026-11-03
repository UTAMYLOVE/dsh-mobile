# Stage 1 Task 3 report: FOGCITY design system

Status: DONE_WITH_CONCERNS

## What changed
All under `android/app/src/main/kotlin/app/dshmobile/ui/fog/` unless noted.
- `FogTokens.kt`: `FogMotion` (easings, all timings), `FogDimens`.
- `FogTheme.kt`: `FogFonts` (Orbitron wght 500/700 via FontVariation; Chakra Petch 400/600), `FogText`, `EffectLevel`, `Effects`, `effectsFor`, `systemReducedMotion`, four CompositionLocals, `FogTheme` (SakuraTheme + typography + LocalEffects).
- `Glass.kt`: `Modifier.glass` as a `Modifier.Node` (Draw + Semantics + CompositionLocalConsumer). Blurs a copy of the backdrop layer (RenderEffect blur 10dp then saturation 1.7), translated to its own window position at draw time, then white gradient (+.16 when no blur), lit corner, 1.2dp rim, two shadows. Also `glow`, `softShadow`, `cssLinear` helpers.
- `FogBackground.kt`: `Backdrop`, `rememberBackdrop`, `FogBackground` (records itself into a GraphicsLayer; three radial glows, two perspective grids), `FogSemantics`, `rememberFogClock` (one clock, advances only while running, x0.25 when idle).
- `FogPanel.kt`, `Hud.kt` (`HudRing`, `Meter`, `CountUpText`), `FogControls.kt` (`FogButton`, `Segmented`), `UiPrefs.kt`.
- `res/font/orbitron.ttf`, `chakra_petch_regular.ttf`, `chakra_petch_semibold.ttf`; `assets/licenses/fonts-OFL.txt` (source line + two OFL.txt back to back).
- Tests in `src/test/.../ui/fog/`: FogTokensTest (3), EffectsTest (4), FontsTest (2), FogComponentsTest (10), UiPrefsTest (2).

## Fonts
Downloaded from the pinned google/fonts commit into an empty scratch dir; all five sha256 values (3 TTF, 2 OFL.txt) matched Global Constraints before copying. Sizes match too.

## TDD
RED: first run failed to compile (`Unresolved reference 'Effects'`, `effectsFor`, `EffectLevel`, ...). GREEN: `--tests 'app.dshmobile.ui.fog.*'` 21 tests, 0 failures, empty system-err.
Full unit suite: 649 tests, 0 failures.

## Deviations
- Brief lists 5 FogComponentsTest cases; the three-way glass and four-way background cases are split into separate tests (10 total) so each condition has its own name.
- Added UiPrefsTest `anUnknownStoredLevelFallsBackToFull`.
- Where the mockup CSS differs from the brief (150deg glass, .35 rim, 62deg grid, 46px cells, blur-filter blobs) the brief/Global Constraints values were used.
- Grid fade: opaque at 10% from the near edge, transparent at 85% toward the horizon (brief says "10%→85%" without direction).
- Blob drift uses CSS `alternate` semantics: 18/22/26 s is one way, so a full round trip is twice that.
- FogPanel's rise animation is gated on `depthTransitions` (no separate flag exists).
- Radial highlight is a circular gradient scaled to 120% x 70% (Compose has no elliptical radial).
- `Modifier.glass` degrades silently (no blur) if creating the RenderEffect throws.

## Concerns
- Visual fidelity (glass blur, grid perspective, glows, shadows via BlurMaskFilter) is not checked on a device or screenshot; Robolectric only exercises composition, semantics and draw without error. Needs an emulator look in T15.
- `LocalBackdrop` and `FogBackground` are only wired by Task 14's FogShell; until then glass is un-blurred in the app.
- Orbitron variable weight relies on API 34+ variation support (minSdk 34, fine).
