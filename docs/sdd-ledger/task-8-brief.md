### Task 8：dsh 程序控制、常駐服務、錯誤記錄、通知

計畫原文 Task 8，**加上帳本 Ruling（ErrorLog、onCostAlert、DNS/hosts、auth 改用 dsh 內建 launch token）與 Task 3～7 的實際介面**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/service/CrashPolicy.kt`、`DshController.kt`、`DshService.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/notify/Notifier.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/HealthCheck.kt`、`DshClient.kt`、`GuestNetworkFiles.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/config/DshPatch.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/log/ErrorLog.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/DshMobileApp.kt`（`Application` 子類，持有單例圖）
- Modify: `android/app/src/main/AndroidManifest.xml`（`<application android:name=".DshMobileApp">`、`DshService` 宣告 `foregroundServiceType="specialUse"` 與 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property，說明「runs the local AI agent runtime the user is chatting with」；**不得新增任何 uses-permission**，`ManifestPermissionsTest` 必須維持綠燈）
- Test: `CrashPolicyTest.kt`、`DshControllerTest.kt`、`HealthCheckTest.kt`、`DshPatchTest.kt`、`ErrorLogTest.kt`、`GuestNetworkFilesTest.kt`（放在 `android/app/src/test/kotlin/app/dshmobile/<同套件>/`）

**Consumes（先讀這些檔）：**
- `runtime/ProotRunner.kt`：`ProotRunner(nativeLibDir, rootfsDir, tmpDir).command(args, env): ProcessBuilder`（已清空環境並設 `PROOT_LOADER`、`LD_LIBRARY_PATH`、`HOME=/root`、`PATH`、`LANG`）。
- `runtime/RootfsInstaller.kt`：`isInstalled()`；rootfs 在 `filesDir/rootfs`。
- `runtime/DshLaunchLine.kt`：`DshLaunchLine.parse(line): LaunchUrl?`，`data class LaunchUrl(val url: String, val port: Int)`。
- `security/SessionToken.kt`、`security/KeyVault.kt`（`AndroidKeyVault`）、`gateway/*`：`LocalGateway(token, vault, meter, upstreams, onNotify, onCostAlert, onRateGuard, …)`、`start(): Int`、`stop()`；`CostMeter`；`Provider { DEEPSEEK, ANTHROPIC }`；`NotifyRequest(title, body, sessionId)`。
- 插件：`/opt/dshm/plugins/memory/index.js`（`GET /dshm/health/memory`）、`/opt/dshm/plugins/notify/index.js`（`GET /dshm/health/notify`），兩者都經 dsh 的 cookie 驗證；notify 插件讀 `DSHM_GATEWAY`，memory 插件讀 `DSHM_WORKSPACE`。
- `docs/dsh-findings.md` §Pin（啟動旗標順序）、§Server（launch URL、cookie、privacy patch）、§Config「設定產生器要輸出的 patch」第 1、4、5、6 點、§Plugins「如何讓 dsh 載入本機路徑的插件」。

**Interfaces（Produces）:**
- `class CrashPolicy(max: Int = 3, windowMs: Long = 600_000, clock: () -> Long)`；`fun onCrash(): Decision`（`RESTART` / `GIVE_UP`：視窗內第 `max` 次回 `GIVE_UP`）；`fun reset()`。
- `class ErrorLog(filesDir: File)`；`fun append(tag: String, msg: String)`：寫 `filesDir/logs/error.log`，每行 `ISO-8601 時間 [tag] msg`；超過 1 MB 時改名為 `error.log.1`（覆蓋舊的）再寫新檔；執行緒安全。寫入前遮蔽：`token=<任何非空白>` → `token=***`；任何 64 個 hex 字元的連續串 → `***`；`fun addSecret(s: String)` 登記的字串 → `***`（Controller 啟動時登記本次 SessionToken 與 launch URL 的 token）。`fun file(): File`（給 Task 11d 匯出）。
- `object DshPatch { fun render(gatewayBase: String, pluginDir: String = "/opt/dshm/plugins"): String }`：產生 dsh `--patch` YAML，內容依序：(1) `llm-deepseek`：`apiKeyEnv: DEEPSEEK_API_KEY`、`baseURL: <gatewayBase>/deepseek`；`web-search-deepseek`：`apiKeyEnv: DEEPSEEK_API_KEY`、`baseURL: <gatewayBase>/deepseek/v1`；`llm-pi-ai`：`providers.anthropic` 的 `apiKeyEnv: ANTHROPIC_API_KEY`、`baseURL: <gatewayBase>/anthropic`；(2) findings 的完整 privacy patch（三列）；(3) 瀏覽式資料夾選擇器 patch；(4) `insert` 兩列 `dshm-memory` → `<pluginDir>/memory/index.js`、`dshm-notify` → `<pluginDir>/notify/index.js`。YAML 字串要正確跳脫。Task 11b 會在這個物件上加代理人設定段落（同一份 patch），所以把各段寫成可組合的函式。
- `object GuestNetworkFiles { fun write(rootfsDir: File, dnsServers: List<String>) }`：每次啟動前覆寫 `rootfs/etc/hosts`（`127.0.0.1 localhost` 與 `::1 localhost ip6-localhost`）與 `rootfs/etc/resolv.conf`（每個 DNS 一行 `nameserver x`，只收合法 IPv4／IPv6 字面值；清單為空或全不合法時用 `1.1.1.1` 與 `8.8.8.8`）。若 `etc/hosts` 是符號連結，先刪連結再寫一般檔（不得跟隨連結寫到 rootfs 外）。DNS 來源由 Controller 用 `ConnectivityManager.getLinkProperties(activeNetwork)?.dnsServers` 取得（只需 `ACCESS_NETWORK_STATE`，已有）。
- `class DshClient(base: OkHttpClient, launch: LaunchUrl)`：自帶 cookie jar（只存 `127.0.0.1:<port>` 的 cookie）、不跟隨到其他主機；`suspend fun login()`：GET launch URL（dsh 回 303 + Set-Cookie，再到 `/`）並確認最後拿到 200；`suspend fun get(path: String): Response`、`suspend fun postJson(path: String, json: String): Response`（呼叫者負責關閉）。Task 10 心跳也會用它。
- `class HealthCheck(http: OkHttpClient, timeoutMs: Long = 60_000)`；`suspend fun check(launch: LaunchUrl): Boolean`：在逾時內重試（間隔 500 ms）直到：`login()` 成功、`/dshm/health/memory` 與 `/dshm/health/notify` 都回 200 且 body 為 `{"ok":true}`；逾時回 false。
- `data class RunningDsh(val dshPort: Int, val gatewayPort: Int, val token: SessionToken, val launch: LaunchUrl)`
- `class DshController(...)`（App 單例，服務與心跳共用；建構參數用可注入的介面以便測試，例如一個 `fun interface DshLauncher { fun start(env: Map<String,String>, args: List<String>): Process }`，正式版用 `ProotRunner`）
  - `suspend fun ensureRunning(): RunningDsh`：已在跑就直接回傳；同時多個呼叫者 → **只啟動一次**（Mutex）。rootfs 沒裝好 → 丟 `IllegalStateException("rootfs not installed")`。
  - 啟動順序：(a) 刪除殘留的 `filesDir/rootfs.old`（Task 3 遺留 minor）；(b) `SessionToken.generate()`；(c) 建 `LocalGateway`（上游 DEEPSEEK = `https://api.deepseek.com/anthropic`、ANTHROPIC = `https://api.anthropic.com`；`onNotify` → `Notifier.show`、`onCostAlert` → `Notifier.showCostAlert`、`onRateGuard` → `Notifier.showRateGuard`）並 `start()`；(d) `GuestNetworkFiles.write`；(e) 把 `DshPatch.render("http://127.0.0.1:<gwPort>/<token>")` 寫到 `rootfs/opt/dshm/dshm.cordis.yml`（檔案權限只有 owner 可讀寫）；(f) 用 launcher 啟動：`/usr/bin/env -u LD_LIBRARY_PATH node /opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --patch /opt/dshm/dshm.cordis.yml --no-open --host 127.0.0.1 --port 0`（啟動器旗標在 app 旗標之前），環境變數恰好：`DSHM_GATEWAY=http://127.0.0.1:<gwPort>/<token>`、`DSHM_WORKSPACE=/root/workspace`、`DSH_HOME=/root/.dsh`、`DSH_TELEMETRY_MODE=DISABLED`、`DEEPSEEK_API_KEY=dshm-proxy`、`ANTHROPIC_API_KEY=dshm-proxy`、`TZ=Asia/Taipei`（**不得**有真金鑰、`NODE_OPTIONS`、`DSHM_TOKEN`）；(g) 背景讀 stdout：第一個 `DshLaunchLine.parse` 成功的行就是 launch URL（60 秒內沒出現 → 視為啟動失敗）；stdout 其他行與 stderr 全部進 `ErrorLog`（tag `dsh`，經遮蔽）；(h) `HealthCheck.check` 通過才回傳，否則停掉行程與閘道並丟例外（原因寫進 ErrorLog）。
  - `fun stop()`：結束 dsh 行程（`destroy`，2 秒後 `destroyForcibly`）、`gateway.stop()`、狀態歸零；可重複呼叫。
  - `val isRunning: StateFlow<Boolean>`；`fun onExit(listener: (exitCode: Int) -> Unit)`（非 `stop()` 造成的結束才通知）。
- `class DshService : Service`：前景服務（`ServiceCompat.startForeground` 搭配 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`），常駐通知「DSH Mobile 執行中」；`onStartCommand` 呼叫 `ensureRunning()`；行程意外結束 → `CrashPolicy.onCrash()`：`RESTART` → 再 `ensureRunning()`；`GIVE_UP` → `Notifier.showCrashGiveUp()` 並停止服務。成功啟動 5 分鐘後 `CrashPolicy.reset()`。
- `class Notifier(context: Context)`：通知頻道 `dshm_messages`（代理人通知、提醒）、`dshm_alerts`（花費、限流、當掉）、`dshm_service`（常駐，低重要性）。`fun show(req: NotifyRequest)`：點擊開啟 `app.dshmobile.ui.MainActivity`（用 `ComponentName(context, "app.dshmobile.ui.MainActivity")`，該 Activity 由 Task 11a 建立），extra `"sessionId"`；`fun showCostAlert(usd: Double)`（「今日花費已超過 US$x.xx」）；`fun showRateGuard(p: Provider)`；`fun showCrashGiveUp()`：兩個動作「退版」（extra `"action"="rollback"`）、「查看錯誤記錄」（extra `"action"="export_error_log"`），都開 MainActivity；`fun serviceNotification(): Notification`。沒有通知權限時靜默略過（不丟例外）。PendingIntent 一律 `FLAG_IMMUTABLE`。通知文字用繁體中文。

- [ ] **Step 1: 寫失敗測試**：`CrashPolicy` 10 分鐘內第 3 次回 `GIVE_UP`、跨出視窗重算、`reset()` 歸零；`DshController` 同時呼叫兩次 `ensureRunning()` 只啟動一次（假 launcher 計次，假 launcher 起一個印出 launch 行的行程，HealthCheck 可注入成恆真）、傳給 launcher 的環境變數恰好是上列 7 個且不含真金鑰（vault 放 `sk-real`）、啟動參數正確、`stop()` 後 `isRunning` 為 false 且閘道埠已關、launch 行 60 秒沒出現就失敗（可注入較短逾時）；`HealthCheck`：用 MockWebServer 模擬 dsh（`/?token=` 回 303 + Set-Cookie、`/` 檢查 cookie）— 兩個健康路由都 ok → true、notify 路由 404 → false、沒帶 cookie 的請求回 401（證明有走登入）；`DshPatch`：含兩個插件絕對路徑、三個 baseURL 指向閘道、privacy 三列、不含 `sk-`；`ErrorLog`：遮蔽三種秘密、超過 1 MB 輪替且只留一個 `.1`；`GuestNetworkFiles`：DNS 清單、過濾不合法、空清單用備援、`etc/hosts` 是指向 rootfs 外的符號連結時不會寫到外面。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` 全綠且輸出乾淨；`./gradlew :app:assembleDebug` 成功。
- [ ] **Step 5:** Commit `feat: dsh controller, foreground service, crash policy`。
