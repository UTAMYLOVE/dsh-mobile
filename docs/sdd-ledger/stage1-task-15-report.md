# Stage 1 — Task 15 report: e2e drives the native UI, CI jank gate

Lane `lane-t15` in worktree `/home/user/wt-t1`, branched from `main` at `0883c19`. Nothing pushed.

Commits:

| Commit | What |
|---|---|
| `66afcaa` | `feat:` the page-switch sweep line (the T4 deferral this task owns), TDD |
| `6871fe3` | `test:` FakeModel, E2eSupport, E2eTest t01–t24, PerfGateTest, uiautomator dependency (Step 7) |
| `2ef4ff4` | `ci:` emulator GPU mode, job timeout, perf numbers in the summary, runner memory on failure (separate, as asked) |
| (this file) | `docs:` this report |

Status: the code compiles and the unit suite is green. **Step 5 (CI green) has not run yet.** The e2e code has never run on an emulator; see "To verify on CI" below.

## 1. CI hang: the evidence and my hypotheses

### Evidence (run 84, commit 1569b58, with the controller's new diagnostics)

I downloaded the run's `e2e-reports` artifact with `gh api .../artifacts/13232495742/zip`. It contains `logcat.txt`, 54 256 lines.

- `12:47:35` t01 started. `@Before prepareApp()` installs the rootfs, which took about 3 minutes.
- `12:50:47` `ActivityScenario` started `MainActivity`. The activity reached RESUMED at `12:50:48.05`, and DshService and proot started at `12:50:48.4`.
- `12:50:49.024` our app's RenderThread (pid 5043, tid 6945) set up its first buffer (`OpenGLRenderer: Failed to initialize 101010-2 format`, `Gralloc4: mapper 4.x is not supported`).
- `12:50:49.227` is the **last line in logcat**. The guest never logged again.
- `12:53:21` Gradle reported `adb: device offline` and then `device 'emulator-5554' not found`. The test was recorded as failed with status -1 (346 s), followed by `testRunFailed: Expected 7 tests, received 0`.
- Between `Running tests` and `device offline`, the job log has no output from the emulator process. A crash handler would normally print something there.
- The same configuration passed on `ad4996a` (run 70, WebView shell): t1 took 210 s. The emulator settings, rootfs and dsh were unchanged in between. The difference is the native shell's rendering.
- Runs 77 and 80 had the same shape (t01 at about 270 s, then the device was gone).

### Hypotheses

- **(c) The emulator's renderer: confirmed as the trigger.** The guest went silent within 0.2 s of the native shell's first frame. With `-gpu swiftshader_indirect` the host renders through the legacy "Android Emulator OpenGL ES Translator (Google SwiftShader)" (from the job log), so a GL problem takes down the whole emulator rather than just the app.
  - At FULL the first frame contains everything that is new compared with the WebView:
    - RenderEffect blur plus saturation for every glass surface (dock, overview panels);
    - an offscreen-composited page stage (`fadeAbove`);
    - two offscreen perspective grids at `rotationX ±68°`, 2.4× the screen width;
    - BlurMaskFilter glows and shadows.
  - I cannot tell from here which one breaks the translator. RenderEffect is the main suspect, because it is new and shader-heavy.
  - Fixes, two changes together:
    1. **Test setup:** `E2e.prepareApp()` sets `effectsController` to SAVER before any activity starts (`E2eSupport.kt:72`), so there is no RenderEffect blur. t21 and PerfGate ask for FULL themselves, as the brief requires, and restore the level afterwards. Production defaults are unchanged.
    2. **CI:** `-gpu swangle_indirect` (SwiftShader Vulkan behind ANGLE, the emulator's more conformant software path) replaces the legacy translator (commit `2ef4ff4`). This is the allowed deviation, made on the evidence above.
  - If t01 now passes and t21 or PerfGate (FULL) still kill the emulator, the culprit is RenderEffect on software GL. At that point it needs a ruling: the gate requires FULL.
- **(d) Host memory: secondary and not excluded.**
  - This is a private repo, so the job runs on a standard private runner (2 vCPU / 7 GB). The guest has 4 GB of RAM, and the Gradle JVM (`-Xmx2g`) runs alongside it.
  - A host OOM kill would also leave no output, which fits the silent death.
  - But the death lands exactly on the first frame, and the same memory configuration passed with the WebView. So I did not change `ram-size`.
  - The failure step now prints `free -m` and the host's dmesg OOM lines, so a host OOM kill will show up if it happens.
  - The guest's lmkd lines at 12:50:06/11 are lmkd re-initialising, not kills.
- **(b) A WebView-era signal: ruled out.** The old t01 waited for `MainActivity.uiStateForTest == Ready`, which is the native shell's signal. It never got there because the device died first.
- **(a) Compose idling under infinite animations: not the cause of this hang, but real hazards for the rewrite.**
  - The old t01 used no Compose rule.
  - CI's `disable-animations` makes the shell's reduced-motion check true, so ambient and spin are off.
  - The code already uses `withInfiniteAnimationFrameNanos`, which the Compose test environment cancels.
  - While reading the test framework I found two problems that would have broken the rewrite, and designed around them:
    1. **`createEmptyComposeRule()` has a 60 s wall-clock limit.** In ui-test 1.10.2 the junit4 rule runs each test body inside kotlinx `runTest(timeout = 60 s)`. A blocking body that runs longer fails after it completes, with `UncompletedCoroutinesError` wrapped as `AndroidComposeUiTestTimeoutException`. I checked this with a throwaway JVM probe: `runTest(1.seconds) { Thread.sleep(2500) }` fails. Every e2e test that starts dsh takes longer than 60 s. Fix: `E2eCompose` (`E2eSupport.kt:194`) is a JUnit rule over the public `AndroidComposeUiTestEnvironment(effectContext, runTestContext, testTimeout = 20.minutes)`.
    2. **The test clock drives the screen.** While a Compose environment is active, the shell's recomposer and its effect coroutines advance only when the test waits through Compose (`waitUntil` and `fetchSemanticsNodes` pump frames). So every wait on something the screen shows goes through `ComposeUiTest.await` (`waitUntil`). The `Thread.sleep` polling `E2e.waitFor` is used only for dsh or app state.
  - **`waitForIdleSync` starvation.** With the shell animating every frame on a renderer slower than vsync, the main looper may never go idle. Two places waited for an idle looper, and both are now avoided:
    1. `ActivityScenario.onActivity` (inside the old `awaitShellReady`) calls `waitForIdleSync`. The new helpers reach the activity through `ActivityLifecycleMonitorRegistry` with `runOnMainSync`, which does not wait for idle.
    2. `ActivityScenario.launch` waits for an idle looper. PerfGate starts the shell with `startActivity(NEW_TASK | CLEAR_TASK)` instead.

## 2. What I built

### FakeModel (`androidTest/.../FakeModel.kt`)

- `Kind { DREAM, SEARCH_BACKEND, TOOL_USE, FOLLOW_UP, HELLO, SLOW, LONG, SEARCH, APPROVE, ASK, SUB, CHILD, FILE, OTHER }`.
- `Seen(path, kind, apiKeyIsTheVaultKey, model, body)`. `toString()` leaves out `body`, so failure logs never print request contents. The key is compared and never printed.
- `classify` (`FakeModel.kt:254`) checks, in order:
  1. the dream marker;
  2. `Perform a web search for the query:` (confirmed against `dsh-web-search-deepseek/lib/index.js`: a non-streaming request that expects `web_search_tool_result` plus cited text);
  3. a last message that carries a `tool_result` → FOLLOW_UP, answered by tool_use id prefix (`toolu_search_`, `_approve_`, `_ask_`, `_sub_`, `_e2e_`);
  4. `E2E_CHILD`, checked before the tool-list test in case subagents get fewer tools;
  5. no `memory_write` tool → OTHER (title generation);
  6. a keyword in the last user message that is not a tool result.
- Every SSE reply ends with `usage {input_tokens: 20000, output_tokens: 1000}`.
- HELLO: a thinking block with `signature_delta`, then three text pieces. SLOW: 40 throttled pieces (about 25 s). LONG: 60 sections, each with a 2-row table and a code block.

### E2eSupport (`androidTest/.../E2eSupport.kt`)

- `E2e`:
  - `prepareApp(effects = SAVER)` and `useEffects`;
  - `launchShell(intent?)`, then `awaitShellReady()`;
  - `mainActivity()` / `shellActivity()` / `nav { }`, all through `runOnMainSync` and the lifecycle monitor;
  - `withAnimationsOn` (`settings put global` for the 3 scales, restored in `finally`);
  - `shell(cmd)`, `webViewsIn`, `errorLogTail`, `waitFor`;
  - `DumpErrorLogOnFailure`, which logs request kinds but not bodies.
- `E2eCompose`: see 1(a)(1).
- Extensions on `ComposeUiTest`:
  - finding and reading: `nodes`, `has`, `hasTagPrefix`, `texts`, `textsOf` (merged node when there is one, else the node itself);
  - waiting: `await`, `awaitTag`, `awaitNoTag`, `awaitText`;
  - acting: `tap` (waits for enabled, scrolls into view), `type`, `newConversation`, `send`;
  - layout: `assertNoFloatingOverlap` (clickable nodes under DOCK or PROMPT_SHEET, plus the reconnect chip and attachment chips, are pairwise disjoint; a node and its own descendant are skipped).

### E2eTest (`androidTest/.../E2eTest.kt`)

- Two-digit names, name order. Every chat test opens its own conversation and creates the data it checks. Tags come from `Tags.kt`. Expected Chinese strings are read from `R.string`, never typed as literals.
- **t01–t07** are carried over. Additions:
  - t01: the five TABs and SHELL_BACKGROUND.
  - t04: the overview's OVERVIEW_REMINDERS shows `E2E-REMIND`.
  - t05: the settings update check returns UpToDate on a verified current version, and npm gets no `view` and no `install`.
- **t10–t24** follow the brief. Notes:
  - t11 also does the TalkBack check (below).
  - t12 switches tabs through `nav.select`, because the modal card hides the dock from semantics.
  - t23 records the card count on every frame of the reconnect and requires exactly `{1}`.
  - t24 sets `wm density` so the emulator's 320 px-wide screen becomes about 412 dp, and resets it afterwards. For each of the five tabs, plus the attach menu, two chips and the approval card, it checks: no floating overlap; the last page control, after scrolling to the end, ends at or above the dock's top; no vertical text (at least 2 characters, at least 2 lines, and narrower than 2 em, measured with `GetTextLayoutResult`). Outside 360..480 dp it only logs (brief).

### PerfGateTest (`androidTest/.../PerfGateTest.kt`)

- Uses UiAutomator `By.res(tag)` and its own `JankStats.createAndTrack` counter. There is no Compose rule.
- Each run is wrapped in `withAnimationsOn`, with `useEffects(FULL)` and `governor.autoDegrade = false`. The previous level and auto-degrade are restored in `@After`.
- Each scenario: warm up, reset the counter, measure, then assert `effective == FULL`, at least 30 frames, and `janky / frames <= MAX`. The three scenarios:
  1. tabs: overview → chat → agents → settings → overview, three times;
  2. E2E_LONG: up and down five times;
  3. E2E_SLOW: 4 s of warm-up, then 10 s measured.
- `MAX_JANK_TABS`, `MAX_JANK_SCROLL` and `MAX_JANK_STREAM` are all `0.10f`. Results are logged as `perf-gate <name>: frames= janky= fraction=`, and the CI summary now lists them.

### Sweep line (`main/.../ui/shell/FogShell.kt:372`, `Tags.PAGE_SWEEP`)

- `PageSweep(nav.tab)` sits in the stage, above the pages and under the top fade.
- On each tab change (not the first show) it draws one 2 dp white line with a white α.9 / 18 dp glow and a pink α.45 / 40 dp glow, skewed −12°. It crosses from −10 dp to the right edge in 620 ms with ease-out, fading in over the first 15 % and out over the rest. This follows the plan's motion constraint and the mockup's `.sweep`.
- It runs only when `Effects.depthTransitions` is on (FULL and BALANCED). It is off at SAVER and with reduced motion, and it is removed immediately if the level changes mid-sweep.
- The progress is read during drawing, so frames redraw without recomposing.
- RED → GREEN with `FogShellSweepTest` (5 tests):
  - RED: 3 failures, `expected:<1> but was:<0>`, in `aTabChangeSweepsOneLineAcrossOnceAtFull`, `balancedStillSweeps` and `switchingToSaverMidSweepRemovesTheLine`.
  - GREEN: 5/5.

## 3. Deviations

1. **The color of the sweep line.** The controller's note said "#A5DEE4". The plan's Global Constraints (binding) and the mockup say a white line with white and pink glows, so I followed the plan. Changing it is a one-line edit if aqua is wanted.
2. **No `createEmptyComposeRule()`; a custom `E2eCompose` rule instead.** The junit4 rule's 60 s cap would fail every long e2e test. Because of that, the helpers are extensions on `ComposeUiTest` instead of `ComposeTestRule`, with the same names and behaviour. `launchShell` takes an optional intent, for t22.
3. **PerfGate starts the shell without ActivityScenario.** Its launch calls `waitForIdleSync`, which a slow renderer animating every frame may never satisfy. The brief said "ActivityScenario + UiAutomator".
4. **E2E_CHILD takes about 75 s, not 30 s.** t19 gives the stop 30 s. A child that finishes on its own in 30 s would make that check meaningless.
5. **t05's added check** runs the same policy as `SettingsEnvFactory.checkUpdate` (`VerifiedUpdatePolicy.target(current) ?: UpToDate`, else `updateWithAutoRollback(target)`) on a `DshVersions` with a recording GuestCommand. The real settings environment is built on `app.dshVersions`, which a test cannot swap.
6. **t24 sets `wm density`** to get about 412 dp on the 320×640 emulator, and resets it afterwards. The CI device is 320 px wide (logcat `Rect(0, 0 - 320, 640)`), so without this the checks would only log.
7. **CI workflow changes** (separate commit `2ef4ff4`; the brief says not to change the workflow):
   - `-gpu swangle_indirect` (the allowed emulator deviation; evidence in §1);
   - `timeout-minutes` 60 → 90 (t01–t24 plus the gate on a software GPU; a timed-out job reports nothing);
   - perf-gate and t24 lines in the step summary;
   - `free -m` and dmesg OOM lines on failure.
   - Note: `ci.yml` is part of the rootfs cache key, so the next run rebuilds both rootfs images (about 20 min).
8. **Page-control clearance in t24 is checked against the dock's top**, as the brief says, not the dock's top minus the 24 dp fade that the Robolectric layout test uses.

## 4. Rulings owned here

- **Sweep line:** implemented (above).
- **TalkBack on chat links:**
  - Compose 1.10.2's `TextLinkScope` gives every link a `combinedClickable` node with `LinkTestMarker` (checked in the bytecode).
  - t11 asserts that the search answer's bubble has at least one clickable descendant in the unmerged tree.
  - **Still to verify on the emulator** (t11); it is a semantics check, not a TalkBack run.
- **A 500-row table is one lazy item:** not changed.
  - PerfGate (2) uses E2E_LONG, which has 60 sections with 2-row tables, as the brief says, so the gate does not cover a huge table.
  - Risk: a 500-row table is measured and laid out as one lazy item, which costs a long frame when it scrolls in. It needs either a dedicated perf scenario or splitting tables into row chunks (stage-2 candidate).

## 5. Verification run here

- Full unit suite (`:app:testDebugUnitTest --max-workers=2`): **979 tests, 0 failures, 0 errors** (974 before, plus the 5 sweep tests).
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: BUILD SUCCESSFUL, with no Kotlin warnings in androidTest.
- Step 6: `grep -rn "android.webkit.WebView" android/app/src/main` returns nothing. Only `android.webkit.MimeTypeMap` remains, in `MainActivity` and `Exporter`.
- Red lines:
  - No permission changes. The manifest is untouched.
  - No dsh source touched.
  - No key or body in any log (`Seen.toString`, failure dump).
  - No telemetry.
  - `withAnimationsOn` and `wm density` are always restored in `finally`.

## 6. To verify on CI (Step 5)

1. **Does t01 get past the first frame?** The fix is SAVER plus SwANGLE.
   - If the emulator still dies there, the next suspects are BlurMaskFilter glows and the offscreen 3D grid on a software renderer. The next step would be a CI-only bisect, for example `-gpu guest` if the image supports it.
   - If SwANGLE itself fails to boot, revert that line.
2. **Do t21 and PerfGate (FULL) survive?** If they kill the emulator while t01–t20 pass, RenderEffect on the software renderer is the cause, and the FULL-only gate needs a ruling (for example, run the gate on a GPU runner, or gate at BALANCED on CI).
3. **The PerfGate thresholds.** Read the three `perf-gate` lines in the step summary. Then set each `MAX_JANK_*` to clamp(measured × 1.5, 0.02, 0.10), rounded up to 0.01, and write the measured value into the KDoc (second push, Step 3). A measured value over 0.10 is reported, not loosened.
4. **Assumptions about dsh 0.2.1-rc.5** that only the emulator can confirm:
   - thinking blocks from the DeepSeek route reach the UI (t10);
   - `web_search` with `{"queries":[...]}` (t11);
   - the `write` tool with `danger-full-access` to `/tmp/e2e-outside.txt` succeeds after approval, so the card says 完成 (t12);
   - the model id `deepseek-v4-pro` reaches the model unchanged (t17);
   - a subagent child's request carries `E2E_CHILD` in its last user text (t19);
   - the file attachment shows up in the request body by name or content (t16).
5. **The wall-clock budget.** I estimate 35–45 min for the test step; the job is now allowed 90.
