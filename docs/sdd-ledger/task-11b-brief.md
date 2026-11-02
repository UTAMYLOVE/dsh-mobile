### Task 11b：設定頁與代理人設定

計畫原文 Task 11b，**加上 findings §Config／§Providers 與前面任務的實際介面，以及下列 Ruling**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/config/AgentSettings.kt`、`AgentSettingsStore.kt`、`AgentConfigWriter.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/SettingsActivity.kt`、`ui/SettingsScreen.kt`
- Modify: `config/DshPatch.kt`（加代理人段落；Anthropic 列只在有 Claude 金鑰時輸出）、`service/DshController.kt`（patch 內容改由可注入的 `patchProvider: (gatewayBase: String) -> String` 產生；正式版由 `DshMobileApp` 組合 `AgentConfigWriter`）、`DshMobileApp.kt`、`AndroidManifest.xml`（宣告 `SettingsActivity`，`exported=false`；**不得新增 uses-permission**）
- Test: `AgentConfigWriterTest.kt`、`AgentSettingsTest.kt`、`AgentSettingsStoreTest.kt`、`ui/SettingsScreenTest.kt`（Robolectric + Compose）；`DshPatchTest`／`DshControllerTest` 跟著調整

**Consumes（先讀）：** `config/DshPatch.kt`、`service/DshController.kt`、`security/KeyVault.kt`、`gateway/CostMeter.kt`（`todayUsd()`、`alertUsd`、`hardCapUsd`）、`runtime/DshVersions.kt`（Task 9）、`system/PhantomProcessDetector.kt`、`ui/theme/SakuraTheme.kt`（Task 11c）、`heartbeat/HeartbeatScheduler.kt`（Task 10）、`export/Exporter.kt`（Task 11d）、`docs/dsh-findings.md` §Config「子代理人同時數、深度、團隊人數」與「設定產生器要輸出的 patch」第 2、3 點、§Providers。

**Rulings（寫進本 brief，實作照做）：**
1. **子代理人沒有獨立模型鍵**（findings：子代理人與隊友沿用父代理模型；dsh 0.2.1 只有 `tool-subagent.modelSelection` 讓模型自選）。本期只設定 **Lead 模型**（`agent-default-model`），`AgentSettings` 不含 `workerModel`；設定頁註明「子代理人沿用總指揮模型」。這是規格 §5.5「跑腿用便宜模型」的已知落差，要在報告列出。
2. **壓縮摘要模型**需改寫 preset（findings 建議執行期由 `--dump-config` 取出再改），本期**不做**，沿用對話模型。
3. **團隊人數不得超過同時數**：輸出時 `agent-team.maxMembers = min(teamSize, maxConcurrent)`、`subagent.maxActiveSubagents = maxConcurrent`。
4. **Agent Teams 開關**：需把 `@deepseek-ai/dsh-experimental-agent-team-profile` 加進 `$DSH_HOME/profiles/web/package.json` 的 `dsh.profile.bundles`（findings §Config）。`AgentConfigWriter` 負責：檔案存在 → 解析 JSON、只在陣列裡加這一項（保留其他欄位與順序）；不存在 → **不要自己建立**（dsh 首次啟動才會建），回報「下次啟動後生效」並在下次 dsh 停止時再補。**請用 scratch dsh 實測**（之前的實作者裝在 `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/dsh-scratch`）：寫入後 `dsh --profile web --patch <你的 patch> --dump-config` 確認 `agent-team` 列存在且設定生效，把指令與節錄輸出貼進報告。若做不到，回報 NEEDS_CONTEXT。
5. 套用設定需要重啟 dsh：設定頁「套用並重啟」按鈕 → `controller.stop()` → `ensureRunning()`。

**Interfaces（Produces）:**
- `data class AgentSettings(val maxConcurrent: Int, val maxDepth: Int = 2, val teamSize: Int = 8, val leadModel: String, val agentTeams: Boolean = true)`；`companion fun defaults(phantom: PhantomState, hasClaudeKey: Boolean): AgentSettings`：`DISABLED` → 16，否則 6；`leadModel` = `deepseek-flash`（不論有無 Claude 金鑰，預設都用 DeepSeek；有 Claude 金鑰時設定頁才多出 Claude 選項）。範圍檢查：`maxConcurrent` 1..32、`maxDepth` 0..3、`teamSize` 1..16。
- 模型清單：DeepSeek `deepseek-flash`、`deepseek-v4-pro`（provider `deepseek-official`）；有 Claude 金鑰才加 `claude-sonnet-5-5`、`claude-opus-5-5`、`claude-haiku-5-5`（provider `anthropic`，經 pi-ai）。沒有 Claude 金鑰時若存的 `leadModel` 是 Claude 模型 → 寫設定時自動退回 `deepseek-flash`（Review Focus 5）。
- `class AgentSettingsStore(prefs: SharedPreferences)`：`load(phantom, hasClaudeKey): AgentSettings`（沒存過 → `defaults`）、`save(s)`。
- `class AgentConfigWriter(dshHome: File /* host 端 rootfs/root/.dsh */)`；`fun patchSection(s: AgentSettings, hasClaudeKey: Boolean): String`（`agent-default-model` 列：`provider` + `model`；`subagent` 列：`maxActiveSubagents`、`maxDepth`；`agentTeams` 為 true 時 `agent-team` 列：`maxMembers`、`maxTasks: 256`、`maxMessageBytes: 65536`、`disposalTimeoutMs: 5000`）；`fun ensureAgentTeamsBundle(enabled: Boolean): Boolean`（見 Ruling 4；enabled=false 時從陣列移除）。
- `DshPatch.render(gatewayBase, pluginDir, agentSection: String, includeAnthropic: Boolean)`：`includeAnthropic=false` 時**整份輸出不得出現 `anthropic`**（Review Focus 5）；金鑰欄只會是 `apiKeyEnv` 名稱，**永不**出現真金鑰。
- 設定頁（Compose、`SakuraTheme`、繁體中文）區塊：金鑰（DeepSeek、Claude：顯示「已設定／未設定」、替換、清除；輸入框為密碼型）；代理人（同時數、深度、團隊人數、Agent Teams 開關、總指揮模型；說明子代理人沿用總指揮模型）；預算（今日花費 US$、通知門檻、硬上限開關＋金額）；心跳間隔（30～60 分鐘，改了就 `HeartbeatScheduler.schedule`）；dsh 版本（目前、上一版、「檢查更新」→ `updateWithAutoRollback` 顯示結果、「退回上一版」）；子行程限制狀態（`PhantomState` 文字＋按鈕開開發人員選項）；「匯出錯誤記錄」、「匯出記憶備份」（`Exporter`）；「套用並重啟」。長時間動作在背景協程，期間按鈕停用並顯示進度。

- [ ] **Step 1: 寫失敗測試**
```kotlin
@Test fun noClaudeKeyMeansNoAnthropicReferences()   // 整份 patch 不含 "anthropic"（不分大小寫），即使存的 leadModel 是 claude-*
@Test fun configNeverContainsRealKey()              // vault 放 "sk-real"，patch 不含 "sk-real"；金鑰只以 apiKeyEnv 名稱出現
@Test fun defaultsDependOnPhantomState()            // DISABLED→16, ENABLED→6, UNKNOWN→6
@Test fun baseUrlsPointAtGateway()
@Test fun teamSizeIsClampedToConcurrency()
@Test fun agentTeamsBundleIsAddedWithoutTouchingOtherFields()
@Test fun missingProfilePackageJsonIsNotCreated()
```
另：`AgentSettingsStore` 存讀往返；`SettingsScreen`（Compose）：沒 Claude 金鑰時模型選單沒有 Claude 選項、按「套用並重啟」會呼叫 stop 再 ensureRunning（假 controller）。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 全套單元測試綠燈、輸出乾淨（`ManifestPermissionsTest` 仍通過）；`assembleDebug` 成功；scratch dsh 的 `--dump-config` 實測結果貼進報告。
- [ ] **Step 5:** Commit `feat: settings screen and agent config writer`。
