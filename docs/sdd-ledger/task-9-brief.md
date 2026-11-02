### Task 9：dsh 版本更新、健康檢查、退版

計畫原文 Task 9，**加上 Task 8 的實際介面**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/DshVersions.kt`、`GuestCommand.kt`
- Test: `android/app/src/test/kotlin/app/dshmobile/runtime/DshVersionsTest.kt`

**Consumes（先讀）：** `runtime/ProotRunner.kt`、`runtime/GuestNetworkFiles.kt`、`service/DshController.kt`、`runtime/HealthCheck.kt`、`log/ErrorLog.kt`（Task 8；正式建構子簽名見 `docs/sdd-ledger/task-8-report.md`）。rootfs 佈局（Task 2）：guest 內 `/opt/dsh/<version>/node_modules/...`，`/opt/dsh/current` 是指向 `/opt/dsh/<version>` 的**絕對路徑**符號連結；host 端對應 `filesDir/rootfs/opt/dsh`。dsh 由 Task 8 以 `/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js` 啟動。

**Interfaces（Produces）:**
- `fun interface GuestCommand { fun run(args: List<String>, timeoutMs: Long): CommandResult }`、`data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)`；正式實作 `ProotGuestCommand(runner: ProotRunner, rootfsDir: File, dnsServers: () -> List<String>)`：每次執行前 `GuestNetworkFiles.write`，以 `/usr/bin/env -u LD_LIBRARY_PATH` 前綴在 guest 裡執行，環境 `HOME=/root`、`PATH=/usr/local/bin:/usr/bin:/bin`、`npm_config_update_notifier=false`、`npm_config_fund=false`、`npm_config_audit=false`；逾時就 `destroyForcibly` 並回 exitCode -1。
- `class DshVersions(optDsh: File, cmd: GuestCommand, log: ErrorLog)`（`optDsh` = host 端 `rootfs/opt/dsh`）
  - `fun current(): String`：讀 `current` 連結目標的最後一段（guest 絕對路徑）。
  - `fun previous(): String?`：讀 `optDsh/.previous`（單行版號；不存在或對應目錄不存在 → null）。
  - `suspend fun latestAvailable(): String`：guest 內 `npm view @deepseek-ai/dsh version`（逾時 60 秒）；輸出 trim 後必須符合版號格式，否則丟例外。
  - 版號格式（所有接受版號的函式都先驗證，防路徑跳脫與指令注入）：`^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$`。
  - `suspend fun install(version: String): Result<Unit>`：已存在 `optDsh/<version>/node_modules/@deepseek-ai/dsh` → 直接成功；否則先刪殘留的 `<version>.tmp`，guest 內 `npm install --prefix /opt/dsh/<version>.tmp @deepseek-ai/dsh@<version> --no-audit --no-fund --loglevel=error`（逾時 15 分鐘）；成功且 `<version>.tmp/node_modules/@deepseek-ai/dsh/package.json` 的 `version` 等於要求的版號 → 原子改名為 `<version>`；任何失敗（非 0 結束碼、逾時、版號不符、空間不足）→ 刪掉 tmp、`Result.failure`，原因寫 ErrorLog；**目前版本完全不動**。
  - `fun switchTo(version: String)`：目錄必須存在；在 host 端建立 `current.tmp` → `/opt/dsh/<version>`（絕對 guest 路徑），用 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 蓋過 `current`；切換前的版本寫入 `.previous`（同樣先寫 tmp 再原子改名）；然後刪掉 `current` 與 `previous` 以外的版本目錄（刪除不得跟隨符號連結；`.tmp` 殘留一併清掉）。切到同一版 → 不動作。
  - `fun rollback(): Boolean`：有 previous 且目錄存在 → `switchTo(previous)`（於是 previous 變成剛才的版本）並回 true；否則 false。
  - `suspend fun updateWithAutoRollback(controller: DshController, health: HealthCheck): UpdateOutcome`：`sealed interface UpdateOutcome { data class Updated(val version: String); data class RolledBack(val version: String, val reason: String); data class Failed(val reason: String); data object UpToDate }`。流程：取 latest（失敗 → `Failed`）；等於 current → `UpToDate`；`install`（失敗 → `Failed`，dsh 照常可用）；記下舊版 → `controller.stop()` → `switchTo(new)` → `controller.ensureRunning()`（它內含 HealthCheck；任何例外視為不健康）→ 成功 `Updated(new)`；失敗 → `controller.stop()` → `switchTo(舊版)` → `controller.ensureRunning()` → `RolledBack(new, reason)`（舊版也起不來 → 仍回 `RolledBack`，原因附註舊版也失敗，寫 ErrorLog）。`health` 參數保留給呼叫端相容（若 ensureRunning 已含健康檢查，可不再重複呼叫，報告說明）。
- 呼叫者（Task 11b 設定頁）會在背景協程中呼叫，不需要自己處理 UI。

- [ ] **Step 1: 寫失敗測試**（假 `GuestCommand` 模擬 npm：在 host 的暫存 `optDsh` 底下建立/不建立目錄與 package.json；假 controller 可注入成功/失敗）
```kotlin
@Test fun installFailureLeavesCurrentUntouchedAndNoTmp()   // 模擬 npm 結束碼 1（ENOSPC）
@Test fun installRejectsWrongInstalledVersion()
@Test fun invalidVersionStringsAreRejected()               // "../x"、"1.2.3; rm -rf /"、"" → 例外，GuestCommand 從未被呼叫
@Test fun keepsOnlyTwoVersions()
@Test fun switchIsAtomicAndRecordsPrevious()
@Test fun unhealthyNewVersionRollsBackAutomatically()      // 結果 RolledBack，current 回到舊版，controller 最後是用舊版啟動
@Test fun manualRollbackSwapsCurrentAndPrevious()
@Test fun sameVersionIsUpToDate()                          // 沒有 install、沒有 stop
@Test fun latestAvailableFailureIsFailedAndDshKeepsRunning()
```
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS；全套單元測試綠燈、輸出乾淨。
- [ ] **Step 5:** Commit `feat: dsh versions with health check and rollback`。
