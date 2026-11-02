### Task 11a：主畫面（WebView）＋櫻花配色

計畫原文 Task 11a，**加上帳本 Ruling（配色改用 dsh 插件 `webserver/index-inject`、通知深連結）與前面任務的實際介面**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `plugins/packages/ui/package.json`（`@dshm/ui`，`private`，`type: module`）、`src/index.ts`、`src/sakura.ts`；Test: `plugins/packages/ui/test/ui.test.ts`
- Modify: `plugins/package.json` 的 `build` 也打包 ui → `plugins/packages/ui/dist/index.js`；`.github/workflows/ci.yml` rootfs staging 複製到 `out/staging/plugins/ui/index.js`；`rootfs/smoke-test.sh` 加檢查 `/opt/dshm/plugins/ui/index.js`
- Modify: `android/app/src/main/kotlin/app/dshmobile/config/DshPatch.kt`（insert 第三列 `dshm-ui` → `<pluginDir>/ui/index.js`）與其測試
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/MainActivity.kt`、`ui/DshWebViewClient.kt`（導覽白名單邏輯抽成純函式以便單元測試）、`ui/DownloadHandler.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`：宣告 `MainActivity`（`exported=true`、LAUNCHER intent-filter、`launchMode="singleTask"`、`configChanges` 涵蓋旋轉／深色模式以免 WebView 重載）；**不得新增 uses-permission**
- Test: `android/app/src/test/kotlin/app/dshmobile/ui/DshWebViewClientTest.kt`、`MainActivityTest.kt`（Robolectric：意圖分派、導向引導頁）；`android/app/src/androidTest/kotlin/app/dshmobile/ui/MainActivityTest.kt`（儀器測試，需要真 dsh：**本任務只需編譯通過**，`./gradlew :app:assembleDebugAndroidTest`；實際在 Task 13 的 CI 模擬器執行）

**Consumes（先讀）：** `service/DshController.kt`（`ensureRunning(): RunningDsh`、`RunningDsh.launch.url/port`、Task 10 加的 `retain/release/stopIfUnretained`）、`service/DshService.kt`、`notify/Notifier.kt`（extras `"sessionId"`、`"action"` = `"rollback"` / `"export_error_log"`）、`runtime/DshVersions.kt`（`rollback()`）、`export/Exporter.kt`（`share`、`errorLogZip`，Task 11d）、`system/OnboardingState.kt` 與 `ui/OnboardingActivity.kt`、`ui/theme/SakuraTheme.kt`（Task 11c）、`DshMobileApp.kt`；插件寫法照 `plugins/packages/notify/src/index.ts`；`docs/dsh-findings.md` §UI（token 清單與選擇器）。

**配色（Ruling：用插件，不用 WebView 注入 CSS）：**
- `@dshm/ui`（`name = 'dshm-ui'`）：`ctx.on('webserver/index-inject', (table) => table.push({ kind: 'style', text: SAKURA_CSS }))`（findings §UI 已實測可用）。
- `SAKURA_CSS`（`src/sakura.ts` 匯出常數）：
  - light（選擇器 `:root, body`）：把 `--dsw-static-deepseek-50…900` 換成粉紅色階（以主色 `#d6336c` 為 500、`#c2255c` 為 600、`#ffd6e3` 為 100、`#fff0f5` 為 50，其餘內插）；背景 `--dsw-alias-bg-base`、`--dsw-alias-bg-layer-1/2/3`、`--dsw-alias-bg-module-platform` = `#fff5f8`；`--dsw-alias-bg-document-selection` 用主色 40% 透明；`--dsw-focus-ring-color` = `#d6336c`；另外 `body { background-color: #fff5f8; }`（儀器測試用 `getComputedStyle(document.body).backgroundColor === 'rgb(255, 245, 248)'` 驗收）。
  - dark（`body[data-ds-dark-theme]`）：只改強調色（`--dsw-static-deepseek-400/450` 改成 `#f06595` 系），**不改**深色背景。
  - **不要**改 `--dsw-alias-brand-primary`、`--dsw-alias-brand-text`、`--dsw-alias-label-primary`（那是文字色）。
- vitest：插件 push 了一列 `kind: 'style'`；CSS 含 `#fff5f8`、`#d6336c`；不含 `--dsw-alias-label-primary`；dark 區塊不改背景。

**通知深連結（Ruling）：** dsh 沒有對話網址（findings §UI）；用 inline script 打開對話需要 client ctx，未驗證。**本期採 findings 的備案 (b)**：點通知只把 App 帶到前景並載入 dsh 首頁；`sessionId` extra 先記錄在 ErrorLog（tag `deeplink`，只寫 session id）以便日後實作。不要做未驗證的 JS 注入。

**MainActivity 行為：**
- `onCreate`：若 `OnboardingState` 未完成或 rootfs 未安裝 → 啟動 `OnboardingActivity` 並 `finish()`。
- `onStart`：`retain("ui")`；`startForegroundService(DshService)`；背景協程 `ensureRunning()` → WebView `loadUrl(running.launch.url)`（dsh 回 303 並設 cookie，WebView 自己處理）。期間顯示載入畫面（Compose，`SakuraTheme`），失敗顯示錯誤畫面：「重試」與「匯出錯誤記錄」。
- `onStop`：`release("ui")`；排一個 **10 分鐘寬限**後的檢查：若 `ui` 仍未再持有 → `stopService(DshService)`（service 的 `onDestroy` 會 `release("service")`）→ `stopIfUnretained()`。（Ruling：規格「App 不在前景時 dsh 不常駐」，寬限避免切 App 一下就殺掉正在跑的代理人；常數 `BACKGROUND_GRACE_MS = 600_000`。）
- WebView 設定：JavaScript、DOM storage 開；`allowFileAccess=false`、`allowContentAccess=false`；`setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`；`mixedContentMode = NEVER_ALLOW`。
- 導覽白名單（`DshWebViewClient.shouldOverrideUrlLoading` → 純函式 `decide(url, dshPort): Decision { ALLOW, EXTERNAL, BLOCK }`）：`http://127.0.0.1:<dshPort>/…` → ALLOW；其他 `http`／`https` → EXTERNAL（`ACTION_VIEW` 交給系統瀏覽器）；`javascript:`、`file:`、`content:`、`intent:`、其他埠的 127.0.0.1、`localhost` → BLOCK。
- 下載（`setDownloadListener`）：只處理 `http://127.0.0.1:<dshPort>/…`：帶 `CookieManager` 的 cookie 用 OkHttp 下載到 `cacheDir/export/<檔名>`（檔名清理：去掉路徑分隔與控制字元，空的話用 `download`），完成後 `Exporter.share`；其他來源或 `blob:` → Toast「無法下載此類型的檔案」（報告註明 blob 下載未支援）。
- Intent extras（`onCreate` 與 `onNewIntent`）：`action=rollback` → 背景 `controller.stop()`、`DshVersions.rollback()`、`ensureRunning()`、重新載入，Toast 結果；`action=export_error_log` → `Exporter.share(errorLogZip())`；`sessionId` → 見上方深連結 Ruling。
- 返回鍵：WebView 能後退就後退，否則照系統預設。
- 右上角一個小型設定按鈕（浮在 WebView 上，不遮內容）開 `app.dshmobile.ui.SettingsActivity`（`ComponentName` 字串；Task 11b 建立）。

- [ ] **Step 1: 寫失敗測試**：`ui.test.ts`（見上）；`DshPatchTest` 第三個插件；`DshWebViewClientTest`（ALLOW／EXTERNAL／BLOCK 各種 URL，含 `http://127.0.0.1:<其他埠>`、`http://127.0.0.1.evil.com:<埠>`、`javascript:`）；Robolectric `MainActivityTest`：引導未完成 → 啟動 OnboardingActivity；`action=export_error_log` 觸發分享 Intent；儀器測試 `MainActivityTest`（編譯即可）：開啟後 WebView 載到 dsh 頁面、`getComputedStyle(document.body).backgroundColor` 為 `rgb(255, 245, 248)`、點外部連結不在 WebView 內開。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** `pnpm -C plugins test/typecheck/build`、`./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 全部成功、輸出乾淨。
- [ ] **Step 5:** Commit `feat: main webview with sakura theme`。

**附加（Task 11c 經驗）：** Robolectric 上的 Compose／Espresso 測試必須加 `@Config(sdk = [36])`（Android 17 jar 缺 `InputManager.getInstance()`），見 `docs/sdd-ledger/task-11c-report.md`。

**附加（Task 11d 經驗）：** `DshMobileApp` 沒有 Exporter 單例；用 `Exporter(context, File(filesDir, "rootfs/root/workspace"), errorLog)` 建構（或在 DshMobileApp 加一個 lazy 單例）。測試若呼叫 `Exporter.share`，要比照 `ExporterTest` 重設 FileProvider 的路徑快取。
