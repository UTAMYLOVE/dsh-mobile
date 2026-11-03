# Follow-up 1 report: workspace, hidden app-managed settings, FOGCITY, lag

Status: DONE_WITH_CONCERNS. All four brief items are done. CI is green on every code commit, e2e included (see the CI section).
The concerns are the real-device checks at the end.

Base: 878581b (= main ae06129 + the brief). Branch `claude/dsh-mobile-cloud-handoff-vfhvdh`.

| Commit | What | CI run | Result |
|---|---|---|---|
| 572e5a5 | item 1: workspace auto-registration | (ran together with cf74cd0) | — |
| cf74cd0 | item 4: lag | 41586316420 | all green, e2e 8/8. The early release v0.1.1+2 was cut from this commit (controller). |
| fab94fc | item 1, root cause: picker client row + `documentsDirectory` | 41587938331 | all green, e2e 9/9 |
| ad4996a | items 2 + 3: hidden managed settings + FOGCITY | 41589224274 | all green, e2e 10/10 |

Order: the controller reprioritised mid-task. Items 1 and 4 went first in their own commits and got CI green before items 2 and 3
were committed. The controller's recon then found the real root cause of the picker problem (`fab94fc`).

## Item 1: a workspace can be chosen

### What changed
- **Root cause** (from the controller's recon, verified here). `config/DshPatch.kt:74-89` `directoryPicker()` disabled the `auto`
  picker but inserted only the host half, `@deepseek-ai/dsh-host-directory-picker-browse`. The page therefore never loaded the
  browser half, `@deepseek-ai/dsh-client-ui-directory-picker-browse`: "选择工作区" did nothing and "添加工作区…" was missing.
  The patch now inserts both halves (row id `directory-picker-browse-ui` for the browser half).
- `config/DshPatch.kt:90-99` `workspaceController()` sets `workspace-controller` to
  `{documentsDirectory: "/root/workspace", documentsLookupTimeoutMs: 10000}`. Without it, dsh's first-use default workspace looks
  up Documents with `xdg-user-dir`, which the rootfs does not have (`spawn xdg-user-dir ENOENT`, per the recon). The row config
  replaces the whole row, so both keys are written; the second is dsh's default.
- **Backstop: auto-registration (the brief's mechanism).** `runtime/WorkspaceRegistrar.kt` (new) runs after every healthy start:
  - It logs in with `DshClient` and sends `workspace/create {"request":{"path":"/root/workspace"}}`.
  - It returns REGISTERED, ALREADY_LISTED or FAILED and never throws (only cancellation passes through). Timeout 15 s.
  - A failure is one ErrorLog line, tag `workspace`, carrying the HTTP status, dsh's error code (validated against
    `[A-Za-z0-9/_.-]{1,80}`) or the exception class. It never logs the URL, the token or dsh's error message.
- `service/DshController.kt:88-93,208-214`: new `afterHealthy: suspend (LaunchUrl) -> Unit`. It runs once per start, after the
  health check and before the run is published, so the WebView's first load already sees the workspace. Anything it throws is
  logged (`after-start step failed (<class>)`) and ignored.
- `DshMobileApp.kt:120-122,151-152` wires it with the loopback `OkHttpClient` shared with `HealthCheck`.

### Is the backstop still needed?
With both patch rows in place, dsh's own picker works: the user can add `/root/workspace` themselves. I kept the registration:
- it costs one loopback RPC per start;
- the user never has to find the folder;
- when it runs first, dsh creates no `deepseek-harness/default-workspace` at all (see scratch evidence 3).

So it is no longer required for usability, but it gives the cleaner state. It could be dropped later without breaking anything.

### Scratch dsh evidence (dsh 0.2.1-rc.5)
The scratch dsh was the existing install of `npm i @deepseek-ai/dsh@0.2.1-rc.5` (the version in `rootfs/versions.env`). It ran
with the same patch rows the app writes: providers pointed at a dead gateway, the agent rows, privacy, picker, plugins. Login
was with the launch URL. RPC is `POST /api/<ns>/<method>`. Scratchpad paths are shortened to `<FU1>` below.

1. **Listing method.** dsh has none that answers over HTTP. `lib/typert.remote-client.d.ts` of
   `dsh-api-workspace-controller` lists only `archiveSession, create, delete, follow (stream), initializeDefault, insertBefore,
   insertSessionBefore, pinSession, rename, unarchiveSession, unpinSession`. The list exists only as the `workspace/follow`
   stream baseline (`WorkspaceBaseline.items`). Over HTTP:
   ```
   workspace/follow {} -> HTTP 200 {"result":{"ok":false,"error":{"code":"gateway/signature-invalid","message":"typert gateway: workspace/follow: stream Remote methods must be opened through the stream carrier"}}}
   ```
2. **`workspace/create` is the check.** It is idempotent by path (type doc: "Created or previously registered Workspace"):
   ```
   workspace/create {"request":{"path":"<FU1>/run/workspace"}} (1st) -> {"ok":true,"value":{"workspace":{"workspaceId":"49b9645e-…","path":"<FU1>/run/workspace","title":"workspace","sessionIds":[],…},"created":true}}
   workspace/create (same path, 2nd)                               -> same workspaceId, "created":false
   $DSH_HOME/storages/workspace.json -> global.workspaceIds = ["49b9645e-…"], tables.workspaces = { one entry, that path }
   ```
3. **Order with the default workspace** (`documentsDirectory` set, fresh `$DSH_HOME`):
   - UI loaded with no registration: the UI creates `<FU1>/run/workspace/deepseek-harness/default-workspace` (title
     `default-workspace`). The default lands *inside* the documents directory, not at it.
   - `workspace/create` sent first, then the UI loaded: the list holds only `<FU1>/run/workspace`. No default is created
     (`initializeDefault` returns ok with no value).
4. **Picker in Chromium (Playwright, zh-CN).**
   - With only the host row: the page's plugin bundles do not contain `dsh-client-ui-directory-picker-browse`. The e2e probe
     returns `false`.
   - With both rows: the probe returns `true`. "选择工作区" shows "添加工作区…", which opens "选择工作区目录" (主目录 / 编辑路径 /
     新建文件夹 / 显示隐藏文件 / 取消 / 打开). `directoryPicker/list` is called. After typing the path and pressing 打开:
     `workspace/create -> {"ok":true,…,"path":"<FU1>/run/workspace","title":"workspace"…}`, and the selector shows "workspace".

### Tests
- `WorkspaceRegistrarTest` (9, MockWebServer through `FakeDshServer`, which gained an `rpc` hook):
  - the exact envelope and path;
  - created → REGISTERED and logged; already listed → ALREADY_LISTED, not logged, one call;
  - dsh refusal → only the error code is logged (not the message); HTTP 500; non-JSON answer;
  - failed login → no RPC sent; a hanging dsh times out in under 3 s; an unreachable dsh;
  - no `127.0.0.1` or `token` anywhere in the log.
- `DshControllerTest` +3: the ready step runs once, after the health check and before publish (isRunning was false); a throwing
  step is logged and the start still succeeds; no step when the health check fails.
- `DshPatchTest`: `swapsInBothHalvesOfTheBrowsingDirectoryPicker`, `dshsDocumentsDirectoryIsTheAppWorkspace`, and the
  section-order test extended.
- e2e:
  - `t6_dshListsTheAppWorkspaceOnceAfterEveryStart`: runs after t5, which restarted dsh twice. `/root/workspace` is in dsh's
    `storages/workspace.json` exactly once, and a further `workspace/create` answers `created:false`.
  - `t8_dshsFolderPickerIsLoadedAndSeesTheWorkspace`: the page loaded the picker's browser half, and `directoryPicker/list`
    of `/root` lists `workspace`.
- RED → GREEN:
  - Registrar and controller: compile RED (`Unresolved reference 'WorkspaceRegistrar'`, `No parameter with name
    'afterHealthy'`), then GREEN (WorkspaceRegistrarTest 9/9, DshControllerTest 43/43).
  - DshPatch: behavioural RED, 12 tests with 3 failing (the two new ones and the order test, `AssertionError`), then GREEN 12/12.
  - Picker probe: RED `false` / GREEN `true` on the scratch dsh, as above.

## Item 2: app-managed settings hidden in dsh

### What changed
`plugins/packages/ui/src/managed.ts` (new); `src/index.ts` adds two rows: `style` `MANAGED_CSS` and `script` `MANAGED_SCRIPT`.
- **Script.** `watchManagedSettings` is serialized with `Function.toString`, so it is self-contained.
  - It marks each block found by `MANAGED_RULES` with `data-dshm-managed="note"` or `"gone"`.
  - It runs once at load, then from a `MutationObserver` (childList, subtree) coalesced to at most one pass per animation frame.
  - Every rule and the whole setup sit in try/catch. A missing block means nothing happens.
- **Style.**
  - `note`: the block's children get `display:none !important` and `pointer-events:none`. A `::before` shows
    「這些設定由 DSH Mobile 管理，請點右上角齒輪」, CSS-escaped so the sheet stays ASCII, in `var(--dsw-alias-label-secondary)`, so it
    reads in both themes.
  - `gone`: the block itself gets `display:none`.

### Selector table (dsh 0.2.1-rc.5, rendered DOM in Chromium; settings inventory from `--dump-config` and the page)
| Patch row(s) the app owns | Where dsh shows it | Anchor (stable hook) | Block hidden | Hook kind |
|---|---|---|---|---|
| `subagent` (maxDepth, maxActiveSubagents) | 插件 → 子智能体 → 运行限制 | `#plugin-config-subagent-depth`, `#plugin-config-subagent-capacity` | `closest('section')`: the 运行限制 section (the model-choice section and 保存 stay) | id |
| `web-search-deepseek` (whole row: key, endpoint = gateway URL with the session token, budget, new-API switch) | 插件 → 网页搜索 | `[data-plugin-item-detail="web-search"] [data-plugin-config]` | that section | data-* |
| Agent Teams bundle in `profiles/web/package.json` + `agent-team` | 插件 → 实验性插件 → 智能体团队 card (switch and detail page) | `li[data-plugin-package="@deepseek-ai/dsh-experimental-agent-team-profile"]`; also `[data-plugin-item-detail="agent-team"] [data-plugin-config]` | the card (note in its place) | data-* |
| `llm-deepseek`, `llm-pi-ai` (baseURL, apiKeyEnv, model lists), key input (`credentials/set` 403) | 设置 → 模型 (DeepSeek 编辑: API 密钥, API 地址, 模型 ID; 添加模型提供商) | `button[aria-label$="(deepseek-official)"]` (provider id inside the localized label) | its ancestor that is a direct child of `[data-slot="settings.section"]`: the whole models section | provider id + data-slot |
| `session-log-deepseek` | 设置 → 通用设置 → "在使用官方模型 API 时上传 Session Log" | `[role="switch"][aria-label*="Session Log"]` | its ancestor that is a direct child of `[data-slot="settings.general.item"]` (the row) | **text** (aria-label; "Session Log" is in both locales). dsh gives this row no id. |
| `account/startSignIn` (403 by the key guard) | 账号菜单 → 登录 | `[role="menuitem"]` whose text is 登录 / 登入 / Sign in / Log in | the menu item, `gone` | **text** (last resort) |
| `agent-default-model` | composer model picker (per conversation, `session.selectModel`) | — | not hidden (deviation 4) | — |
| `session-telemetry-otel`, `plugin-package-inventory-deepseek` | no UI surface found (no client bundle references either namespace) | — | — | — |

Scratch check: the plugin was built and loaded through the patch, and Playwright ran in zh-CN and en-US:
- the script is in `<head>`; no `pageerror`;
- `#plugin-config-subagent-depth` exists but is not visible, while the model-choice switch is visible;
- the Session Log switch, `button[aria-label$="(deepseek-official)"]`, the Agent Teams card switch, the web-search endpoint and
  the 登录 item are all not visible;
- the note shows in their place (screenshots `fu1/fog-subagent.png`, `f2-models.png`, `f3-plugins.png` in the scratchpad).

### Tests
- vitest `managed.test.ts` (13, jsdom; DOM fixtures shaped like the real ones above):
  - an empty page: no throw, nothing marked; no MutationObserver plus a throwing `querySelectorAll`: no throw;
  - each row of the table, positive and negative (e.g. only the limits section, never the model choice; only the team card);
  - blocks rendered later and re-rendered (replaced) are marked;
  - every selector is valid; the CSS shape and the decoded note;
  - jsdom layout: the depth input computes `display:none` while the section stays.
- `ui.test.ts` checks the three injected rows exactly.
- RED: with `managed.ts` absent, both test files fail to load (`Failed to load url ../src/…`). GREEN: 13/13.
- e2e `t7_theSubagentDepthFieldIsHiddenInDshsSettings`, in the real WebView on the emulator:
  - opens 插件 by the sidebar button's aria-label (插件 or Plugins), then the `li[data-plugin-item="subagent"]` card;
  - asserts `#plugin-config-subagent-depth` exists and has no client rects, and that the note's `::before` contains
    "DSH Mobile".
  - The same three JS snippets were run in Chromium against the scratch dsh in zh-CN and en-US: `hidden`.

## Item 3: FOGCITY

### What changed
- `plugins/packages/ui/src/fogcity.ts` (new) replaces `sakura.ts`:
  - **Palette.** Only `#FEDFE1 #D7C4BB #DC9FB4 #FFFFFF #A5DEE4 #9E7A7A`, plus `rgba()` of those for transparency. Text is
    `#5E4445` / `#7D5F60` (`--dsw-alias-label-*`, `brand-primary`, `brand-text`, `link`). Aqua is used only in the background
    glow, the panel edge and the checked switch fill/glow.
  - **Tokens.** Light tokens sit on `html body:not([data-ds-dark-theme])`: the deepseek scale, `bg-*` (layers translucent white),
    `border-l1..4`, `--dsw-specific-*` (sidebar, input, bubble, selector, tip), primary button = pink with `#5E4445` text, focus
    ring.
  - **Dark mode.** One rule, `html body[data-ds-dark-theme]`: only scale 400/450, the focus ring and the selection highlight.
  - **Background.** The body is `#FEDFE1` with three static `radial-gradient` glows (pink, aqua, white). There is no animation
    and no fixed attachment.
  - **Shapes.** `--dsw-radius-lg: 22px` (panels), `--dsw-radius-panel: 30px` and `[role="dialog"]` 30px (pop-up cards),
    xs..xl 10–26px. Buttons, inputs and selects are `999px` (pill), textarea 22px.
  - **Glass.** The composer card and dialogs get a translucent white gradient and a soft rose-brown shadow. The composer and the
    settings modal get a 1px white → pink → aqua edge through a masked `::after`. `backdrop-filter` appears exactly twice: the
    composer (`[data-composer-card]`) and pop-ups (`[role="dialog"], [role="menu"], [role="listbox"]`).
  - **Motion.** Button transitions are 0.12–0.15 s, plus a 0.97 press scale; the dsh duration tokens are capped at 0.2 s.
    `prefers-reduced-motion: reduce` sets `animation: none !important; transition: none !important` on everything.
  - **Fonts.**
    - Orbitron 500/700 for h1–h3 and `--dsw-font-family-brand`.
    - Chakra Petch 400/600 with `tabular-nums` for buttons, inputs, labels, kbd and time.
    - Body and chat text keep dsh's system stack (`--dsw-font-family` untouched).
    - Latin-subset WOFF2 files from `@fontsource/orbitron@5.3.0` and `@fontsource/chakra-petch@5.3.0` (OFL-1.1) are embedded as
      data URIs in `src/fonts.ts`, with `unicode-range` set to Latin.
    - The bundle `dist/index.js` is 54 KB (limit 300 KB).
  - Sources, sha256 and licences are in `plugins/packages/ui/README.md`.
- Compose:
  - `ui/theme/SakuraTheme.kt`: the same palette (`Sakura.Background/MilkTea/Primary/White/Aqua/RoseBrown/Text/TextSecondary`).
    The scheme has primary `#DC9FB4` with `onPrimary #5E4445`, background and surface `#FEDFE1`, and text `#5E4445`/`#7D5F60`.
    Every container and surface-container is a palette colour, with no default lavender. Shapes are 12/16/22/30/30 dp.
    Material 3 buttons are already capsules. Error stays Material red.
  - Titles in Settings and Onboarding use the text colour, not pink (`SettingsScreen.kt`, `OnboardingScreen.kt`).
  - The gear button is translucent white with `#5E4445` and a pink 1dp ring (`MainScreen.kt`).
  - Window, WebView and root backgrounds are `#FEDFE1` (`colors.xml`, `MainActivity.kt:218,253`).
- Expectations changed from `rgb(255, 245, 248)` to `rgb(254, 223, 225)`: `E2eSupport.FOGCITY` / `awaitDshInFogcity`, e2e `t1`
  renamed, and the instrumentation `ui/MainActivityTest.showsTheDshPageInFogcity`.

### Tests
- vitest `ui.test.ts` (24):
  - **Palette:** the palette is exact; every hex is in the palette (base64 excluded); every `rgb()/rgba()` uses palette
    channels; no colour keywords or other colour functions.
  - **Background and text:** the light body is `#FEDFE1` with radial glows and no background in dark rules; label tokens are
    the two rose browns; aqua is never a text value (`color`, `--dsw-alias-label-*`, link, brand); the dark rule holds only
    accents.
  - **Motion:** no `infinite`, no `@keyframes`, every `animation` is `none`; every transition is ≤ 200 ms; the reduced-motion
    block is present; `backdrop-filter` count is ≤ 2, on the composer and dialog.
  - **Shapes:** the masked edge gradient runs in the order white → pink → aqua; 22/30 px radii and 999 px pills; no zero radius.
  - **Fonts:** 4 `@font-face` rules, each a woff2 data URI with the Latin range; no external URL or `@import`; Orbitron on
    titles, Chakra Petch with tabular-nums on labels.
  - **Bundle:** esbuild bundles to ≤ 300 KB (and above 30 KB, proving the fonts are inside).
  - RED: before `fogcity.ts` existed the file failed to load. GREEN: 24/24.
  - One test bug was fixed on the way: `block()` was called with a declaration instead of a selector.
- `SakuraThemeTest` (5, Robolectric Compose, `@Config(sdk = [36])`): palette, scheme roles, text only rose browns, no
  non-palette containers, corner sizes. RED: compile (`Unresolved reference 'MilkTea'`, …). GREEN: 5/5.
- Scratch check (Chromium):
  - light-mode body `rgb(254, 223, 225)`; glows visible behind a translucent sidebar;
  - the plugin page, settings and the folder dialog are rendered in the palette;
  - dark mode keeps `rgb(21, 21, 23)` with a pink send button.
  - Screenshots `fu1/f2-home.png`, `f3-plugins.png`, `f3-dark.png`, `picker-2.png`.

## Item 4: lag

Assumptions (cannot be measured here): the per-frame cost is
- composition of a full-screen ComposeView over the WebView;
- dsh/Node competing with the UI thread and renderer for CPU;
- WebView renderer priority;
- heavy CSS (blur on big areas, animations, which item 3 now avoids).

### What changed
- **Overlay.**
  - `ui/MainScreen.kt:49-50`: in Ready the overlay's root is not `fillMaxSize`.
  - `ui/MainActivity.kt:101-108,255,266-271`: a collector sets the ComposeView's layout params: `WRAP_CONTENT` at `TOP|END` in
    Ready, `MATCH_PARENT` for Loading and Error. Only the gear is a layer over the page.
- **nice.** `runtime/ProotRunner.kt:9-29,56-61`: `niceBinary` defaults to `/system/bin/nice` when it is a file and executable.
  The command becomes `nice -n 10 <libproot.so> …`. nice execs proot, so the `Process` stays proot's and every guest process
  inherits the niceness. Without nice the command is unchanged. This applies to dsh starts and to guest npm commands alike.
- **Renderer.** `ui/MainActivity.kt:232-234`: `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)`. Hardware
  acceleration: the manifest never disables it and no software layer is set (both asserted in a test).
- **Perf log.** `ui/MainActivity.kt:273-280`: on the first Ready after `onCreate`, ErrorLog tag `perf` gets
  `main screen ready <ms> ms after it opened (process started <ms> ms before that)`. It is logged once per screen, with no
  URL or token.

### Tests
- `ProotRunnerTest` +2: with nice the command is `[nice, -n, 10]` followed by exactly the plain command; nice is used only when
  it exists and is executable.
  - RED: `expected:<[/tmp/nice…, -n, 10]> but was:<[/native/lib/libproot.so, --link2symlink, --kill-on-exit]>`.
- `MainScreenTest` +3 (testTag `main-overlay`): Ready is under 64 dp (RED: `ready overlay is 320.0.dp x 470.0.dp`); Loading and
  Error are over 200 dp.
- `MainActivityTest` +3:
  - overlay params MATCH → WRAP at TOP|END on Ready → MATCH again when dsh stops (RED: `expected:<-2> but was:<-1>`);
  - perf logged once without URL or token (RED: `[] expected:<1> but was:<0>`);
  - renderer priority IMPORTANT and waived, not a software layer, `FLAG_HARDWARE_ACCELERATED` set.
    - Robolectric's ShadowWebView drops the priority, so the test uses a small recording shadow,
      `RendererPriorityWebViewShadow`.
    - RED with the call removed: `expected:<2> but was:<0>`.
- e2e: the emulator has `/system/bin/nice`, so every e2e dsh start since cf74cd0 runs under `nice -n 10`. That shows nice
  works in an app's SELinux domain on API 34.

## Deviations
1. **Listing (item 1).**
   - dsh 0.2.1-rc.5 has no HTTP list RPC (only the `workspace/follow` stream). `workspace/create` is used as the idempotent
     check and create; dsh answers `created:false` and never duplicates.
   - The e2e reads the list from dsh's own `storages/workspace.json` instead.
2. **Picker root cause and `documentsDirectory` (controller's recon, added as `fab94fc`).** dsh puts its default at
   `/root/workspace/deepseek-harness/default-workspace`, not at `/root/workspace`. Because the app registers `/root/workspace`
   first, that default is never created in practice.
3. **Hiding mechanics (item 2).**
   - A managed block keeps its box. Its children are `display:none` and the note is the block's own `::before`, instead of
     `display:none` on the block plus an inserted note element. No foreign node goes into React's DOM.
   - The sign-in menu item is removed outright (`gone`).
4. **Scope of hiding.**
   - Added: the whole web-search form (every field is in the patched row, and the endpoint shows the gateway URL with the
     session token), the whole 模型 section, and the 登录 menu item.
   - Not hidden: the composer's model picker. It chooses the model per conversation (`session.selectModel`); the app sets only
     the default.
   - OTel and plugin-inventory upload have no UI.
5. **Text-match hooks.** Two anchors use text because dsh offers no `id`/`data-*`: the Session Log row's aria-label (contains
   "Session Log" in both locales) and the 登录 menu item text. Everything else is id, data-* or the provider id.
6. **FOGCITY details.**
   - Primary buttons are pink with `#5E4445` text (white on `#DC9FB4` is about 2.2:1). Links are `#5E4445`.
   - `--dsw-alias-bg-base` is transparent so the static glows show. A side effect: dsh's sticky composer fade becomes
     see-through; the composer itself is blurred glass.
   - The pill rule carries an id's weight (`:not(#dshm-fogcity)`) to beat dsh's multi-class rounding without `!important`.
   - Compose keeps Material red for errors.
7. **Perf log** measures from `MainActivity.onCreate` and also gives the offset from process start, since a warm start would
   otherwise be indistinguishable.
8. `jsdom@30.1.2` was added as a dev dependency for the item 2 tests.

## Verification
- Android unit tests: **628 tests, 0 failures, 0 errors, 0 skipped** (602 at base, +26). `assembleDebug` and
  `assembleDebugAndroidTest` pass, on each pushed state.
- Plugins: `pnpm -C plugins test` **138 passed** (117 at base), `typecheck` clean, `build` ok (ui bundle 54 KB).
- Self-review:
  - still exactly 9 permissions (ManifestPermissionsTest green; no manifest change);
  - no dsh source touched (patch rows and plugin only);
  - the new log lines carry no URL or token (asserted by tests);
  - test messages print no URL or token: the e2e prints dsh's JSON answers, which contain paths and ids only.

## CI (branch claude/dsh-mobile-cloud-handoff-vfhvdh)
| Run | Commit | android | plugins | rootfs arm64 / x86_64 | e2e |
|---|---|---|---|---|---|
| 41586316420 | cf74cd0 (items 1 + 4) | success | success | success / success | success 8/8 (t6 new) |
| 41587938331 | fab94fc (picker) | success | success | success / success | success 9/9 (t8 new) |
| 41589224274 | ad4996a (items 2 + 3) | success (628) | success (138) | success / success (fresh build: plugins changed) | success 10/10 (t7 new; t1 + 11a check `rgb(254, 223, 225)`) |

Final green run on the last code commit: **41589224274**.

## Only a real device can confirm
- The lag improvement itself on the S25 Ultra. Read the new `perf` line in the exported error log, and compare scrolling and
  tapping with v0.1.0.
- That `/system/bin/nice` exists and works under Android 17's app SELinux domain on Samsung (on API 34 it does).
- That FOGCITY renders as intended in Samsung's WebView: the masked gradient edge, `backdrop-filter` cost while scrolling under
  the composer, the Orbitron and Chakra Petch fallback mixing with Chinese text, and how the gear sits over dsh's top-right
  controls.
- dsh's folder dialog on a phone screen (layout at 412 px wide, typing a path with the soft keyboard).
- That the hidden blocks stay hidden after a dsh update. The selectors are dsh 0.2.1-rc.5's; a new dsh can rename them, and then
  the blocks simply reappear.
