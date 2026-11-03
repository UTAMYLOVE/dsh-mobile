# Stage 1 Task 14 report: 又炫又順 (live effects, jank governor, paused animations)

Branch `lane-t14` (based on de1c788).

## What changed
- `ui/fog/JankGovernor.kt` (new): windows of 120 frames, a window with >= 12 janky frames is bad, 2 bad windows in a row step the cap down one level and restart the count; never steps up; `reset()` goes back to FULL and clears window and streak (lifetime `frames`/`janky` are kept so a screen can log its own share); `autoDegrade` test hook; `onDegrade` callback (janky, frames of the bad windows) and an internal `onCapChanged` for the controller. `effectiveLevel()`.
- `ui/fog/EffectsController.kt` (new): `level` (prefs), `effective`, `set()` (prefs, level, governor reset), `effects(reducedMotion)`. `effective` is a plain MutableStateFlow kept in step by `set` and the governor's callback (no scope needed, thread safe).
- `DshMobileApp.effectsController` (lazy). MainActivity: collects `effective` plus `reducedMotion` (re-read in onResume), `FogTheme(effects)`; `JankStats.createAndTrack(window)` feeding the governor, tracking only between onStart and onStop; each step down logs `[perf] effects capped at BALANCED after N janky of M frames`; each onStop with frames logs `[perf] frames=%d janky=%d (%.1f%%) level=%s`. `governorForTest`. `buildSettingsEnv(activity, app, onEffectLevel = app.effectsController::set)`; `SettingsEnv.onEffectLevel` added with a default `{}` (so the existing test constructor still compiles); no settings UI built (Task 13).
- `FogShell`: provides `LocalBackdrop` (real blur on for every glass surface), `LocalPageActive` (root: lifecycle >= STARTED; each page: started and it is the current tab, so the page sliding out is already still), `LocalIdle` (root `pointerInput` on the Initial pass, never consumes; 1 s tick counted in the composition clock, idle after 8 quiet seconds; state flips only at start or end of idleness).
- Perf minors: `CountUpText` caches layouts per string (`StringCache`, LRU 48); `FogDebug.count` is a no-op unless `FogDebug.enabled` (tests set it); `HudRing` now exposes `FogSemantics.Animating`.
- Blur bound: a glass surface blurs only while any part of it is inside the window after parent clipping (`boundsInWindow`, checked in `onGloballyPositioned`); otherwise, or when blur switches off, it releases its graphics layer. `BlurLayers.active` counts live layers.
- Panel rise: `glass(shape, settled)`. The rise is a graphicsLayer-only move, which does not reliably reach onGloballyPositioned, so `FogPanel` passes `settled` (derivedStateOf rise >= 1) and the flip redraws the blur at the final position. During the 0.7 s rise the blur is approximately placed while the panel is still mostly transparent.
- Deps: `androidx.metrics:metrics-performance:1.0.0`.

## Tests
- `JankGovernorTest` (9), `EffectsControllerTest` (4): the brief's cases plus an 11-janky window and the callback.
- `GlassLayersTest` (6): 30 glass panels in a scroll column hold at most 4 layers (while scrolled to top, middle, end); no layers at BALANCED; layer returned when blur goes off and when the surface leaves; debug counters off by default; StringCache; panel rise ends locked (see concerns).
- `FogShellEffectsTest` (6): only the current page animates (mid transition the outgoing page is false), backdrop provided, idle after 8 s and a touch ends it, degrading turns every glass `Blur` false, live level change flips the background `Animating` without rebuilding, lifecycle below STARTED stops the animation.
- `MainActivityTest` (+3): degrading steps the effective level and logs the cap line; effect level from the settings env applies, persists and does not restart dsh; perf summary on stop (and nothing when no frames).
- Full unit suite: see the final message (all green); `:app:assembleDebugAndroidTest` compiles. RED: tests were written before the classes, so RED was a compile failure (no separate output captured).

## Deviations
1. Log lines: ErrorLog renders `[perf] frames=...` (the tag), not `perf: frames=...`; tests assert the `[perf]` form.
2. The brief's MainActivityTest semantics cases (Blur false, Animating) moved to `FogShellEffectsTest`: a Robolectric activity has no composition until `.visible()`, and with a visible window every `idle()` renders frames (a single test took over 3 minutes). Same wiring is mirrored there (`showLive`). The MainActivity-level tests assert the controller and prefs instead.
3. "Settings tab tap on EFFECT2": that control is Task 13's, so the test calls the env's `onEffectLevel` directly.
4. `FogShell` does not itself provide `LocalEffects`; `FogTheme(effects)` in MainActivity does (FogShell tests already wrap it).
5. `LocalPageActive` is not part of the blur condition (existing tests use it false to still the background while checking blur).

## Concerns
- `aPanelThatRoseInHasItsBlurLockedToTheBackgroundAtTheEnd` also passes without the `settled` fix under Robolectric, so it guards the end state but does not prove the fix is needed on a device.
- JankStats itself is not exercised under Robolectric (frames are fed through `governorForTest`); real numbers come from the exported `[perf] frames=` lines on the S25 Ultra.
- MainActivity edits (for the Task 6 merge): imports, three fields, `setContent` block, `trackJank/startJankTracking/stopJankTracking`, onStart/onResume/onStop hooks, `settingsEnv` call. DshMobileApp: one line.
