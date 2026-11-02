### Task 5：本機閘道（金鑰注入、隱私剝除、花費、通知入口）

計畫原文的介面，**加上帳本 Ruling 與 `docs/dsh-findings.md` §Config／§Providers／§Search 的實測結果**整理如下。本文與計畫原文衝突時以本文為準（本文已吸收 Ruling）。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/gateway/LocalGateway.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/gateway/CostMeter.kt`、`PriceTable.kt`、`UsageParser.kt`、`RateGuard.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/security/KeyVault.kt`（介面 + `AndroidKeyVault`）
- Test: `android/app/src/test/kotlin/app/dshmobile/gateway/LocalGatewayTest.kt`、`CostMeterTest.kt`、`UsageParserTest.kt`、`PriceTableTest.kt`、`RateGuardTest.kt`
- Modify: `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`（Ktor server CIO、OkHttp、kotlinx-coroutines、MockWebServer、org.json 或 kotlinx-serialization 擇一；用 Maven Central 上存在的穩定版，寫死版號）

**Consumes:** `SessionToken`（Task 4，`android/app/src/main/kotlin/app/dshmobile/security/SessionToken.kt`，`value` 為 64 hex）。

**Interfaces（Produces）:**
- `enum class Provider { DEEPSEEK, ANTHROPIC }` — 網頁搜尋走 DeepSeek 同一把金鑰、同一個上游（findings §Search：不需第三方金鑰），所以**沒有 SEARCH**。
- `interface KeyVault { fun get(p: Provider): String?; fun put(p: Provider, key: String); fun clear(p: Provider) }`；`class AndroidKeyVault(context: Context) : KeyVault`：Android Keystore 產生的 AES-256-GCM 金鑰（alias `dshm-keyvault`）加密後存 SharedPreferences（檔名 `dshm_keys`），每筆存 IV + 密文（Base64）。不記 log。單元測試用記憶體假 vault；`AndroidKeyVault` 在 Robolectric 無 AndroidKeyStore，不必單元測試（報告註明）。
- `data class Usage(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0, val webSearchRequests: Long = 0)`
- `object UsageParser { fun fromJson(p: Provider, body: String): Usage?; fun fromSseEvent(p: Provider, data: String): UsageUpdate? }` — 兩家都是 Anthropic Messages 格式：
  - 非串流：頂層 `usage`：`input_tokens`、`output_tokens`、`cache_read_input_tokens`、`cache_creation_input_tokens`、`server_tool_use.web_search_requests`。
  - 串流：`message_start.message.usage`（開頭值）與 `message_delta.usage`（**最終／累計值**）。合併規則：**後出現事件中有的欄位覆蓋先前的值，沒有的欄位保留先前值**（Anthropic 的 message_delta 可能沒有 input_tokens；DeepSeek 的 message_delta 就是完整最終值）。**不可把 message_start 與 message_delta 相加。** `UsageUpdate` 為部分欄位（nullable）的型別，或實作者用等效設計，但合併規則要有測試。
- `class PriceTable(...)`；`fun costUsd(p: Provider, model: String, u: Usage, at: Instant): Double`。每百萬 token 美元，數字照抄（findings §Providers，2026-11-02）：
  - DeepSeek（離峰／尖峰）：`deepseek-flash` 快取命中 0.003/0.006、未命中輸入 0.15/0.30、輸出 0.60/1.20；`deepseek-v4-pro` 0.022/0.044、0.66/1.32、1.98/3.96。舊名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 按 `deepseek-flash` 計。尖峰 = **UTC 週一至週五 01:00–04:00 與 06:00–10:00**（含起點、不含終點），其他時間離峰。DeepSeek `input_tokens` 以未命中價、`cache_read_input_tokens` 以命中價、`cache_creation` 以未命中價計。搜尋：每次 `web_search_requests` 加 **US$0.00075**（$0.75/1000）。
  - Anthropic（輸入/輸出/快取讀取/快取寫入 5m）：`claude-fable-5-1` 10/50/0.25/12.50；`claude-opus-5-5` 4/20/0.20/5；`claude-sonnet-5-5` 2/10/0.10/2.50；`claude-haiku-5-5` 0.10/0.50/0.01/0.125，**當該請求總輸入（input+cache_read+cache_write）> 100,000 時**改 0.50/2.50/0.05/0.625；`claude-haiku-4-5` 1/5/0.10/1.25。搜尋：每次加 **US$0.01**（$10/1000）。
  - 未知模型：保守計價 — DeepSeek 用 `deepseek-v4-pro` 尖峰價，Anthropic 用 `claude-fable-5-1` 價。模型名比對前去掉日期尾碼以外不做模糊比對（精確比對 + 上述舊名對照表即可）。
  - 國定假日不處理（一律依星期與時刻；Ruling：高估比低估安全）。
- `class CostMeter(store: SharedPreferences, prices: PriceTable, zone: ZoneId = ZoneId.of("Asia/Taipei"), clock: Clock)`；`fun record(p: Provider, model: String, u: Usage)`；`fun todayUsd(): Double`；`fun consumeAlert(): Boolean`（當日首次 `todayUsd() > alertUsd` 回 true，之後當日都回 false；隔天重算）；`fun isBlocked(): Boolean`（`hardCapUsd != null && todayUsd() >= hardCapUsd`）；`var alertUsd = 5.0`、`var hardCapUsd: Double? = null`（兩者持久化在同一個 store）。換日依 `zone`。`record` 執行緒安全。
- `class RateGuard(maxPerMinute: Int = 120, clock: Clock)`；`fun tryAcquire(p: Provider): Boolean`（每個 provider 獨立的 60 秒滑動視窗）。
- `data class NotifyRequest(val title: String, val body: String, val sessionId: String?)`
- `class LocalGateway(token: SessionToken, vault: KeyVault, meter: CostMeter, upstreams: Map<Provider, HttpUrl>, onNotify: (NotifyRequest) -> Unit, onCostAlert: (usd: Double) -> Unit, onRateGuard: (Provider) -> Unit, rateGuard: RateGuard = RateGuard(clock = Clock.systemUTC()))`；`fun start(): Int`（只綁 `127.0.0.1`、埠 0 由系統配發，回傳實際埠）；`fun stop()`。正式上游（Task 8 傳入）：DEEPSEEK = `https://api.deepseek.com/anthropic`、ANTHROPIC = `https://api.anthropic.com`。
- 路由：
  - `/{token}/deepseek/{rest...}` → `<DEEPSEEK upstream>/{rest...}`（保留 query string，例如 Anthropic 的 `?beta=true`；dsh 對話打 `/v1/messages`，網頁搜尋打 `/v1/messages`、Files 打 `/v1/files`）。
  - `/{token}/anthropic/{rest...}` → `<ANTHROPIC upstream>/{rest...}`。
  - `POST /{token}/notify`，JSON `{"title","body","sessionId"?}` → `onNotify`，回 `{"ok":true}`；JSON 壞掉 → 400。
  - token 不符（用常數時間比較）→ 403，絕不打上游。其他路徑 → 404。
- 轉送規則（每個模型請求）：
  1. 依序檢查：`meter.isBlocked()` → 429 `{"error":"dshm_daily_cap"}`；`rateGuard.tryAcquire(p)` 失敗 → 429 `{"error":"dshm_rate_guard"}`，並呼叫 `onRateGuard(p)`（同一 provider 10 分鐘內最多呼叫一次）；vault 沒金鑰 → 401 `{"error":"dshm_missing_key","provider":"ANTHROPIC"}`（provider 用 enum 名）。
  2. 丟掉進來的 `Authorization`、`x-api-key`（dsh 送的是假值 `dshm-proxy`）、`x-deepseek-harness-user-id`、`x-deepseek-harness-session-id`、`Host`、hop-by-hop 標頭；兩家都加 **`x-api-key: <真金鑰>`**（findings：dsh 用 x-api-key，DeepSeek 兩種都收）。
  3. 若 body 是 JSON 物件：刪除頂層 `dsh_session_log`、`dsh_plugin_packages`（隱私 Ruling：所有 provider 都剝）。非 JSON body 原樣轉送。從 body 讀 `model` 供計價。
  4. 上游 OkHttp client：不跟隨轉址、不重試、無整體逾時（串流可能很長），連線逾時合理即可。
  5. 回應：狀態碼與標頭照轉（去 hop-by-hop），**body 逐塊轉送**（SSE 不可等整包），同時旁聽 SSE `data:` 行用 `UsageParser` 累積用量；非串流 JSON 用 `fromJson`。
  6. 串流正常結束**或中途斷線／客戶端取消**時，若已看到任何用量就 `meter.record(...)` **恰好一次**；然後 `if (meter.consumeAlert()) onCostAlert(meter.todayUsd())`。
  - 任何 log／例外訊息都不得含金鑰或 token。

- [ ] **Step 1: 寫失敗測試**（MockWebServer 當上游、真的 HTTP 客戶端打閘道）
```kotlin
@Test fun injectsDeepseekKeyAndStripsFake()          // 上游收到 x-api-key: real-key；沒有 dshm-proxy、沒有 Authorization、沒有 x-deepseek-harness-* 標頭
@Test fun stripsDshUploadFieldsFromBody()            // body 的 dsh_session_log、dsh_plugin_packages 被刪，其他欄位不變
@Test fun preservesPathAndQuery()                    // /{t}/anthropic/v1/messages?beta=true → 上游 /v1/messages?beta=true
@Test fun wrongTokenIs403AndNeverCallsUpstream()
@Test fun streamsSseChunkByChunk()                  // 上游分 3 塊、每塊間隔 200ms；客戶端在第 1 塊後 <150ms 內收到資料
@Test fun countsUsageFromFinalSseChunk()            // meter.todayUsd() > 0，且等於 PriceTable 依最終 usage 算出的值
@Test fun countsPartialUsageWhenStreamAborts()      // 上游送出含 usage 的塊後斷線，用量仍入帳（恰好一次）
@Test fun dailyCapReturns429WhenEnabled()
@Test fun rateGuardReturns429AndNotifiesOnce()      // 注入小上限的 RateGuard；第 N+1 個請求 429，onRateGuard 只被呼叫一次
@Test fun notifyRouteInvokesCallback()              // 收到 NotifyRequest("t","b","s1")
@Test fun missingKeyReturns401WithReadableError()   // {"error":"dshm_missing_key","provider":"ANTHROPIC"}
@Test fun costAlertFiresOnceWhenCrossingThreshold()
@Test fun bindsLoopbackOnly()
```
`CostMeterTest`：台北時間 23:59 記一筆、00:01 `todayUsd()` 歸零；`consumeAlert()` 超過 5.0 時第一次 true、第二次 false、隔天再超過又 true；`isBlocked()` 在 hardCap 為 null 時恆 false。
`PriceTableTest`：尖峰／離峰邊界（UTC 週一 00:59 離峰、01:00 尖峰、04:00 離峰、週六 02:00 離峰）；舊名對照；Haiku 5.5 >100K 分級；搜尋費；未知模型保守價。
`UsageParserTest`：message_start + message_delta 合併（Anthropic 累計、DeepSeek 最終）、非串流 JSON、`server_tool_use.web_search_requests`、非 usage 事件回 null、壞 JSON 回 null。
`RateGuardTest`：視窗滑動、provider 互不影響。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作（Ktor CIO server + OkHttp client，回應 body 以串流轉送）。
- [ ] **Step 4:** PASS；全套 `./gradlew :app:testDebugUnitTest` 也 PASS、輸出乾淨。
- [ ] **Step 5:** Commit `feat: local gateway with key injection, cost meter, notify route`。
