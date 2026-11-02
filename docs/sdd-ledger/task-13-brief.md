### Task 13：模擬器端到端測試（CI）

計畫原文 Task 13，**加上前面任務的實際介面與下列 Ruling**。本文與計畫原文衝突時以本文為準。這是第一次在真的 Android（x86_64 模擬器）上跑 proot + Debian + Node 24 + 官方 dsh + 三個插件 + App 的完整組合；預期會發現前面任務單元測試看不到的問題，**修那些問題也是本任務的範圍**（每個修正都要有對應的 E2E 或單元測試，並在報告逐條列出）。

**Rulings：**
1. **只能在 GitHub Actions 跑**（本容器沒有 KVM）。本任務的實作者**獲准**推送到分支 `claude/dsh-mobile-cloud-handoff-vfhvdh`（只有這個分支；不得 force push、不得開 PR、不得動其他分支），並用 GitHub MCP 工具（`mcp__github__actions_list`、`mcp__github__get_job_logs`；需要時先用 ToolSearch 載入）讀 CI 結果、反覆修到綠燈。每次推送前先在本機跑過單元測試與 `assembleDebug`/`assembleDebugAndroidTest`。CI 已設定 docs-only 推送不觸發、同分支新推送會取消舊執行。
2. **測試用上游**：`DshMobileApp` 增加一個只給測試用的覆寫點（例如 `@VisibleForTesting var upstreamsOverride: Map<Provider, HttpUrl>?`，在單例 controller 首次建立前設定），讓閘道把 DeepSeek 請求轉到測試行程裡的 MockWebServer（`127.0.0.1`）。正式路徑不變。**CI 不需要真金鑰、不花錢**：測試把假金鑰 `sk-e2e-fake` 存進 `AndroidKeyVault`。
3. **假模型**（MockWebServer `Dispatcher`，說 Anthropic Messages 協定、SSE 串流，格式照 findings §Providers「用量欄位」：`message_start`、`content_block_*`、`message_delta`（含 usage）、`message_stop`）：依請求內容回應——
   - 對話請求且尚無 `tool_result` → 回一個 `tool_use` 呼叫 `memory_write`（`{"target":"daily","text":"E2E-MEMORY-MARK"}`）；
   - 帶 `tool_result` 的後續請求 → 回純文字 `ok`；
   - 標題產生或其他請求 → 回純文字；
   - 記憶整理（dream）請求（可由 prompt 內容辨識，見 `plugins/packages/memory/src/dream.ts`）→ 回 `{"promote":[],"remind":[{"title":"E2E-REMIND","body":"from e2e"}]}`。
4. 跳過引導：測試直接寫 `OnboardingState`（全部完成）、`AndroidKeyVault` 假金鑰，並在測試開始時用 `RootfsInstaller` 從 APK asset 安裝 x86_64 rootfs（若已安裝則略過）。

**Files:**
- Create: `android/app/src/androidTest/kotlin/app/dshmobile/E2eTest.kt`（以及需要的 `androidTest` 輔助類別：假模型、等待工具）
- Modify: `.github/workflows/ci.yml`：新增 `e2e` job（`needs: rootfs`，`runs-on: ubuntu-latest`，`timeout-minutes: 60`）：checkout → JDK 21 + Android SDK（同 android job）→ 啟用 KVM（udev rule 步驟，照 `reactivecircus/android-emulator-runner` 文件）→ 下載 `dshm-rootfs-x86_64` artifact → node/pnpm 建插件（若 APK 建置需要）→ `reactivecircus/android-emulator-runner@v2`（`api-level: 34`、`arch: x86_64`、`target: google_apis`、`disk-size: 6G` 以上、`emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim`、`disable-animations: true`），script：`./gradlew :app:connectedDebugAndroidTest -Pdshm.rootfsAbi=x86_64 -Pdshm.rootfsFile=<下載的路徑>`；失敗時上傳 `android/app/build/reports/androidTests` 與 `adb logcat` 為 artifact。
- Modify: `DshMobileApp.kt`（Ruling 2）
- 以及為了讓 E2E 通過而必須修的任何程式（逐條記錄原因）

**Consumes：** 全部前面任務。先讀 `docs/sdd-ledger/task-8-report.md`、`task-10-report.md`、`task-11a-report.md`、`task-11c-report.md`、`task-12-report.md`（rootfs 打包屬性 `dshm.rootfsAbi`／`dshm.rootfsFile`）、`docs/dsh-findings.md` §Server（RPC 呼叫格式）、§Providers。

**E2E 測試（規格 §9 的五項逐一對應）：**
1. 啟動 `MainActivity` 後 WebView 載入 dsh 介面（等到 `document.title` 非空或可辨識的 DOM 出現），且 `getComputedStyle(document.body).backgroundColor === 'rgb(255, 245, 248)'`（同時讓 Task 11a 的儀器測試 `MainActivityTest` 在這裡真正執行）。
2. 從測試行程直接 `GET http://127.0.0.1:<dshPort>/`（不帶 cookie）→ 401 或 403；`GET /dshm/health/memory`（不帶 cookie）→ 401。
3. 透過 dsh 對話讓代理人寫一條記憶：用 `DshClient` 登入後以 dsh 的 RPC（`POST /api/<namespace>/<method>`，格式見 findings §Server；建立工作區、開新對話、送訊息的方法名請在 scratch dsh 查證）送一則訊息；假模型回 `memory_write` 工具呼叫 → 等到工作區 `memory/<今天>.md` 出現 `E2E-MEMORY-MARK`。
4. 心跳：先在工作區寫一個**昨天**的每日筆記（dream 只處理今天以前的筆記），再用 App 的 `WorkerFactory` 執行 `HeartbeatWorker`（`TestListenableWorkerBuilder`）→ `NotificationManager.activeNotifications` 出現標題 `E2E-REMIND` 的通知。
5. 更新失敗自動退版：用假 `GuestCommand` 讓「新版」安裝成功但 dsh 起不來（例如 `bin.js` 立即 `process.exit(1)`）→ `updateWithAutoRollback` 回 `RolledBack`，之後 `ensureRunning()` 成功、健康檢查通過、介面仍可用。

- [ ] **Step 1:** 寫測試與 CI job。
- [ ] **Step 2:** 推上去，CI 失敗（預期）。
- [ ] **Step 3:** 修到全綠（每次修正都記錄在報告）。
- [ ] **Step 4:** CI 上 `e2e` job PASS，其他 job 也全綠。
- [ ] **Step 5:** Commit（可多個）；最後一個 `test: emulator end-to-end suite`。
