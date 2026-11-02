### Task 10：心跳（HeartbeatWorker + HeartbeatScheduler）

計畫原文 Task 10，**加上 Task 6／8 的實際介面與以下 Ruling**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/heartbeat/HeartbeatWorker.kt`、`HeartbeatScheduler.kt`
- Modify: `android/app/src/main/kotlin/app/dshmobile/service/DshController.kt`（加持有者計數，見下）、`service/DshService.kt`（持有／釋放）、`DshMobileApp.kt`（App 啟動時排程心跳；WorkManager 自訂初始化若需要）
- Test: `android/app/src/test/kotlin/app/dshmobile/heartbeat/HeartbeatWorkerTest.kt`、`HeartbeatSchedulerTest.kt`；`DshControllerTest.kt` 補持有者測試
- Modify（若需要）: `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`（`androidx.work:work-testing`，版本對齊已有的 work-runtime 2.12.0）

**Consumes（先讀）：** `service/DshController.kt`、`runtime/DshClient.kt`、`notify/Notifier.kt`、`log/ErrorLog.kt`、`gateway/LocalGateway.kt` 的 `NotifyRequest`、`DshMobileApp.kt`（Task 8；簽名見 `docs/sdd-ledger/task-8-report.md`）。記憶插件路由（Task 6）：`POST /dshm/heartbeat`（需 dsh cookie，`DshClient` 會先 `login()`）→ 200 `{"promoted":[string…],"reminders":[{"title","body"}…]}`；502 `{"error":"dream_parse"}`；500 `{"error":"dream_failed"}`；409 `{"error":"dream_busy"}`。

**Ruling（防止心跳關掉使用者正在用的 dsh）：** 計畫的「原本沒在跑 → 結束後 stop()」有競態：心跳冷啟動 dsh 期間使用者打開 App，心跳結束時會把使用者的 dsh 關掉。改為**持有者計數**：
- `DshController` 新增 `fun retain(owner: String)`、`fun release(owner: String)`、`fun stopIfUnretained(): Boolean`（持有者集合為空才 `stop()`，回傳是否真的停了）、`val holders: Set<String>`（唯讀快照，給測試）。執行緒安全。`stop()` 本身維持原意（強制停止）。
- `DshService` 進入前景時 `retain("service")`、結束時 `release("service")`；MainActivity（Task 11a）之後也會用同一 API。
- 心跳：`retain("heartbeat")` → 工作 → `release("heartbeat")` → `stopIfUnretained()`。這自然滿足「原本在跑就不動」（service 還持有）。

**Interfaces（Produces）:**
- `object HeartbeatScheduler { const val WORK_NAME = "dshm-heartbeat"; fun schedule(context: Context, intervalMin: Int = 45); fun cancel(context: Context) }`：`PeriodicWorkRequest<HeartbeatWorker>`，間隔夾到 30..60 分鐘，`Constraints(requiredNetworkType = NetworkType.CONNECTED)`，`enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, …)`。開機後 WorkManager 會自己恢復週期工作（用既有的 `RECEIVE_BOOT_COMPLETED`，**不得新增權限**；合併後 manifest 權限測試必須維持綠燈）。
- `class HeartbeatWorker(context, params) : CoroutineWorker`：依賴（controller、client 工廠、notifier、errorLog）由 `DshMobileApp` 的單例圖取得，並可在測試注入（例如自訂 `WorkerFactory`）。流程：
  1. `setForeground(ForegroundInfo(id, notifier.serviceNotification(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE))`（失敗時記 ErrorLog 後繼續；Android 對背景啟動前景服務有限制，報告說明你的處理）。
  2. `retain("heartbeat")`；`ensureRunning()`；用 `DshClient(…, running.launch)` `login()` 後 `postJson("/dshm/heartbeat", "{}")`。
  3. 200 → 每個 reminder → `Notifier.show(NotifyRequest(title, body, null))`；`promoted` 數量寫一行 ErrorLog（tag `heartbeat`，**不寫內容**）。
  4. 非 200（含 409 busy）、逾時、任何例外 → 不發通知、寫 ErrorLog（只寫狀態碼／例外類別，不寫 body）。
  5. `finally`：`release("heartbeat")`、`stopIfUnretained()`。
  6. **一律回 `Result.success()`**（本次跳過，不重試轟炸）。整體逾時 5 分鐘。
- `DshMobileApp.onCreate`：`HeartbeatScheduler.schedule(this, <設定值，預設 45>)`（設定頁 Task 11b 會改間隔；目前用常數 45 或已存在的偏好值）。

- [ ] **Step 1: 寫失敗測試**（`work-testing` 的 `TestListenableWorkerBuilder`／`TestDriver`，假 controller／假 client）：dsh 已被 service 持有時心跳不會冷啟動第二個、結束後仍在跑；沒有其他持有者時結束後被停掉；心跳期間 service 加入持有 → 心跳結束**不**停；路由回 500／409／例外時結果仍為 success 且沒發通知；兩個 reminder 發兩則通知；`schedule(context, 5)` 實際排 30 分鐘、`schedule(context, 90)` 排 60、有網路條件、unique name 正確；`DshController` 持有者計數的並行測試。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS；全套單元測試綠燈、輸出乾淨；`assembleDebug` 成功。
- [ ] **Step 5:** Commit `feat: heartbeat worker`。

**附加（Task 8 審查的 minor，帳本 Ruling 併入本任務，因為本任務本來就改這兩個檔）：**
- (a) `DshController`：`launcher.start` 丟例外時（`run` 尚為 null）也要刪掉含通行碼的 `dshm.cordis.yml`；補測試。
- (b) `DshService`：rootfs 未安裝（`IllegalStateException("rootfs not installed")`，建議改成專用例外型別 `RootfsNotInstalledException`）**不算當掉**：不經 `CrashPolicy`、不發「多次當掉／退版」通知，直接記 ErrorLog 並停止服務。
- (c) `DshService`：`GIVE_UP` 後 `CrashPolicy.reset()`，讓使用者手動重啟時重新計數。
- (d) `DshController`：`stop()` 落在啟動途中（`current` 尚未設定）時要生效：設 `stopRequested`，啟動流程在發布 running 狀態前檢查，若已要求停止則清理並丟 `CancellationException`；`_isRunning` 的更新放在同一把鎖內。補一個「啟動途中 stop」測試。
