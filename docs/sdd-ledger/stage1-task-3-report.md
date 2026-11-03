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

## Fix round 1

Goal: no per-frame allocations in draw paths, an isolated animated background, panel spacing per the mockup.

### What changed
- `Glass.kt`: `GlassNode` keeps a `GlassCache` (outline, path, fill/rim/highlight brushes, stroke, both shadow paints with their blur filters), rebuilt only when size, density, direction, lifted flag or shape changes. The blur `RenderEffect` is built once per radius; the `GraphicsLayer` is created lazily on first blur; the backdrop is re-recorded only when the offset or size changes (the list refers to the live backdrop layer). Highlight fades to `White.copy(alpha = 0f)`. An `ObserverModifierNode` calls `invalidateSemantics` and `invalidateDraw` when effects or backdrop change; a `GlobalPositionAwareModifierNode` invalidates draw when the panel's window position changes (blurring only). `glow` is now `drawWithCache` with `dy`/`dx` parameters. One blur helper (`cssBlurFilter`, sigma = blur/2) is used by glass shadows, `glow`, `HudRing`, `Meter` and `Segmented`.
- `FogBackground.kt`: the three glows are built once (brush at their own centre) inside their own `graphicsLayer {}` and moved by the clock with translate/scale, so a tick redraws only that layer. The grid mask is cached per size.
- `Hud.kt`: `HudRing` and `Meter` use `drawWithCache` (dash effects, strokes, glow paint, rect, shader built once per size). `CountUpText` no longer recomposes while counting: it draws with a `TextMeasurer` from a draw-time state read, is sized for the final text, and exposes the final string as semantics text.
- `FogControls.kt`: pink glow 0/6/18, aqua 14; `FogButton` min height 48 dp; `Segmented` cells min 48 dp, `selectableGroup`, lift uses `PILL_MS` with `Spring`, drawn in its own alpha layer with cached shadow and fill.
- `FogPanel.kt`: padding start/end 18, top 16, bottom 18; heading gap 14 dp.
- `FogTheme.kt`: Material title/body/label keep the system font; only display/headline use Orbitron.

### Tests (RED to GREEN)
- New `FogDrawCacheTest` (4): background glows/grid, ring and meter caches stay at their first build across 60 clock-driven frames; glass cache and RenderEffect stay at 1 across 20 forced redraws; moving a panel makes the node invalidate its own draw and changes the backdrop shift; counting does not recompose the caller.
- `FogComponentsTest` +2: panel padding is 16/18/18/18; Material text uses the system font, display uses Orbitron.
- RED evidence: with the cache-key check disabled (`glass.cache` rebuilt every draw) two tests fail; with the position-invalidate removed `movingAPanelRedrawsItsBlurLockedToTheBackground` fails; both restored, all green. (The old code had no counters, so the original per-frame allocation could not be run against the new tests directly.)
- Focused `app.dshmobile.ui.fog.*`: 27 tests, 0 failures. Full unit suite: 655 tests, 0 failures.

### Notes
- The Robolectric harness redraws everything when the window is drawn into a bitmap, so draw counts alone do not prove invalidation; the test therefore pins the `glass.moved` counter (the node asked for the redraw) and the cache counters.
- `Meter` fill gradient now spans the full bar width and is clipped by the fill width (a fixed shader so nothing is rebuilt as it grows); the mockup's gradient spans the fill itself, so a short fill ends lighter than full colour.
