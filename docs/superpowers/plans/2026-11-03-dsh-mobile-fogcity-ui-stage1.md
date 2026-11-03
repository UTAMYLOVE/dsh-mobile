# DSH Mobile 霧城原生介面 第一階段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 dsh 的網頁畫面整個換掉：App 用 Jetpack Compose 畫出霧城五分頁殼（懸浮玻璃工具列）、原生對話頁（清單、串流、Markdown、工具卡、搜尋來源、權限／提問卡、附件、停止、排隊、改名刪除、模型切換）、簡易代理人清單、總覽基本版與搬進新介面的設定，並以 JankStats 門檻與驅動原生畫面的 e2e 驗證，可以發版。

**Architecture:** 新的 `DshRemote`（`app.dshmobile.remote`）用現有 `DshClient` 的 cookie 登入，單次呼叫走 HTTP `POST /api/<ns>/<method>`，串流走一條 `/api/remote.mux` WebSocket（OkHttp），`$events` 的權限／提問以 `/api/$events/result` 回答。每個 `RunningDsh` 一個 `ShellSession`（App 層、跟 dsh 同生死；planner's call：不用 androidx ViewModel——不加依賴、Activity 重建不丟狀態、dsh 換實例時整組一起丟），裡面放純 Kotlin 的 reducer／store（對話、對話清單、代理人）；Compose 畫面都是吃狀態＋回呼的無狀態元件。`MainActivity` 不再有 WebView，改畫 `FogShell`；dsh 的啟停、崩潰政策、退版、心跳全部沿用 `DshController`／`DshService`。

**Tech Stack:** Kotlin（AGP 9.5 內建 Kotlin 2.2.10）、Jetpack Compose（BOM 2026.01.01）、OkHttp 4.12 WebSocket、kotlinx-serialization-json（JsonElement，無編譯器外掛）、commonmark-java 0.32.0、androidx.metrics JankStats 1.0.0、Robolectric 4.17（Compose 測試 `@Config(sdk = [36])`）、MockWebServer（含 WebSocket 升級）、Compose UI test + UiAutomator 2.3.0（androidTest）。

**Spec:** `docs/superpowers/specs/2026-11-03-dsh-mobile-fogcity-ui-design.md`（本計畫只做 §5.10 第 1 階段）。協定以 `docs/dsh-ui-protocol.md`、`docs/dsh-remote-map.txt` 為準。前一期規格 `docs/superpowers/specs/2026-11-02-dsh-mobile-design.md` 與 `docs/sdd-ledger/progress.md` 的 Ruling 仍有效。

## Global Constraints

- 紅線不變：官方 dsh 一行不改；manifest 權限仍是那 9 個（`ManifestPermissionsTest` 不改即綠；附件只用系統選擇器 `PickVisualMedia`／`OpenMultipleDocuments`，不加任何權限）；金鑰只在 Keystore；對話與使用統計上傳關閉；dsh、閘道只綁 127.0.0.1。
- `DshRemote` 只連 `127.0.0.1:<launch port>`，沿用 `DshClient`（`Proxy.NO_PROXY`、只收 dsh origin 的 cookie、不跟隨轉址）；WebSocket 不送 `Origin` 標頭（實測：無 Origin＝接受，錯 Origin＝403）。
- launch URL、token、cookie、金鑰不得出現在任何記錄、例外訊息、畫面；dsh 錯誤碼只在符合 `[A-Za-z0-9/_.-]{1,80}` 時回顯（同 `WorkspaceRegistrar.CODE`），否則寫 `refused`。對話內容、提示、工具參數不寫進 `ErrorLog` 或 logcat。
- dsh 固定 0.2.1-rc.5。協定：單次 `{"type":"client-request","rpcId","method","payload":{"args":{…具名參數…}}}` → `{"type":"server-response","rpcId","result":{"ok":true,"value"}|{"ok":false,"error":{"code","message","details"}}}`；HTTP 非 200＝傳輸／認證失敗。mux 用戶端框只有 `open{type,streamId,endpoint,payload}`、`item{type,streamId,value?}`、`end{type,streamId}`、`cancel{type,streamId}`，**鍵集合必須完全一致**（伺服器嚴格檢查）；伺服器框 `item{streamId,value?}`、`error{streamId,error{code,message,details}}`、`end{streamId}`。cancel 後伺服器不送 end。同一連線上重複的活 `streamId` 會讓伺服器關掉整條 socket。
- 只能更新到 App 驗證過協定的 dsh 版本：`VerifiedDsh.VERSIONS = listOf("0.2.1-rc.5")`（隨 App 發佈；新增版本＝先驗證協定再改這行並發 App）。
- 花費一律用 App 閘道的 `PriceTable`／`CostMeter`；通知門檻預設 US$5；Asia/Taipei 換日、換月。
- 不保留任何「開原版 dsh 畫面」入口；主流程不得出現 `android.webkit.WebView`（e2e t1 檢查 view 樹）。
- 配色只用霧城六色＋兩個文字色（沿用 `Sakura` 物件，`ui/theme/SakuraTheme.kt`）：`#FEDFE1` 底、`#D7C4BB` 奶茶、`#DC9FB4` 粉紅（主色、發光）、`#FFFFFF`、`#A5DEE4` 水藍（**只當填色／發光，永不當文字**）、`#9E7A7A` 玫瑰褐；文字只用 `#5E4445`、次要 `#7D5F60`；圖示線條用 `#5E4445`；粉色上永遠不放白字。錯誤色沿用 `#B3261E`。樣品裡的其他色（`#6E5253`、`#2C646B`、`#8F4560`、`#3F2C2D`、`#f1c3d2`、`#e9b9c9`、`#f6d3df`、`#FFF0F4`）一律改用上述色＋透明度（planner's call：規格 §3 優先於樣品）。
- 字體：英文標題 Orbitron（可變字重 wght）、數字與英文小標籤 Chakra Petch 400/600（數字 `fontFeatureSettings = "tnum"`）、中文系統字。來源 google/fonts commit `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc`：`ofl/orbitron/Orbitron[wght].ttf`（38576 bytes，sha256 `f42db2dd16e642258e35782916eceb1dcdbea06fb958d77ad71dc5963587e8fd`）、`ofl/chakrapetch/ChakraPetch-Regular.ttf`（78488，`98fcd638baa5c81ff0316b7538ce330ee3b23b1302726de3526d5933a8ecf986`）、`ofl/chakrapetch/ChakraPetch-SemiBold.ttf`（78604，`45264de3204ddbd5fb3e14a2402acd5c630d16650ae5fc221d2c52da46a6734b`）；OFL 全文＋版權行放 `assets/licenses/fonts-OFL.txt`（planner's call：Android 不吃 `@dshm/ui` 用的 WOFF2，改用 Google Fonts 官方 TTF 並鎖 commit 與雜湊）。
- 圓角：面板 22dp、工具列／彈出卡／確認卡 30dp、使用者與 AI 氣泡 22dp（靠邊角 8dp）、搜尋卡 18dp、縮圖與分組格 16dp、欄位 12dp、按鈕／輸入框／分頁膠囊／晶片全圓（`RoundedCornerShape(50)`）。
- 玻璃（`Modifier.glass`）：底色 140° 線性漸層 白α.62 → 白α.16@60% → 白α.28；背後模糊 10dp＋飽和度 1.7（只在 `Effects.blur`）；邊框 1.2dp 140° 漸層 白α.95 → `#DC9FB4`α.75@50% → `#A5DEE4`α.90；左上高光 radial（中心 0,0、半徑 120%×70%）白α.55 → 透明@45%；陰影 0/12/30 `#9E7A7A`α.16 ＋ 0/2/6 `#9E7A7A`α.08。無模糊時（平衡／省電）三個白色停點各加 0.16。
- 背景：底 `#FEDFE1`；三團光暈（徑向漸層，不用 blur 濾鏡，α.75）b1 300dp 粉紅 top −60 right −90、18s 往返 → 位移(−70,80) 縮放 1.12；b2 260dp 水藍 bottom 120 left −110、22s → (90,−60) 0.9；b3 240dp 奶茶 top 42% right −70、26s → (−80,−40) 1.15；上下各一片透視網格：高 42%、rotateX 68°、格 44dp、線 1dp 白α.95、整片 α.55、6s 線性流動一格、往遠處 10%→85% 淡出。
- 版面：頁面內距 上 safe+92、左右 16、下 safe+196（工具列高度），面板間距 14；面板內距 16/18/18。標頭：左「DSH」＋小字「MOBILE」（Orbitron 18sp、字距 .26em）、下方時間 11sp Chakra Petch .12em；右側頁名滾輪 15sp＋五個點（8×4dp，目前頁 22dp 寬粉紅發光）。面板標題：7dp 方點（圓角 2、粉紅或水藍、發光 6）、中文 13sp `#5E4445` 字距 .12em、英文 11sp Chakra Petch .2em `#7D5F60`、漸層細線 1dp `#9E7A7A`α.45 → 透明、右側 meta 11sp Chakra Petch .14em。
- 工具列：左右 12dp、底 safe+12、圓角 30、內距 12/12/8，玻璃。輸入列間距 10：輸入框高 48 全圓、邊 白α.9、底 白α.5、15sp、提示字 `#7D5F60`「問點什麼…」；圓鈕 48dp：附件 底 `#A5DEE4`α.40 邊 α.95 發光 α.70 半徑 14；送出 徑向（35%,30%）`#FEDFE1` → `#DC9FB4`、陰影 0/6/18 `#DC9FB4`α.85；圖示線 `#5E4445`。分頁列上距 8、每格內距 8/7：中文 15sp/500 字距 .08em（目前頁 `#5E4445`、其他 `#7D5F60`）、英文 9sp Chakra Petch .16em；滑動膠囊 寬＝1/分頁數、140° 白α.85 → 白α.45、陰影 0/4/14 `#9E7A7A`α.18＋1dp 內框 白α.9。分頁多於 5 個時分頁列可橫向捲動。
- 確認卡（權限／提問）：左右 12、底 safe+12（蓋在工具列上）、圓角 30、內距 20/20/18、底 140° 白α.88 → `#FEDFE1`α.70；遮罩 `#5E4445`α.28；標題 18sp/500 字距 .1em、英文副標 11sp Chakra Petch .24em。
- 對話：標頭標題 17sp/500 .06em；模型晶片 11sp Chakra Petch .14em、內距 5/12、底 白α.6、邊 `#DC9FB4`α.6；「☰ 對話」鈕 高 36 全圓 同色、13sp。訊息間距 12、最寬 88%、內距 12/16、15sp 行高 1.7。我方氣泡 135° `#FEDFE1` → `#DC9FB4`、陰影 0/6/18 `#DC9FB4`α.55、字 `#5E4445`；AI 氣泡＝玻璃。搜尋卡 寬 92%、內距 12/14、底 `#A5DEE4`α.22、邊 α.85；標題 12sp Chakra Petch .14em＋8dp 水藍發光點；來源晶片 12sp、內距 4/10、底 白α.7、間距 6；來源標註 11sp Chakra Petch .08em `#7D5F60`。清單項：上下 12、底線虛線 1dp `#9E7A7A`α.25、8dp 粉紅發光點、標題 16sp、小字 12sp `#7D5F60`；搜尋框 高 44 全圓 白α.6；分組標 11sp Chakra Petch .2em。
- 儀表（照 `fogcity2-hud.js`）：240 單位圓環：刻度環 r114 `#9E7A7A` 線寬 7 虛線 1.2/10.74（40s 自轉）、軌道 r100 `#9E7A7A`α.18 寬 4、數值弧 r100 `#DC9FB4` 寬 5 圓頭＋7dp 粉紅發光（從 −90° 起畫）、水藍虛線環 r87 寬 1.5 虛線 3/9（26s 反轉）。進度條 高 7 全圓 底 `#9E7A7A`α.14，填色 90° 粉紅α.6 → 粉紅（或水藍）＋發光 10。分段控制：三格、間距 4、內距 4、全圓 底 白α.45；鈕 內距 9 14sp `#7D5F60`；選中 140° 白 → 白α.6、`#5E4445`、陰影 0/3/10 `#9E7A7A`α.18。
- 動態（照樣品）：ease-out `cubic-bezier(.22,1,.36,1)`、spring `(.34,1.56,.64,1)`、soft `(.2,.8,.2,1)`。換頁 560ms ease-out：離開頁 → X −dir·18%、rotateY dir·10°、縮 .9、透明、模糊 8；進入頁從 X dir·72%、rotateY −dir·24°、透明 → 原位；掃光線 2dp 白＋粉紅光暈、620ms、skewX −12°。分頁膠囊 550ms spring；確認卡 550ms spring 從 translateY 115%；遮罩 350ms。面板升起 600ms ease-out 從 Y+26dp、縮 .98、透明，第 i 個延遲 120+70·i ms（i 最多 6）。數字遞增 950ms（1−(1−t)³）；圓環描繪 1300ms soft 延遲 200；進度條 1100ms ease-out。
- 效果強度（設定「效果強度」：全開／平衡／省電，預設全開；各等級內容與 8 秒閒置減速為 planner's call，照規格「先關模糊，再關背景動畫」的順序切）：全開＝模糊＋發光＋背景動畫＋轉場；平衡＝關模糊，其餘照舊；省電＝關模糊、關背景動畫與圓環自轉、換頁改 200ms 淡入淡出、數字不遞增。系統「移除動畫」（`ValueAnimator.areAnimatorsEnabled() == false`）＝不論等級都關背景動畫、轉場與遞增（頁面直接出現）。只有目前分頁、且 Activity 至少 STARTED 時才跑任何動畫；8 秒無觸控後背景動畫速度 ×0.25。偵測掉格自動降級：全開 → 平衡 → 省電（只降不升，直到使用者再改設定或程序重啟）。
- 介面文字一律繁體中文（樣式字串放 `res/values/strings.xml`）；e2e 用 `Tags` 物件裡的 testTag，不用中文字串找元件。
- 新依賴（`gradle/libs.versions.toml`）：`org.commonmark:commonmark`、`commonmark-ext-gfm-tables`、`commonmark-ext-gfm-strikethrough` 皆 `0.32.0`（BSD-2-Clause，授權放 `assets/licenses/commonmark-BSD-2.txt`）；`androidx.metrics:metrics-performance:1.0.0`；androidTest：Compose BOM＋`ui-test-junit4`、`espresso-core 3.7.0`、`androidx.test.uiautomator:uiautomator:2.3.0`。不加其他第三方函式庫。
- Robolectric 的 Compose 測試一律 `@Config(sdk = [36])`；單元測試 `cd android && ./gradlew :app:testDebugUnitTest`；instrumentation 只在 CI 模擬器跑（本地只要 `:app:assembleDebugAndroidTest` 編得過）。
- 與另一份工作的重疊：HEAD 已含過渡換皮 ad4996a（`SakuraTheme.kt`、`MainActivity.kt`、`MainScreen.kt`、`SettingsScreen.kt`、`OnboardingScreen.kt`、`E2eSupport.kt`、`E2eTest.kt`、`plugins/packages/ui`）。本計畫不改 `SakuraTheme.kt`（`FogTheme` 包住它）與 `plugins/`（planner's call：`@dshm/ui` 留著不動——改 plugins 會改變 rootfs 雜湊，逼每台機重裝 130 MB rootfs；第四階段本來就要改 plugins 時再處理）；`MainActivity.kt`、`MainScreen.kt`、`SettingsScreen.kt`、`E2eSupport.kt`、`E2eTest.kt` 會被本計畫改寫，動手前先 rebase 到最新 HEAD。

## Review Focus

1. **斷線或 dsh 換了一個實例時正好有權限詢問／正在串流**（App 在背景太久 socket 被 2 秒心跳踢掉、`DshService` 重開 dsh）→ 介面自己重連、重開所有串流，對話不重複也不漏；同一個 `eventId` 的詢問只出現一張卡，別處已回答的不會復活 → Task 2（`aRedeliveredPromptKeepsOneCard`、`promptsNotRedeliveredAfterReconnectAreDropped`）、Task 5（`aSecondSnapshotReplacesItemsWithoutDuplicates`）、Task 15（t23）。
2. **目前的 dsh 是 v0.1.x「無條件更新」裝進來的未驗證版本**（rootfs 沒變就不會被重設）→ 設定與總覽顯示警告，「更新」換回最新驗證版；上一版未驗證時「退版」不可按、通知的退版也不退到它 → Task 13（`anUnverifiedCurrentVersionWarnsAndUpdateSwitchesToTheNewestVerified`、`rollbackIsDisabledWhenThePreviousVersionIsUnverified`）。
3. **怪或很長的模型輸出**：10 萬字回覆、20 欄表格、串流到一半沒關的 ``` 、原始 HTML、Markdown 圖片 → 不當機不卡死，表格與程式碼橫向捲動，不從網路載任何圖片 → Task 7。
4. **附件出錯**：超過上限的檔案、無法讀取的 content URI、選擇器取消、dsh 拒絕圖片（模型不支援）→ 看得到的錯誤訊息，什麼都不偷偷丟掉，上傳失敗時不送出半套提示 → Task 9。
5. **協定漂移**：未知的框或事件類型、缺欄位、`ok:false` 帶錯誤碼 → 忽略或顯示含錯誤碼的可讀訊息，絕不當機、不回顯 token → Task 1（`aBusinessErrorThrowsWithASafeCode`）、Task 5（`unknownAndMalformedFramesNeverThrow`）。

---

## 檔案結構

新增（`android/app/src/main/kotlin/app/dshmobile/` 之下）：
- `remote/Rpc.kt`（單次呼叫信封、`DshRpcException`）、`remote/MuxFrames.kt`（mux 框）、`remote/MuxConnection.kt`（WebSocket、串流、重連）、`remote/DshEvents.kt`（`$events`、詢問、主機事件）、`remote/Model.kt`（`SessionSummary`、`PromptPart`…）、`remote/DshRemote.kt`（門面＋型別化呼叫）。
- `runtime/VerifiedDsh.kt`。
- `chat/ConversationState.kt`、`chat/ConversationReducer.kt`、`chat/ConversationStore.kt`、`chat/SessionIndex.kt`、`chat/Attachments.kt`。
- `agents/RunningAgents.kt`。
- `overview/OverviewSource.kt`、`overview/ReminderLog.kt`。
- `ui/fog/`：`FogTokens.kt`、`FogTheme.kt`（字體、`Effects`、CompositionLocal）、`Glass.kt`、`FogBackground.kt`、`FogPanel.kt`、`Hud.kt`（圓環、進度條、遞增數字）、`FogControls.kt`（按鈕、分段控制）、`JankGovernor.kt`、`UiPrefs.kt`。
- `ui/shell/`：`ShellTab.kt`、`Tags.kt`、`ShellSession.kt`（含 `ShellSessionHolder`）、`ShellNav.kt`、`FogShell.kt`、`Dock.kt`、`StatusPanel.kt`、`PromptSheet.kt`、`SettingsEnvFactory.kt`。
- `ui/chat/`：`ConversationListScreen.kt`、`ChatScreen.kt`、`MessageViews.kt`、`ExternalLinks.kt`。`ui/markdown/Markdown.kt`。`ui/agents/AgentsScreen.kt`。`ui/overview/OverviewScreen.kt`。
- `res/font/orbitron.ttf`、`chakra_petch_regular.ttf`、`chakra_petch_semibold.ttf`；`assets/licenses/fonts-OFL.txt`、`commonmark-BSD-2.txt`。

修改：`runtime/DshClient.kt`（`openWebSocket`）、`runtime/DshVersions.kt`（指定目標版本）、`gateway/CostMeter.kt`（分供應商、月計、revision）、`config/AgentSettings.kt`（顯示名稱）、`config/AgentSettingsStore.kt`（`novelModel`）、`heartbeat/HeartbeatScheduler.kt`（下次執行時間）、`DshMobileApp.kt`、`ui/MainActivity.kt`（改寫）、`ui/MainOps.kt`、`ui/MainScreen.kt`（只留 `MainUiState`）、`ui/SettingsScreen.kt`、`AndroidManifest.xml`、`res/values/strings.xml`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、androidTest 的 `E2eSupport.kt`／`E2eTest.kt`／`FakeModel.kt`、test 的 `runtime/FakeDshServer.kt`、`ui/MainActivityTest.kt`、`ui/SettingsScreenTest.kt`、`ui/MainOpsTest.kt`、`gateway/CostMeterTest.kt`。

刪除：`ui/DshWebViewClient.kt`、`ui/DshChromeClient.kt`、`ui/DownloadHandler.kt`、`ui/SettingsActivity.kt`（＋其 manifest 項目、`MainActivity.SETTINGS_ACTIVITY`）及其測試 `DshWebViewClientTest`、`DownloadHandlerTest`、`SettingsActivityTest`、`MainScreenTest`；`androidTest/.../ui/MainActivityTest.kt`（planner's call：它只驗 WebView 載入，由 E2eTest.t1 取代）。

---

### Task 1: 協定信封與單次呼叫（DshRemote 前半）

**Files:**
- Create: `remote/Rpc.kt`、`remote/Model.kt`、`remote/DshRemote.kt`
- Test: `test/.../remote/RpcEnvelopeTest.kt`、`test/.../remote/DshRemoteCallTest.kt`

**Interfaces:**
- Consumes: `DshClient(base: OkHttpClient, launch: LaunchUrl)`、`login()`、`postJsonForText(path, json): TextResponse`；測試用 `FakeDshServer(rpc = …)`。
- Produces:
  ```kotlin
  object RpcEnvelope {
      fun request(method: String, args: JsonObject, rpcId: String): String
      fun parse(body: String): RpcResult
  }
  sealed interface RpcResult { data class Ok(val value: JsonElement) : RpcResult; data class Err(val code: String, val message: String) : RpcResult }
  class DshRpcException(val code: String, message: String) : java.io.IOException(message)
  data class SessionSummary(val id: String, val parentId: String?, val origin: String?, val updatedAt: Long, val running: Boolean,
      val blank: Boolean, val title: String?, val lastLine: String?, val model: String?, val label: String?) {
      companion object { fun from(json: JsonObject): SessionSummary? }
  }
  data class SearchHit(val sessionId: String, val snippet: String)
  sealed interface PromptPart {
      data class Text(val text: String) : PromptPart
      data class Image(val mediaType: String, val base64: String, val name: String?) : PromptPart
      data class File(val receiptId: String, val name: String) : PromptPart
  }
  enum class PromptMode(val wire: String) { QUEUE("queue"), STEER("steer") }
  class DshRemote(client: DshClient, scope: CoroutineScope, zone: ZoneId = ZoneId.systemDefault()) {
      suspend fun call(method: String, args: JsonObject): JsonElement          // 失敗丟 DshRpcException（ok:false）或 IOException（非 200）
      suspend fun listSessions(): List<SessionSummary>                         // session/list {_request:{}}
      suspend fun searchSessions(query: String): List<SearchHit>               // session/search {request:{query}}
      suspend fun createSession(workspaceId: String?): String                  // {request:{workspaceId}}；null 時 {request:{cwd:"/root/workspace"}}
      suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>, mode: PromptMode)
      suspend fun cancel(sessionId: String)                                    // session/cancel {request:{sessionId}}
      suspend fun rename(sessionId: String, title: String): String            // session/rename → value.title
      suspend fun archive(sessionId: String)                                   // workspace/archiveSession {request:{sessionId, stopActivity:true}}
      suspend fun selectModel(sessionId: String, provider: String, model: String) // session/selectModel {request:{sessionId,provider,model}}
      suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String // fileUploads/upload {agentId, request:{data(base64), name}} → receiptId
      suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement // session/page {request:{address:{kind:"session",sessionId}, beforeSeq, maxMessages}}
      suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) // subagents/interruptByParent {childSessionId,parentSessionId,mode:"continuable"}
      fun close()
  }
  ```
  `SessionSummary.from` 取：`sessionId`、`parentSessionId`、`origin`、`updatedAt`、`running`、`blank`、`projections.values.title`、`projections.values.turnOutline` 最後一筆的 `response ?: prompt`、`projections.values.modelSelection.lastUsed.model`、`projections.values.subagent.label`；缺任何選填欄位就是 null，缺 `sessionId` 回 null。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class RpcEnvelopeTest {
    @Test fun requestHasExactlyTheProtocolKeys() {
        val o = Json.parseToJsonElement(RpcEnvelope.request("session/list", buildJsonObject { putJsonObject("_request") {} }, "r1")).jsonObject
        assertEquals(setOf("type", "rpcId", "method", "payload"), o.keys)
        assertEquals("client-request", o["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("args"), o["payload"]!!.jsonObject.keys)
    }
    @Test fun okResultReturnsTheValue()            // {"result":{"ok":true,"value":{"items":[]}}} → Ok({"items":[]})
    @Test fun okWithoutValueIsJsonNull()           // {"result":{"ok":true}} → Ok(JsonNull)
    @Test fun errResultCarriesCodeAndMessage()     // gateway/internal / "spawn xdg-user-dir ENOENT"
    @Test fun garbageIsAnErrNotAThrow()            // "<html>" → Err("unexpected-answer", …)
}
class DshRemoteCallTest {  // FakeDshServer 回放 rpc；檢查 recorded request
    @Test fun callPostsToApiNamespaceMethodWithTheCookie()        // path == "/api/session/list"、Cookie 含 dsh-auth-x=signed、unauthenticated 為空
    @Test fun aBusinessErrorThrowsWithASafeCode()                 // ok:false code "workspace/invalid-path" → DshRpcException.code 同；code "http://127.0.0.1:1/?token=x" → code "refused"，message 不含 "token"
    @Test fun aNon200IsAnIOExceptionWithoutTheLaunchUrl()         // 500 → IOException，message 不含 "token="、不含 port
    @Test fun typedCallsSendTheDocumentedArgs()                   // 逐一比對 payload.args JSON：listSessions、createSession("ws1")、createSession(null)、cancel、rename、archive、selectModel、interruptSubagent、searchSessions、page
    @Test fun promptSendsQueueModeContentPartsAndTimeZone() {
        // parts = [Text("hi"), Image("image/png","AAAA","a.png"), File("rcpt-1","n.txt")]
        // → {"request":{"requestId":"q1","sessionId":"s1","mode":"queue","content":[{"type":"text","text":"hi"},
        //    {"type":"image","mediaType":"image/png","data":"AAAA","name":"a.png"},{"type":"file","receiptId":"rcpt-1"}],"clientTimeZone":"Asia/Taipei"}}
    }
    @Test fun uploadSendsBase64AndReturnsTheReceipt()             // args {"agentId":"s1","request":{"data":"aGk=","name":"n.txt"}} → "rcpt-1"
    @Test fun listSessionsParsesSummaries()                       // 一筆含 projections（title、turnOutline、modelSelection、subagent.label）、一筆只有 sessionId → 兩個 SessionSummary，缺的欄位為 null
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'app.dshmobile.remote.*'`
Expected: FAIL（`RpcEnvelope` 未定義）

- [ ] **Step 3: 實作 `remote/Rpc.kt`、`remote/Model.kt`、`remote/DshRemote.kt`（只到單次呼叫）**

`call` 先 `client.login()` 一次（之後重用 cookie；HTTP 401 時再登入重試一次），`rpcId = "dshm-" + UUID`。`clientTimeZone = zone.id`。錯誤碼過濾規則見 Global Constraints。

- [ ] **Step 4: 跑測試確認通過**（同 Step 2 指令，Expected: PASS）

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/app/dshmobile/remote android/app/src/test/kotlin/app/dshmobile/remote
git commit -m "feat: DshRemote unary RPC (envelope, typed session calls)"
```

---

### Task 2: mux 串流、重連與 `$events`

**Files:**
- Create: `remote/MuxFrames.kt`、`remote/MuxConnection.kt`、`remote/DshEvents.kt`
- Modify: `runtime/DshClient.kt`（加 `openWebSocket`）、`remote/DshRemote.kt`（加串流與 events）、`test/.../runtime/FakeDshServer.kt`（WebSocket）
- Create (test): `test/.../runtime/FakeMux.kt`、`test/.../remote/MuxFramesTest.kt`、`MuxConnectionTest.kt`、`DshEventsTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `DshRemote.call`、`DshRpcException`、`SessionSummary.from`。
- Produces:
  ```kotlin
  // DshClient
  fun openWebSocket(path: String, listener: WebSocketListener): WebSocket   // 同一個 client（cookie jar、NO_PROXY）；不加 Origin
  // MuxFrames
  object MuxFrames {
      fun open(streamId: String, endpoint: String, args: JsonObject): String
      fun item(streamId: String, value: JsonElement?): String   // value 為 null 時不放 value 鍵
      fun end(streamId: String): String
      fun cancel(streamId: String): String
      fun parse(text: String): ServerFrame?                     // 不認得或壞掉 → null
  }
  sealed interface ServerFrame { val streamId: String
      data class Item(override val streamId: String, val value: JsonElement?) : ServerFrame
      data class Error(override val streamId: String, val code: String, val message: String) : ServerFrame
      data class End(override val streamId: String) : ServerFrame }
  enum class MuxState { CONNECTING, OPEN, RECONNECTING, CLOSED }
  sealed interface StreamSignal {
      data class Value(val value: JsonElement?) : StreamSignal
      data object Reopened : StreamSignal          // 重連後、重新 open 前送出；之後第一個 Value 是新的 baseline/snapshot
      data class Failed(val code: String, val message: String) : StreamSignal }
  class MuxConnection(open: (WebSocketListener) -> WebSocket, relogin: suspend () -> Unit, scope: CoroutineScope,
                      backoffMs: List<Long> = BACKOFF_MS) {
      val state: StateFlow<MuxState>
      fun stream(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<StreamSignal> // 冷 flow：收集＝open，取消＝cancel，伺服器 end＝完成
      internal fun dropForTest()
      fun close()
      companion object { val BACKOFF_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L) }
  }
  sealed interface PendingPrompt { val eventId: String; val agentId: String
      data class Approval(override val eventId: String, override val agentId: String, val toolName: String, val callId: String?, val reason: String) : PendingPrompt
      data class Question(override val eventId: String, override val agentId: String, val questions: List<AskQuestion>) : PendingPrompt }
  data class AskQuestion(val id: String, val header: String?, val question: String, val options: List<String>, val multiSelect: Boolean)
  sealed interface HostEvent {
      data class SessionAdded(val summary: SessionSummary) : HostEvent
      data class SessionStatus(val sessionId: String, val running: Boolean) : HostEvent
      data class SessionRemoved(val sessionId: String) : HostEvent }
  class DshEvents(mux: MuxConnection, call: suspend (String, JsonObject) -> JsonElement, scope: CoroutineScope, redeliveryGraceMs: Long = 3_000) {
      val clientId: StateFlow<String?>
      val prompts: StateFlow<List<PendingPrompt>>      // 依到達順序
      val hostEvents: SharedFlow<HostEvent>
      suspend fun allowOnce(p: PendingPrompt.Approval)  // outcome {kind:"result", value:"allowed-once"}
      suspend fun reject(p: PendingPrompt.Approval)     // value "rejected"
      suspend fun answer(p: PendingPrompt.Question, selected: Map<String, List<String>>) // value {answers:[{id, selected:[label…]}]}
      suspend fun decline(p: PendingPrompt.Question)    // outcome {kind:"rejected", error:{name:"UserDeclined", message:"declined in DSH Mobile"}}
  }
  // DshRemote 新增
  val mux: MuxConnection; val events: DshEvents
  fun follow(sessionId: String): Flow<StreamSignal>  // session/follow {request:{address:{kind:"session",sessionId}, assistantStream:true, maxMessages:500, turnWindow:{minMessages:50,minTurns:2}}}
  fun control(): Flow<StreamSignal>                  // session/control {}
  fun workspaces(): Flow<StreamSignal>               // workspace/follow {}
  ```
  測試替身（後面任務都用）：`class FakeMux { val received: List<JsonObject>; fun send(json: String); fun sendItem(streamId: String, value: String); fun closeAll(); var rejectNextUpgradeWith: Int?; var onOpen: (streamId: String, endpoint: String, args: JsonObject) -> Unit }`，`FakeDshServer(..., mux: FakeMux? = null)` 對已登入的 `/api/remote.mux` 回 `withWebSocketUpgrade`。

重連（planner's call：同一個 dsh 實例就一直重試，退避 250ms→5s 封頂；換了 dsh 實例由 `ShellSessionHolder` 換新物件，舊的 `close()`）：socket 斷 → `state = RECONNECTING` → 依 `backoffMs` 等待（用完後一直用最後一個值）→ 升級回 401 時先 `relogin()` → 成功後對每個仍在收集的串流先發 `Reopened`，再用**原 streamId** 重送 `open`。新 streamId 在同一個 `MuxConnection` 內永不重用（`"s1"`, `"s2"`…）。`DshEvents` 在 `Reopened` 時把 clientId 清掉並把現有詢問標記待確認；新的 `ready` 之後 `redeliveryGraceMs` 內沒有被重送（同 eventId）的詢問就移除（planner's call：dsh 會重送仍在等的 waterfall，沒重送＝已在別處回答）。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class MuxFramesTest {
    @Test fun clientFramesHaveExactKeySets()     // open: {type,streamId,endpoint,payload{args}}；item(null) 無 value 鍵；end/cancel 只有 {type,streamId}
    @Test fun serverFramesParse()                // item 有/無 value、error 帶 code/message、end
    @Test fun unknownOrBrokenFramesAreNull()     // {"type":"ping"}、"[]"、"not json"、缺 streamId → null
}
class MuxConnectionTest {   // FakeDshServer(mux = FakeMux())，backoffMs = listOf(0)
    @Test fun streamOpensWithAFreshIdAndRoutesItsItems()        // 兩個串流各收自己的 item；FakeMux.received 的 open 鍵集合正確
    @Test fun anErrorFrameFailsOnlyThatStream()                 // 收到 Failed(code,…) 後該 flow 完成，另一條照常
    @Test fun cancellingTheCollectorSendsCancel()
    @Test fun endCompletesTheFlow()
    @Test fun aDroppedSocketReconnectsAndReopensLiveStreams() {
        // closeAll() → state 經 RECONNECTING 回 OPEN；收集者看到 Reopened；FakeMux 收到同 streamId 的第二個 open；之後的 item 照常到
    }
    @Test fun a401UpgradeLogsInAgainBeforeRetrying()            // rejectNextUpgradeWith = 401 → relogin 被叫一次，之後 OPEN
    @Test fun closeStopsReconnectingAndFailsNothingLoudly()      // close() 後 closeAll() 不再有升級請求；state CLOSED
    @Test fun failuresNeverCarryTheLaunchUrl()                  // 伺服器關閉後所有 Failed.message、記錄字串不含 "token="
}
class DshEventsTest {
    @Test fun readyGivesTheClientIdUsedForAnswers() {
        // ready{clientId:"c1"} + waterfall approval/request eventId "e1" → allowOnce →
        // POST /api/$events/result args == {"clientId":"c1","eventId":"e1","outcome":{"kind":"result","value":"allowed-once"}}；之後 prompts 為空
    }
    @Test fun approvalPrefersTheChineseDisplayReason()     // displayReason.zh 優先，否則 en，否則 reason
    @Test fun questionsParseIdsHeadersAndOptions()         // user-questions/request → Question(q1,"Choose","Which color?",["Pink","Blue"],false)
    @Test fun answerSendsSelectedLabels()                  // {"answers":[{"id":"q1","selected":["Pink"]}]}
    @Test fun cancelRemovesThePrompt()                     // {type:"cancel",eventId:"e1"}
    @Test fun aRedeliveredPromptKeepsOneCard()             // 同 eventId 送兩次 → prompts.size == 1
    @Test fun promptsNotRedeliveredAfterReconnectAreDropped() // e1、e2 等待中 → 斷線重連 → 新 ready 後只重送 e1 → grace(0) 後只剩 e1
    @Test fun aFailedAnswerKeepsThePromptAndThrows()       // $events/result 回 ok:false → DshRpcException，prompts 仍含 e1
    @Test fun sessionLifecycleEmitsBecomeHostEvents()      // emit api-session/added args[{sessionId,parentSessionId,origin:"subagent",running:true,projections}] → SessionAdded；status args["x",false] → SessionStatus；removed args["x"] 或 [{sessionId:"x"}] → SessionRemoved
}
```

- [ ] **Step 2: 跑測試確認失敗**：`./gradlew :app:testDebugUnitTest --tests 'app.dshmobile.remote.*'` → FAIL
- [ ] **Step 3: 實作上述介面**（OkHttp `WebSocketListener`；所有框在 `Dispatchers.IO` 解析，分派進每條串流的 `Channel(UNLIMITED)`；`DshRemote` 建構時啟動 `events`）
- [ ] **Step 4: 跑測試確認通過**（PASS；Task 1 的測試仍綠）
- [ ] **Step 5: Commit** `feat: mux streams with reconnect and $events prompts`

---

### Task 3: 霧城設計系統（字體、色票、玻璃、背景、效果等級）

**Files:**
- Create: `ui/fog/FogTokens.kt`、`FogTheme.kt`、`Glass.kt`、`FogBackground.kt`、`FogPanel.kt`、`Hud.kt`、`FogControls.kt`、`UiPrefs.kt`；`res/font/orbitron.ttf`、`chakra_petch_regular.ttf`、`chakra_petch_semibold.ttf`；`assets/licenses/fonts-OFL.txt`
- Test: `test/.../ui/fog/FogTokensTest.kt`、`EffectsTest.kt`、`FontsTest.kt`、`FogComponentsTest.kt`、`UiPrefsTest.kt`

**Interfaces:**
- Consumes: `Sakura`、`SakuraTheme`（不改）。
- Produces:
  ```kotlin
  object FogMotion { val EaseOut: Easing; val Spring: Easing; val Soft: Easing
      const val PAGE_MS = 560; const val SAVER_PAGE_MS = 200; const val SWEEP_MS = 620; const val PILL_MS = 550; const val SHEET_MS = 550
      const val SCRIM_MS = 350; const val RISE_MS = 600; const val STAGGER_MS = 70; const val STAGGER_BASE_MS = 120; const val STAGGER_MAX = 6
      const val COUNT_MS = 950; const val RING_MS = 1300; const val RING_DELAY_MS = 200; const val METER_MS = 1100
      const val IDLE_AFTER_MS = 8_000L; const val IDLE_SPEED = 0.25f }
  object FogDimens { val Panel = 22.dp; val Sheet = 30.dp; val BubbleTail = 8.dp; val SearchCard = 18.dp; val Thumb = 16.dp
      val DockSide = 12.dp; val DockHeight = 196.dp; val HeaderHeight = 92.dp; val Round = 48.dp }
  object FogFonts { val Orbitron: FontFamily; val ChakraPetch: FontFamily }   // ChakraPetch: 400 Regular、600 SemiBold；Orbitron 用 FontVariation weight 500/700
  object FogText { val Number: TextStyle; val Label: TextStyle; val Heading: TextStyle }   // Number = ChakraPetch 600 + "tnum"
  enum class EffectLevel { FULL, BALANCED, SAVER }
  data class Effects(val blur: Boolean, val glow: Boolean, val ambient: Boolean, val transitions: Boolean, val countUp: Boolean, val pageMs: Int)
  fun effectsFor(level: EffectLevel, reducedMotion: Boolean): Effects
  fun systemReducedMotion(): Boolean                       // !ValueAnimator.areAnimatorsEnabled()
  val LocalEffects: ProvidableCompositionLocal<Effects>
  val LocalBackdrop: ProvidableCompositionLocal<Backdrop?>
  val LocalPageActive: ProvidableCompositionLocal<Boolean> // 目前分頁且 Activity ≥ STARTED
  val LocalIdle: ProvidableCompositionLocal<Boolean>
  @Composable fun FogTheme(effects: Effects, content: @Composable () -> Unit)   // SakuraTheme + FogFonts typography + locals
  class Backdrop           // 背景層的 GraphicsLayer＋在視窗中的位置
  @Composable fun rememberBackdrop(): Backdrop
  @Composable fun FogBackground(backdrop: Backdrop, modifier: Modifier = Modifier)   // 自己讀 LocalEffects/LocalPageActive/LocalIdle
  fun Modifier.glass(shape: Shape): Modifier               // Modifier.Node + CompositionLocalConsumerModifierNode
  @Composable fun FogPanel(title: String?, en: String? = null, meta: String? = null, ice: Boolean = false, index: Int = 0,
                           modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
  @Composable fun HudRing(progress: Float, size: Dp, modifier: Modifier = Modifier, center: @Composable () -> Unit)
  @Composable fun Meter(progress: Float, ice: Boolean = false, modifier: Modifier = Modifier)
  @Composable fun CountUpText(value: Double, format: (Double) -> String, style: TextStyle, modifier: Modifier = Modifier)
  enum class FogButtonKind { PINK, AQUA, GHOST }
  @Composable fun FogButton(text: String, kind: FogButtonKind, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)
  @Composable fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, tagPrefix: String, modifier: Modifier = Modifier)
  object FogSemantics { val Blur: SemanticsPropertyKey<Boolean>; val Animating: SemanticsPropertyKey<Boolean> }
  class UiPrefs(prefs: SharedPreferences) { var effectLevel: EffectLevel /* 預設 FULL */; var lastSessionId: String?; companion object { const val PREFS = "dshm_ui" } }
  ```
  玻璃模糊（planner's call：不引入 Haze 等第三方，只模糊「背景層」而不是捲在下面的內容）：`FogBackground` 把光暈與網格錄進 `GraphicsLayer`；`glass` 在自己的形狀內把那個圖層平移到自己的位置，加 `RenderEffect`（`BlurEffect(10dp)` 串 `ColorFilter` 飽和度 1.7）畫在底下，再疊漸層、邊框、高光。只在 `Effects.blur` 時做；`FogSemantics.Blur` 標記實際是否有模糊。`FogBackground` 的 `FogSemantics.Animating` = `ambient && pageActive`。所有數值見 Global Constraints。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class FogTokensTest {
    @Test fun easingsMatchTheMockup()      // EaseOut.transform(0.5f) == CubicBezierEasing(.22f,1f,.36f,1f).transform(0.5f)，Spring、Soft 同理
    @Test fun dimensionsMatchTheSpec()     // Panel 22.dp、Sheet 30.dp、DockHeight 196.dp、HeaderHeight 92.dp
    @Test fun motionTimingsMatchTheMockup()// PAGE_MS 560、PILL_MS 550、RISE_MS 600、STAGGER 70/120/6、COUNT 950、RING 1300/200、METER 1100
}
class EffectsTest {
    @Test fun fullHasEverything()          // effectsFor(FULL,false) == Effects(true,true,true,true,true,560)
    @Test fun balancedOnlyDropsBlur()      // (false,true,true,true,true,560)
    @Test fun saverDropsBlurAmbientAndCountUp() // (false,true,false,true,false,200)
    @Test fun reducedMotionStopsAllMotionAtEveryLevel() // 每個等級 ambient/transitions/countUp 皆 false；blur 照等級
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class FontsTest {
    @Test fun bundledFontsAreThePinnedFiles()   // R.font.orbitron 等三檔 openRawResource 的 sha256 == Global Constraints 的值
    @Test fun theOflNoticeShipsWithBothCopyrights() // assets/licenses/fonts-OFL.txt 含 "The Orbitron Project Authors"、"The Chakra Petch Project Authors"、"SIL Open Font License, Version 1.1"
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class FogComponentsTest {   // createComposeRule
    @Test fun panelShowsTitleEnglishAndMeta()
    @Test fun glassBlursOnlyWhenEffectsAllow()   // FULL → Blur=true；BALANCED → false
    @Test fun backgroundAnimatesOnlyOnTheActivePageWithAmbient() // pageActive=false 或 SAVER 或 reducedMotion → Animating=false
    @Test fun segmentedSelectsAndReports()        // 點 tagPrefix+"2" → onSelect(2)，selected 語意正確
    @Test fun countUpShowsTheFinalValueWithoutCountUp() // countUp=false 時第一個 frame 就是最終字串
}
class UiPrefsTest { @Test fun effectLevelDefaultsToFullAndPersists() }   // InMemorySharedPreferences
```

- [ ] **Step 2: 跑測試確認失敗** `--tests 'app.dshmobile.ui.fog.*'` → FAIL
- [ ] **Step 3: 下載字體**：從 google/fonts commit `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc` 取三個 TTF 與兩份 `OFL.txt`，核對 sha256 後放進 `res/font/`（檔名小寫底線），`fonts-OFL.txt` = 兩份 OFL.txt 依序串接，前面一行寫來源 URL 與 commit。
- [ ] **Step 4: 實作 `ui/fog/*`**（數值全照 Global Constraints；網格用 Canvas 畫線＋`graphicsLayer { rotationX = 68f; cameraDistance = 340.dp 換算 }`；光暈用 radial gradient 畫，不用 `Modifier.blur`）
- [ ] **Step 5: 跑測試確認通過**（PASS）
- [ ] **Step 6: Commit** `feat: FOGCITY design system for Compose (fonts, glass, background, effect levels)`

---

### Task 4: 原生殼：五分頁、懸浮工具列，主流程移除 WebView

**Files:**
- Create: `ui/shell/ShellTab.kt`、`Tags.kt`、`ShellSession.kt`、`ShellNav.kt`、`FogShell.kt`、`Dock.kt`、`StatusPanel.kt`、`SettingsEnvFactory.kt`
- Modify: `ui/MainActivity.kt`（改寫）、`ui/MainScreen.kt`（只保留 `enum class MainUiState`）、`ui/SettingsScreen.kt`（`onClose` 可為 null、`contentPadding`、嵌入時透明背景）、`DshMobileApp.kt`、`AndroidManifest.xml`、`res/values/strings.xml`、`app/build.gradle.kts`＋`libs.versions.toml`（androidTest 的 Compose test、espresso）
- Delete: `ui/DshWebViewClient.kt`、`ui/DshChromeClient.kt`、`ui/DownloadHandler.kt`、`ui/SettingsActivity.kt` 與 `DshWebViewClientTest`、`DownloadHandlerTest`、`SettingsActivityTest`、`MainScreenTest`、`androidTest/.../ui/MainActivityTest.kt`
- Modify (test): `test/.../ui/MainActivityTest.kt`、`test/.../ui/SettingsScreenTest.kt`；androidTest `E2eSupport.kt`、`E2eTest.kt`
- Create (test): `test/.../ui/shell/FogShellTest.kt`、`ShellSessionHolderTest.kt`

**Interfaces:**
- Consumes: Task 2 `DshRemote`、`MuxState`；Task 3 全部；`MainOps`、`UiHolder`、`LaunchRequest`、`SettingsEnv`、`SettingsScreen`。
- Produces:
  ```kotlin
  enum class ShellTab(val zh: String, val en: String) { OVERVIEW("總覽","OVERVIEW"), CHAT("對話","CHAT"), AGENTS("代理人","AGENTS"), LIBRARY("書架","LIBRARY"), SETTINGS("設定","SETTINGS") }
  object Tags {   // 後續任務在此加常數；e2e 只用這些
      const val TAB = "tab-"            // + ShellTab.name
      const val COMPOSER_INPUT = "composer-input"; const val SEND = "composer-send"; const val STOP = "composer-stop"; const val ATTACH = "composer-attach"
      const val STATUS_PANEL = "status-panel"; const val RETRY = "status-retry"; const val EXPORT_LOG = "status-export-log"
      const val RECONNECTING = "reconnecting"; const val LIBRARY_PLACEHOLDER = "library-placeholder"; const val SHELL_BACKGROUND = "shell-background"
  }
  class ShellSession(val remote: DshRemote, val scope: CoroutineScope) { fun close() }   // 之後任務加欄位
  class ShellSessionHolder(private val create: (RunningDsh) -> ShellSession) {
      val current: StateFlow<ShellSession?>
      fun attach(running: RunningDsh): ShellSession   // 同一個 launch.url 回傳現有的；不同則先 close 舊的
      fun detach()
  }
  class ShellNav { var tab: ShellTab; var openSessionId: String?; var listOpen: Boolean
      fun select(tab: ShellTab); fun openSession(id: String); fun back(): Boolean }   // 預設 tab = OVERVIEW（照樣品第 0 頁）
  data class ComposerUi(val text: String, val onText: (String) -> Unit, val canSend: Boolean, val running: Boolean,
      val onSend: () -> Unit, val onStop: () -> Unit, val onAttach: () -> Unit, val chips: List<AttachmentChip> = emptyList(),
      val onRemoveChip: (String) -> Unit = {}, val error: String? = null)
  data class AttachmentChip(val id: String, val name: String, val isImage: Boolean)
  @Composable fun FogShell(status: MainUiState, connection: MuxState?, nav: ShellNav, composer: ComposerUi,
                           onRetry: () -> Unit, onExportErrorLog: () -> Unit, overlay: @Composable () -> Unit = {},
                           page: @Composable (ShellTab) -> Unit)
  @Composable fun StatusPanel(state: MainUiState, onRetry: () -> Unit, onExportErrorLog: () -> Unit)
  fun buildSettingsEnv(activity: ComponentActivity, app: DshMobileApp): SettingsEnv   // 原 SettingsActivity.buildEnv 搬來
  // DshMobileApp
  val shellSessions: ShellSessionHolder           // create = { r -> ShellSession(DshRemote(DshClient(loopback, r.launch), applicationScope), applicationScope) }
  val uiPrefs: UiPrefs
  // MainActivity（測試接縫）
  internal val navForTest: ShellNav; internal val uiStateForTest: StateFlow<MainUiState>
  ```
- 行為（MainActivity）：生命週期、`UiHolder` 持有、give-up、Retry、退版、App 更新、匯出、意圖處理**全部照舊**（Ruling：GIVE_UP 後只有使用者明確動作才重啟）。`startDsh()` 成功後 `app.shellSessions.attach(running)`；`Ready` ＝ attach 後 `remote.mux.state` 第一次到 `OPEN`；ensureRunning 回來 60 秒內沒到 `OPEN` → `Error`，`ErrorLog` 記 `remote: no connection (<例外類別>)`。`controller.isRunning` 變 false → `detach()`、狀態回 Loading。時間記錄 `perf: main screen ready …` 沿用。返回鍵先交給 `ShellNav.back()`。
- 狀態的位置（planner's call：設定必須永遠可用，dsh 起不來時才修得了）：Loading/Error 不再蓋滿全螢幕，而是在需要 dsh 的分頁（對話、代理人）內容區顯示 `StatusPanel`（文字沿用 `main_loading`、`main_error_title/body`、重試、匯出錯誤記錄）；總覽、設定照常；工具列永遠在。連線 `RECONNECTING` 超過 2 秒在工具列上方顯示小晶片「重新連線中…」。
- 書架分頁在第一階段只有說明（無法避免）：「書架會在下一版推出：Claude 寫的小說會收在這裡。」本任務中總覽、對話、代理人暫時顯示空白霧城面板，由後續任務填入；設定分頁直接放現有 `SettingsScreen(env, onClose = null, contentPadding = 頁面內距)`。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class FogShellTest {
    @Test fun fiveTabsInOrderWithChineseAndEnglishLabels()   // TAB+name 依序 OVERVIEW..SETTINGS，各含 zh 與 en 文字
    @Test fun selectingATabUpdatesNavAndSelectedState()
    @Test fun theDockComposerIsOnEveryTab()                   // 每個分頁都找得到 COMPOSER_INPUT、SEND、ATTACH
    @Test fun libraryShowsOnlyThePlaceholder()
    @Test fun loadingAndErrorShowInChatAndAgentsButSettingsStillWorks() {
        // status=Error：CHAT 分頁有 STATUS_PANEL、RETRY、EXPORT_LOG；切到 SETTINGS 看不到 STATUS_PANEL，看得到設定內容
    }
    @Test fun reconnectingChipShowsOnlyWhileReconnecting()
}
class ShellSessionHolderTest {
    @Test fun sameLaunchUrlReturnsTheSameSession()
    @Test fun aNewLaunchUrlClosesTheOldSession()
    @Test fun detachClosesAndClears()
}
// test/.../ui/MainActivityTest.kt（Robolectric）：completeSetup 的假 dsh 改成印出 FakeDshServer(mux = FakeMux()) 的 launch URL（每次啟動一個新的 server）
// 保留並改寫斷言：startRetainsUiStartsTheServiceAndAttachesTheShell（shellSessions.current 的 launch == 假 dsh 的 URL）、
//   stopReleasesUiAndSchedulesTheGraceCheck、所有 give-up／retry／rollback／update／export／intent 測試（WebView 斷言改為 uiState 與 shellSessions 斷言）
// 刪除：所有 WebView／file chooser／JS dialog／renderer priority／overlay shrink／settingsOpensTheSettingsActivityByName 測試
// 新增：
    @Test fun readyOnlyAfterTheRemoteIsOpen()                  // FakeMux 延遲接受升級 → Loading；接受後 → Ready
    @Test fun aNewDshInstanceReattachesTheShellToItsLaunchUrl() // 取代 whenDshComesBackElsewhere…
    @Test fun noWebViewAnywhereInTheContentTree()               // 走訪 window.decorView，沒有 android.webkit.WebView
    @Test fun theErrorLogNeverHoldsTheLaunchUrl()               // 整個啟動後 errorLog 不含 "token="
    @Test fun backFirstClosesShellNavigation()                  // nav.openSessionId 設值 → back → 清掉、activity 未 finish
// SettingsScreenTest：closeButton 測試改為 `withoutOnCloseThereIsNoCloseButton` 與 `withOnCloseTheCloseButtonCloses`
```

- [ ] **Step 2: 跑測試確認失敗** `--tests 'app.dshmobile.ui.*'` → FAIL
- [ ] **Step 3: 實作** 上述檔案；manifest 刪 `SettingsActivity`，`MainActivity` 的註解改為「configChanges：轉向、深色模式不重建殼」；`strings.xml` 刪只給齒輪用的 `main_settings_*`、`main_download_*`，新增分頁、工具列、狀態文字（見上）。`setContent { FogTheme(effects) { … } }`，`effects = effectsFor(app.uiPrefs.effectLevel, systemReducedMotion())`（Task 14 再接降級）。
- [ ] **Step 4: 讓 androidTest 跟著編得過**：`E2eSupport` 刪 WebView 輔助（`webView`、`eval`、`onDsh`、`awaitDshInFogcity`、`BG`、`FOGCITY`），新增 `fun awaitShellReady(scenario)`（等 `uiStateForTest == Ready`，300 s）；`E2eTest.t1` 改為 `t1_theNativeShellIsReadyWithoutAWebView`（Ready、view 樹無 WebView）；刪 `t7`；`t8` 改名 `t7_dshsFolderPickerHostListsTheWorkspace` 只留 RPC 斷言；`t5` 結尾改 `awaitShellReady`。androidTest 依賴加 Compose BOM、`ui-test-junit4`、`espresso-core`。
- [ ] **Step 5: 跑測試確認通過**：`./gradlew :app:testDebugUnitTest` 全綠，`./gradlew :app:assembleDebugAndroidTest` 編得過
- [ ] **Step 6: Commit** `feat: native FOGCITY shell with five tabs; WebView leaves the main flow`

---

### Task 5: 對話狀態：`session/follow` 的 reducer 與 store

**Files:**
- Create: `chat/ConversationState.kt`、`chat/ConversationReducer.kt`、`chat/ConversationStore.kt`
- Modify: `remote/DshRemote.kt`（實作 `ChatRemote`）
- Test: `test/.../chat/ConversationReducerTest.kt`、`ConversationStoreTest.kt`

**Interfaces:**
- Consumes: Task 1/2 `StreamSignal`、`PromptPart`、`PromptMode`、`DshRemote`。
- Produces:
  ```kotlin
  interface ChatRemote {
      fun follow(sessionId: String): Flow<StreamSignal>
      suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>, mode: PromptMode)
      suspend fun cancel(sessionId: String)
      suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement
      suspend fun selectModel(sessionId: String, provider: String, model: String)
      suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String
  }
  data class ConversationState(val sessionId: String, val items: List<ChatItem> = emptyList(), val live: LiveReply? = null,
      val running: Boolean = false, val title: String? = null, val hasMore: Boolean = false, val firstSeq: Long? = null,
      val acceptedRequestIds: Set<String> = emptySet())
  sealed interface ChatItem { val key: String
      data class User(override val key: String, val text: String, val attachments: List<String>) : ChatItem
      data class Assistant(override val key: String, val seq: Long, val reasoning: String?, val text: String) : ChatItem
      data class Tool(override val key: String, val name: String, val summary: String, val status: ToolStatus,
                      val sources: List<WebSource> = emptyList(), val sharePath: String? = null, val error: String? = null) : ChatItem
      data class Notice(override val key: String, val kind: NoticeKind, val text: String) : ChatItem }
  enum class ToolStatus { RUNNING, WAITING_APPROVAL, DONE, FAILED }
  enum class NoticeKind { STOPPED, ERROR }
  data class WebSource(val url: String, val title: String)
  data class LiveReply(val attemptId: String, val reasoning: String, val text: String, val toolNames: List<String>)
  object ConversationReducer {
      fun reduce(state: ConversationState, frame: JsonElement?): ConversationState   // 永不丟例外
      fun prepend(state: ConversationState, page: JsonElement): ConversationState
  }
  data class PendingSend(val requestId: String, val text: String, val attachments: List<String>, val queued: Boolean)
  class ConversationStore(private val remote: ChatRemote, val sessionId: String, scope: CoroutineScope,
                          newRequestId: () -> String = { "dshm-" + UUID.randomUUID() }) {
      val state: StateFlow<ConversationState>
      val pending: StateFlow<List<PendingSend>>
      val sendError: StateFlow<String?>
      suspend fun send(parts: List<PromptPart>, displayText: String, attachmentNames: List<String>): Boolean // 一律 PromptMode.QUEUE
      suspend fun stop()
      suspend fun loadOlder()                      // page(beforeSeq = firstSeq, maxMessages = 200)
      suspend fun selectModel(model: AgentModels.Model)
      fun close()
  }
  ```
  Reducer 規則（框的形狀見 `docs/dsh-ui-protocol.md` §2.2–2.5）：
  - `snapshot` → 以 `records[].event` 重建 `items`（**取代**，不是附加），`hasMore`、`firstSeq` 取自快照；`assistantStream.activeAttempt` 不重建 live（等下一個 start）。
  - 顯示：`user/message` 只在 `data.source.kind == "user"`（文字取 `data.message.content` 的 text 部分，也容忍 `data.content`；`image`/`file` 部分只記名稱或「圖片」），並把 `data.source.rpcId` 加進 `acceptedRequestIds`；`assistant/message` → `Assistant`（reasoning 與 text 各自串接）＋每個 `tool-call` 區塊一張 `Tool(RUNNING)`（`arguments` 可能是字串或物件）；`tool/call` 若還沒有同 callId 的卡就新增；`tool/result` → `DONE`／`isError` 時 `FAILED` 並帶錯誤文字第一行（最多 200 字）；`approval/asked` → `WAITING_APPROVAL`；`approval/decided` `rejected`/`cancelled`/`unavailable` → `FAILED` 錯誤「已拒絕」，`allowed-once` → `RUNNING`；`turn/start` → running true；`turn/end` → false，`reason.kind == "aborted"` 加 `Notice(STOPPED,"已停止")`；`session/title` → title。其餘（`system/message`、`request/*`、`step/*`、`agent/inbox/*`、`job/*`、`session/title-llm-request`…）與不認得的類型一律忽略。
  - live：`assistant-stream` 的 `start` 建 `LiveReply`；`chunk` 依 index 累加 `text-delta`、`reasoning-delta`，`tool-call-delta` 有 name 時記下；`end`（`committed` 或 `abandoned`）→ live = null。
  - 工具摘要：`web_search` → `queries` 以「, 」串接；其他依序取 `description`、`command`、`file_path`、`path`、`url`、`query` 第一個有值的，否則參數 JSON；都截到 80 字。
  - 搜尋來源（planner's call：結果 meta 的位置沒實測過）：先找 `tool/result` 的 `data.meta.sources[]{url,title}`，沒有就從結果文字抓 Markdown 連結 `[標題](http…)`；只收 http/https，依 url 去重。
  - 分享路徑（planner's call，取代 WebView 下載）：工具名 `write` 或 `edit`、`file_path` 正規化後在 `/root/workspace/` 底下 → `sharePath` = 相對路徑；含 `..` 跳出、其他路徑 → null。
  - key：使用者／助理用 `"e" + seq`，工具用 callId，notice 用 `"n" + seq`。

- [ ] **Step 1: 寫失敗的測試**（fixture 用小工具 `ev(type, seq, data)`、`snapshot(vararg events)`、`chunk(...)`，照協定文件的形狀手寫）

```kotlin
class ConversationReducerTest {
    @Test fun snapshotShowsOnlyRealPromptsAndReplies()      // user(kind user "hi")、user(runtime-context)、system/message、request/header、assistant(reasoning "think", text "Hello") → [User("hi"), Assistant(reasoning="think", text="Hello")]
    @Test fun aSecondSnapshotReplacesItemsWithoutDuplicates()
    @Test fun liveTextAccumulatesByIndexAndCommitClearsIt()   // start、block-start text、"Hel"、"lo" → live.text "Hello"；end committed → live null
    @Test fun reasoningDeltasGoToLiveReasoning()
    @Test fun abandonedAttemptClearsLive()
    @Test fun aToolCallBecomesARunningCardThenDone()          // bash {"description":"Print a greeting","command":"echo hi"} → summary "Print a greeting"；tool/result → DONE
    @Test fun aFailedToolKeepsTheFirstErrorLine()
    @Test fun webSearchCardsCarryMetaSources()                // meta.sources [{url:"https://example.com/w",title:"W"}]
    @Test fun webSearchFallsBackToMarkdownLinksInTheResult()  // "- [E2E Weather](https://example.com/e2e-weather)\n- [x](javascript:alert(1))" → 只有 E2E Weather
    @Test fun approvalAskedAndDecidedMoveTheToolStatus()      // asked → WAITING_APPROVAL；rejected → FAILED("已拒絕")；allowed-once → RUNNING
    @Test fun turnEndAbortedAddsAStoppedNotice()              // running false、最後一項 Notice(STOPPED,"已停止")
    @Test fun titleEventsSetTheTitle()
    @Test fun acceptedRequestIdsComeFromUserMessages()        // source.rpcId "q1" → acceptedRequestIds 含 "q1"
    @Test fun writeUnderTheWorkspaceGetsASharePath()          // "/root/workspace/notes/a.md" → "notes/a.md"；"/tmp/x"、"/root/workspace/../etc/passwd" → null
    @Test fun prependAddsOlderItemsFirst()
    @Test fun unknownAndMalformedFramesNeverThrow()           // null、{}、{"type":"event"}、{"type":"event","event":{"type":"tool/result","data":5}}、{"type":"weird"} → state 不變
}
class ConversationStoreTest {   // FakeChatRemote（記錄呼叫、可控 follow 的 Channel），runTest
    @Test fun sendUsesQueueModeAndShowsAPendingBubble()
    @Test fun thePendingBubbleLeavesWhenDshRecordsTheMessage() // follow 送出 rpcId == requestId 的 user/message → pending 空
    @Test fun aPromptSentWhileRunningIsMarkedQueued()          // turn/start 後 send → PendingSend.queued == true
    @Test fun aRefusedPromptRemovesThePendingBubbleAndReportsTheCode() // prompt 丟 DshRpcException("session/model-unavailable") → send 回 false、sendError 含該碼
    @Test fun stopCallsCancel()
    @Test fun reopenedKeepsPendingBubblesAndTakesTheNewSnapshot()
    @Test fun loadOlderPrependsAPage()
    @Test fun selectModelSendsProviderAndModel()               // claude-sonnet-5-5 → ("anthropic","claude-sonnet-5-5")
}
```

- [ ] **Step 2: 跑測試確認失敗** `--tests 'app.dshmobile.chat.*'` → FAIL
- [ ] **Step 3: 實作**（store 在 `Dispatchers.Default` reduce，`StateFlow` 自然合併；`DshRemote : ChatRemote`）
- [ ] **Step 4: 跑測試確認通過**（PASS）
- [ ] **Step 5: Commit** `feat: conversation reducer and store over session/follow`

---

### Task 6: 對話清單（分組、搜尋、新對話、改名、刪除）

**Files:**
- Create: `chat/SessionIndex.kt`、`ui/chat/ConversationListScreen.kt`
- Modify: `ui/shell/ShellSession.kt`（`val index`）、`ui/shell/Tags.kt`、`config/AgentSettings.kt`（`AgentModels.displayName`）、`strings.xml`、`remote/DshRemote.kt`（實作 `IndexRemote`）
- Test: `test/.../chat/SessionIndexTest.kt`、`test/.../ui/chat/ConversationListScreenTest.kt`、`test/.../config/AgentSettingsTest.kt`（加一個測試）

**Interfaces:**
- Consumes: Task 1/2（`listSessions`、`searchSessions`、`createSession`、`rename`、`archive`、`control()`、`workspaces()`、`events.hostEvents`）。
- Produces:
  ```kotlin
  interface IndexRemote { suspend fun listSessions(): List<SessionSummary>; suspend fun searchSessions(query: String): List<SearchHit>
      suspend fun createSession(workspaceId: String?): String; suspend fun rename(sessionId: String, title: String): String
      suspend fun archive(sessionId: String); fun control(): Flow<StreamSignal>; fun workspaces(): Flow<StreamSignal>; val hostEvents: Flow<HostEvent> }
  data class SessionRow(val id: String, val title: String, val lastLine: String?, val model: String?, val updatedAt: Long, val running: Boolean)
  enum class DayGroup(val zh: String) { TODAY("今天"), YESTERDAY("昨天"), EARLIER("更早") }
  data class SessionIndexState(val summaries: Map<String, SessionSummary> = emptyMap(), val archived: Set<String> = emptySet(),
      val workspaceId: String? = null, val projections: Map<String, Map<String, JsonElement>> = emptyMap())
  object SessionIndexReducer {
      fun applyList(s: SessionIndexState, list: List<SessionSummary>): SessionIndexState
      fun applyWorkspace(s: SessionIndexState, frame: JsonElement?): SessionIndexState   // baseline/replace/archived；workspaceId = path == "/root/workspace" 的那個
      fun applyControl(s: SessionIndexState, frame: JsonElement?): SessionIndexState     // 只留 title、turnOutline、modelSelection
      fun applyEvent(s: SessionIndexState, e: HostEvent): SessionIndexState
      fun rows(s: SessionIndexState): List<SessionRow>    // 只有頂層（parentId == null 且 origin != "subagent"）、去掉 archived，updatedAt 新到舊
  }
  fun groupByDay(rows: List<SessionRow>, today: LocalDate, zone: ZoneId): List<Pair<DayGroup, List<SessionRow>>>  // 空組不出現
  class SessionIndex(remote: IndexRemote, scope: CoroutineScope) {
      val rows: StateFlow<List<SessionRow>>
      fun refresh(); suspend fun search(query: String): List<SessionRow>   // 依 SearchHit 順序；lastLine 換成 snippet
      suspend fun create(): String; suspend fun rename(id: String, title: String); suspend fun delete(id: String) }
  fun AgentModels.displayName(id: String?): String?   // deepseek-flash→"DeepSeek Flash"、deepseek-v4-pro→"DeepSeek Pro"、claude-sonnet-5-5→"Claude Sonnet"、claude-opus-5-5→"Claude Opus"、claude-haiku-5-5→"Claude Haiku"，其他原樣
  @Composable fun ConversationListScreen(groups: List<Pair<DayGroup, List<SessionRow>>>, query: String, onQuery: (String) -> Unit,
      onOpen: (String) -> Unit, onNew: () -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onClose: () -> Unit)
  // Tags 新增：SESSION_ROW = "session-"（+id）、NEW_SESSION = "new-session"、SESSION_SEARCH = "session-search"、SESSION_MENU = "session-menu-"（+id）、
  //   RENAME = "rename"、RENAME_FIELD = "rename-field"、RENAME_SAVE = "rename-save"、DELETE = "delete"、DELETE_CONFIRM = "delete-confirm"、SESSION_LIST = "session-list"
  ```
  刷新時機：連上（每次 `Reopened` 之後）、頂層的 `SessionAdded`／`SessionRemoved`、create/rename/delete 之後。刪除＝`archive`（planner's call：dsh 0.2.1-rc.5 沒有刪除對話的 RPC；封存＋`stopActivity:true` 從清單消失並停掉進行中的工作，資料仍在工作區），確認框文字「刪除這個對話？」／「對話會從清單移除，正在進行的工作也會停止。」／「刪除」。沒標題顯示「新對話」。清單畫面標頭「所有對話」＋「＋ 新對話」，搜尋框提示「搜尋對話」，空清單「還沒有對話」；每列：標題、`lastLine · 模型顯示名`、在跑時一個水藍發光點。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class SessionIndexTest {
    @Test fun onlyTopLevelUnarchivedSessionsNewestFirst()
    @Test fun controlProjectionsUpdateTitleLastLineAndModel()     // {type:"projection",sessionId,key:"title",value:"新標題"} 等
    @Test fun workspaceArchivedFrameHidesSessions()              // {type:"archived",archivedSessionIds:["s2"]}
    @Test fun workspaceIdIsTheAppWorkspace()                      // baseline items 兩個，path "/root/workspace" 的 id 被選中
    @Test fun statusEventsToggleRunning()
    @Test fun groupingUsesTheLocalDay()                          // zone Asia/Taipei，today 2026-11-03：23:59 前一天 → 昨天；11-01 → 更早
    @Test fun createUsesTheWorkspaceIdOrFallsBackToCwd()
    @Test fun deleteArchivesAndRefreshes()
    @Test fun searchKeepsDshOrderAndShowsSnippets()
    @Test fun brokenFramesAreIgnored()
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class ConversationListScreenTest {
    @Test fun groupsShowInOrderWithRows()
    @Test fun tappingARowOpensIt()
    @Test fun renameFromTheRowMenu()                 // SESSION_MENU+id → RENAME → 輸入 → RENAME_SAVE → onRename(id,"新名字")；空白名字不可存
    @Test fun deleteAsksFirst()                      // DELETE → 確認框 → DELETE_CONFIRM → onDelete(id)；取消則無呼叫
    @Test fun emptyListSaysSo()
}
// AgentSettingsTest 加：displayNamesForEveryKnownModel
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**；`ShellSession` 加 `val index = SessionIndex(remote, scope)`
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: conversation list with day groups, search, rename and delete`

---

### Task 7: 原生 Markdown

**Files:**
- Create: `ui/markdown/Markdown.kt`、`assets/licenses/commonmark-BSD-2.txt`
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`（commonmark 三個模組 0.32.0）
- Test: `test/.../ui/markdown/MarkdownParserTest.kt`、`MarkdownViewTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface MdBlock {
      data class Paragraph(val text: AnnotatedString) : MdBlock
      data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
      data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
      data class Code(val language: String?, val code: String) : MdBlock
      data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
      data class Quote(val blocks: List<MdBlock>) : MdBlock
      data object Rule : MdBlock }
  object MarkdownParser {
      const val MAX_CHARS = 200_000
      fun parse(text: String): List<MdBlock>    // 超過 MAX_CHARS 只解析前段，最後加一段「（內容過長，只顯示前 20 萬字）」
  }
  @Composable fun rememberMarkdown(text: String, streaming: Boolean): List<MdBlock>   // Dispatchers.Default 解析；streaming 時 100 ms 去抖
  @Composable fun MarkdownView(blocks: List<MdBlock>, onLink: (String) -> Unit, modifier: Modifier = Modifier)
  const val LINK_TAG = "url"
  ```
  選擇（planner's call）：commonmark-java 0.32.0＋gfm-tables＋gfm-strikethrough，自己轉成 Compose——BSD-2、純 Java、完全離線、Maven Central 可解析；現成的 Compose Markdown 函式庫（mikepenz multiplatform-markdown-renderer 0.46.0）依賴 kotlin-stdlib 2.4.21，AGP 9.5 內建的 Kotlin 2.2.10 讀不了。行內：粗體、斜體、刪除線、行內碼（Chakra Petch 不適合程式碼，用 `FontFamily.Monospace`，底 白α.6、圓角 6）、連結（`LinkAnnotation.Clickable(tag=url)` 交給 `onLink`，只放行 http/https）。HTML 區塊與行內 HTML 當純文字顯示；Markdown 圖片不載入，顯示成「〔圖片〕alt」的連結文字（planner's call：離線、隱私）。表格與程式碼區塊各自橫向捲動；表格儲存格最寬 240dp。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class MarkdownParserTest {
    @Test fun boldItalicCodeAndLinksBecomeSpans()     // "**粗** *斜* `c` [a](https://x.y)" → 一個 Paragraph，含 Bold/Italic/Monospace span 與 LINK_TAG "https://x.y"
    @Test fun listsKeepOrderAndStart()                // "3. a\n4. b" → ListBlock(ordered,start=3,2 items)
    @Test fun gfmTablesParse()                        // "| a | b |\n|---|---|\n| 1 | 2 |" → Table(header [a,b], rows [[1,2]])
    @Test fun fencedCodeKeepsLanguageAndText()
    @Test fun anUnclosedFenceWhileStreamingIsStillACodeBlock()
    @Test fun rawHtmlIsShownAsText()                  // "<script>x</script>" → 文字含 "<script>"
    @Test fun imagesAreNotLoadedButShownAsLinks()     // "![貓](https://x/cat.png)" → 文字 "〔圖片〕貓"，LINK_TAG https://x/cat.png
    @Test fun nonHttpLinksAreNotClickable()           // [x](javascript:alert(1))、[y](file:///etc) → 無 LINK_TAG
    @Test fun hugeInputIsCappedWithANotice()          // 300_000 字 → 解析 < 2 s，最後一個 Paragraph 是截斷提示
    @Test fun aTwentyColumnTableParses()
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class MarkdownViewTest {
    @Test fun rendersTableCellsAndCode()
    @Test fun tappingALinkCallsOnLink()
    @Test fun wideTablesScrollHorizontally()          // 表格容器有 horizontalScroll 語意（ScrollBy action）
}
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 加依賴與授權檔，實作**（授權檔：commonmark-java 的 LICENSE.txt 全文，版權行 "Copyright (c) 2015, Atlassian Pty Ltd" 依其原檔）
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: native Markdown rendering with commonmark`

---

### Task 8: 對話頁（串流、思考、工具卡、搜尋來源、停止、排隊、模型切換）

**Files:**
- Create: `ui/chat/ChatScreen.kt`、`ui/chat/MessageViews.kt`、`ui/chat/ExternalLinks.kt`
- Modify: `ui/shell/ShellSession.kt`（`conversation(id)`）、`ui/MainActivity.kt`（對話分頁、工具列送出、深連結）、`ui/MainOps.kt`（刪 `recordDeepLink`）、`ui/shell/Tags.kt`、`strings.xml`
- Test: `test/.../ui/chat/ChatScreenTest.kt`、`test/.../ui/chat/CitationsTest.kt`、`test/.../ui/MainActivityTest.kt`（加測試）、`test/.../ui/MainOpsTest.kt`

**Interfaces:**
- Consumes: Task 5 `ConversationStore`、`ConversationState`、`ChatItem`、`PendingSend`；Task 6 `SessionIndex`、`ConversationListScreen`、`AgentModels.displayName`；Task 7 `MarkdownView`、`rememberMarkdown`；`Exporter.shareWorkspaceFile(rel)`。
- Produces:
  ```kotlin
  data class ModelChoice(val id: String, val label: String, val selected: Boolean)
  data class ChatUiState(val title: String, val items: List<ChatItem>, val live: LiveReply?, val running: Boolean,
      val pending: List<PendingSend>, val models: List<ModelChoice>, val hasMore: Boolean, val sendError: String?)
  @Composable fun ChatScreen(state: ChatUiState, onOpenList: () -> Unit, onPickModel: (String) -> Unit, onLink: (String) -> Unit,
                             onShare: (String) -> Unit, onLoadOlder: () -> Unit, modifier: Modifier = Modifier)
  fun citedSources(text: String, sources: List<WebSource>): List<Int>   // 回答裡出現其 url 的來源序號（從 1 起），依來源順序
  object ExternalLinks { fun open(context: Context, url: String): Boolean }   // 只有 http/https，ACTION_VIEW + CATEGORY_BROWSABLE；找不到瀏覽器回 false
  // ShellSession 新增：fun conversation(id: String): ConversationStore   // 最多快取 3 個，最舊的 close()
  // Tags 新增：CHAT_LIST_BUTTON="chat-list-button"、CHAT_TITLE="chat-title"、MODEL_CHIP="model-chip"、MODEL_OPTION="model-option-"(+id)、
  //   USER_BUBBLE="user-bubble"、ASSISTANT_BUBBLE="assistant-bubble"、LIVE_BUBBLE="live-bubble"、THINKING="thinking"、TOOL_CARD="tool-"(+callId)、
  //   TOOL_STATUS="tool-status-"(+callId)、SEARCH_CARD="search-card"、SOURCE_CHIP="source-chip-"(+序號)、CITES="cites"、QUEUED="queued"、
  //   NOTICE="notice"、LOAD_OLDER="load-older"、SHARE="share-"(+callId)
  ```
  畫面細節：思考過程預設折疊，標「思考過程」，點開顯示；工具卡狀態字「執行中」「等待確認」「完成」「失敗」；`web_search` 的卡改畫成搜尋卡「搜尋網路 · N 個來源」＋來源晶片（標題，沒有就主機名），點晶片 `ExternalLinks.open`；助理回答若引用了前一張搜尋卡的來源，下面加「來源 ①②…」（圓圈數字 ①–⑳）。排隊中的送出顯示淡化的我方氣泡＋「排隊中」。`Notice(STOPPED)` 顯示「已停止」。`hasMore` 時頂端「載入更早的訊息」。LazyColumn 用 `key = item.key`、`contentType`；在底部時自動跟到最新。標頭：「☰ 對話」鈕、標題（單行省略）、右上模型晶片（顯示名大寫）→ 30dp 玻璃選單「這個對話使用的模型」：`AgentModels.available(hasClaudeKey)`（planner's call：用 App 自己的清單，價格只有這些模型有；Claude 只在有金鑰時出現）；目前值＝`modelSelection` 投影的 `next ?: lastUsed`，沒有就 `AgentSettings.leadModel`。
- 工具列：在任何分頁送出都送到目前開著的對話並切到「對話」（planner's call：照樣品）；沒有開著的對話就先 `index.create()`。`running && 輸入框空白` 時送出鈕換成停止鈕（`Tags.STOP`，「停止回覆」）→ `store.stop()`；`running && 有字` → 送出（排隊）。送出失敗把文字放回輸入框並顯示 `sendError`。對話分頁內：`nav.listOpen` 時顯示 `ConversationListScreen`，否則 `ChatScreen`；沒有開著的對話時顯示清單。上次開的對話存 `UiPrefs.lastSessionId`。
- 深連結：`LaunchRequest.OpenSession(id)` → `nav.openSession(id)`＋切到對話分頁（取代只記錄的第一期做法；`MainOps.recordDeepLink` 刪除）。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class ChatScreenTest {
    @Test fun userAndAssistantBubblesRenderMarkdown()
    @Test fun liveTextShowsWhileStreaming()                   // live.text "Hel" → LIVE_BUBBLE 含 "Hel"
    @Test fun thinkingIsCollapsedUntilTapped()
    @Test fun toolCardsShowNameSummaryAndStatus()            // RUNNING→"執行中"、WAITING_APPROVAL→"等待確認"、FAILED→"失敗" 與錯誤字
    @Test fun webSearchShowsTheSourceCardAndChips()          // "搜尋網路 · 2 個來源"、SOURCE_CHIP1 文字 "E2E Weather"、點擊 → onLink("https://example.com/e2e-weather")
    @Test fun answersCiteTheSourcesTheyLinkTo()              // CITES 文字 "來源 ①"
    @Test fun queuedSendsAreMarked()
    @Test fun stoppedNoticeShows()
    @Test fun modelChipListsOnlyAvailableModels()           // 無 Claude 時只有 DeepSeek Flash/Pro；點 MODEL_OPTION+"deepseek-v4-pro" → onPickModel
    @Test fun shareButtonOnlyForWorkspaceFiles()
    @Test fun loadOlderShowsWhenThereIsMore()
}
class CitationsTest { @Test fun citedSourcesAreNumberedInSourceOrder() ; @Test fun noLinksNoCites() }
// MainActivityTest（Robolectric＋FakeMux）新增：
    @Test fun sendingFromAnotherTabSwitchesToChatAndPrompts()    // 在總覽輸入 "hi" 送出 → nav.tab CHAT；FakeDshServer 收到 session/prompt
    @Test fun withoutAnOpenConversationSendingCreatesOne()      // 收到 session/create 再 session/prompt
    @Test fun aDeepLinkOpensThatConversation()                  // EXTRA_SESSION_ID "s1" → nav.openSessionId == "s1"、tab CHAT
    @Test fun stopShowsWhileRunningWithAnEmptyInput()
// MainOpsTest：刪除 recordDeepLink 測試
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: native chat page with streaming, tool cards, search sources, stop, queue and model switch`

---

### Task 9: 附加圖片與檔案

**Files:**
- Create: `chat/Attachments.kt`
- Modify: `ui/MainActivity.kt`（`PickVisualMedia`、`OpenMultipleDocuments`）、`ui/shell/Dock.kt`（縮圖列）、`ui/shell/Tags.kt`、`strings.xml`
- Test: `test/.../chat/AttachmentsTest.kt`、`test/.../ui/shell/DockAttachmentsTest.kt`

**Interfaces:**
- Consumes: Task 5 `ConversationStore.send`、`ChatRemote.upload`；Task 4 `ComposerUi`、`AttachmentChip`。
- Produces:
  ```kotlin
  data class PendingAttachment(val id: String, val name: String, val mime: String, val bytes: ByteArray) { val isImage: Boolean }
  sealed interface LoadResult { data class Ok(val attachment: PendingAttachment) : LoadResult
      data class TooLarge(val name: String, val limitMb: Int) : LoadResult; data class Unreadable(val name: String) : LoadResult }
  class AttachmentLoader(private val open: (Uri) -> InputStream?, private val describe: (Uri) -> Pair<String?, Long?> /* 名稱、大小 */,
                         private val mimeOf: (Uri) -> String?) {
      fun load(uri: Uri): LoadResult          // 讀到上限＋1 bytes 就停，不信任 describe 的大小
      companion object { const val IMAGE_LIMIT = 5 * 1024 * 1024; const val FILE_LIMIT = 20 * 1024 * 1024
          val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp") }
  }
  class AttachmentSender(private val remote: ChatRemote) {
      suspend fun toParts(sessionId: String, text: String, attachments: List<PendingAttachment>): List<PromptPart> // 先全部上傳，任何一個失敗就丟例外、什麼都不送
  }
  // MainActivity 測試接縫：internal fun attachForTest(uris: List<Uri>)
  // Tags 新增：ATTACH_IMAGE="attach-image"、ATTACH_FILE="attach-file"、ATTACHMENT_CHIP="attachment-"(+id)、ATTACHMENT_REMOVE="attachment-remove-"(+id)、COMPOSER_ERROR="composer-error"
  ```
  規則（planner's call）：`IMAGE_TYPES` 且 ≤ 5 MB → `PromptPart.Image`（base64）；其他類型或圖片 > 5 MB → 走 `fileUploads/upload` 成 `PromptPart.File`；檔案上限 20 MB。附件鈕打開小選單「圖片」「檔案」。錯誤文字：超過上限「%1$s 超過 %2$d MB，沒有附上」、讀不到「讀不到 %s」、上傳失敗「附件上傳失敗，訊息沒有送出（%s）」、dsh 拒絕「dsh 不接受這個附件：%s」（錯誤碼）；失敗時附件與文字都留在工具列。選擇器取消＝什麼都不變。縮圖 64dp 圓角 16，右上 24dp 深色圓形 ✕。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class AttachmentsTest {
    @Test fun aSmallPngBecomesAnImagePart()
    @Test fun aPdfIsUploadedAndBecomesAFilePart()          // upload 被叫一次，part = File(receiptId,name)
    @Test fun aSevenMegabyteJpegIsSentAsAFile()
    @Test fun overTwentyMegabytesIsTooLargeEvenIfTheProviderLies() // describe 說 1 KB，實際 21 MB → TooLarge
    @Test fun anUnreadableUriIsReportedNotThrown()         // open 丟 SecurityException 或回 null → Unreadable
    @Test fun oneFailedUploadSendsNothing()                // 第二個 upload 丟 IOException → toParts 丟例外，prompt 從未被呼叫
    @Test fun textComesFirstThenAttachmentsInPickOrder()
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class DockAttachmentsTest {
    @Test fun chipsShowAndCanBeRemoved()
    @Test fun anErrorStaysVisibleAndTheTextIsKept()
    @Test fun sendIsEnabledWithOnlyAnAttachment()
}
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**（`ContentResolver.query(OpenableColumns)` 給名稱與大小；`ContentResolver.getType` 給 mime；讀檔在 `Dispatchers.IO`）
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: image and file attachments`

---

### Task 10: 權限詢問與 ask_user_question 玻璃確認卡

**Files:**
- Create: `ui/shell/PromptSheet.kt`
- Modify: `ui/MainActivity.kt`（把 `remote.events.prompts` 接到 `FogShell.overlay`）、`ui/shell/Tags.kt`、`strings.xml`
- Test: `test/.../ui/shell/PromptSheetTest.kt`、`test/.../ui/MainActivityTest.kt`（加測試）

**Interfaces:**
- Consumes: Task 2 `PendingPrompt`、`AskQuestion`、`DshEvents.allowOnce/reject/answer/decline`；Task 6 `SessionIndex.rows`（取對話標題）。
- Produces:
  ```kotlin
  @Composable fun PromptSheet(prompt: PendingPrompt, conversationTitle: String?, waitingCount: Int, busy: Boolean, error: String?,
      onAllowOnce: () -> Unit, onReject: () -> Unit, onAnswer: (Map<String, List<String>>) -> Unit, onDecline: () -> Unit)
  // Tags 新增：PROMPT_SHEET="prompt-sheet"、APPROVE_ONCE="approve-once"、REJECT="reject"、OPTION="option-"(+questionId+"-"+index)、
  //   QUESTION_SUBMIT="question-submit"、QUESTION_DECLINE="question-decline"、PROMPT_ERROR="prompt-error"
  ```
  行為（planner's call：dsh 在等就卡住，所以卡片是全域、強制的）：不論在哪個分頁都蓋在工具列上（遮罩＋30dp 確認卡，動態見 Global Constraints），返回鍵與點遮罩都關不掉；一次一張（`prompts.first()`），「還有 %d 個等你回覆」。權限卡：英文副標「APPROVAL」、標題「dsh 想要執行 %s」、內文＝reason、「來自：%s」對話標題、按鈕「允許這一次」（PINK）「拒絕」（GHOST）。提問卡：副標「QUESTION」、每題 header 晶片＋問題＋選項（單選；`multiSelect` 時可複選），「送出」（每題至少選一個才可按）與「不回答」。送出中 `busy` 停用按鈕；失敗顯示「沒有送出，請再試一次」並保留卡片。絕不自動回答。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class PromptSheetTest {
    @Test fun approvalShowsToolReasonAndConversation()
    @Test fun allowOnceAndRejectCallBack()
    @Test fun questionNeedsAnAnswerBeforeSubmit()      // 未選 → QUESTION_SUBMIT disabled；選 OPTION q1-0 → onAnswer({"q1":["Pink"]})
    @Test fun multiSelectCollectsSeveralLabels()
    @Test fun declineIsAlwaysAvailable()
    @Test fun anErrorKeepsTheSheetAndSaysSo()
    @Test fun moreWaitingIsCounted()
}
// MainActivityTest（FakeMux）：
    @Test fun anApprovalShowsOverAnyTabAndIsAnsweredWithTheClientId() // 在設定分頁時送 waterfall → PROMPT_SHEET 出現；APPROVE_ONCE → $events/result 帶 ready 的 clientId、value "allowed-once"
    @Test fun backDoesNotDismissAPendingPrompt()
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: glass approval and question cards that always reach the user`

---

### Task 11: 代理人分頁（簡易清單＋叫它停）

**Files:**
- Create: `agents/RunningAgents.kt`、`ui/agents/AgentsScreen.kt`
- Modify: `ui/shell/ShellSession.kt`（`val agents`）、`ui/MainActivity.kt`、`ui/shell/Tags.kt`、`strings.xml`、`remote/DshRemote.kt`（實作 `AgentsRemote`）
- Test: `test/.../agents/RunningAgentsTest.kt`、`test/.../ui/agents/AgentsScreenTest.kt`

**Interfaces:**
- Consumes: Task 1 `listSessions`、`interruptSubagent`；Task 2 `hostEvents`、`MuxState`；Task 6 `SessionIndex.rows`（父對話標題）。
- Produces:
  ```kotlin
  interface AgentsRemote { suspend fun listSessions(): List<SessionSummary>; suspend fun interruptSubagent(childSessionId: String, parentSessionId: String)
      val hostEvents: Flow<HostEvent>; val muxState: StateFlow<MuxState> }
  data class AgentRow(val childId: String, val parentId: String, val label: String, val running: Boolean)
  object RunningAgentsReducer { fun applyList(rows: Map<String, AgentRow>, list: List<SessionSummary>): Map<String, AgentRow>
      fun applyEvent(rows: Map<String, AgentRow>, e: HostEvent): Map<String, AgentRow> }
  class RunningAgents(remote: AgentsRemote, scope: CoroutineScope) {
      val rows: StateFlow<List<AgentRow>>     // 在跑的在前，其餘依加入順序；移除事件就拿掉
      val runningCount: StateFlow<Int>
      suspend fun stop(row: AgentRow) }
  @Composable fun AgentsScreen(rows: List<AgentRow>, parentTitle: (String) -> String?, limit: Int, stopping: Set<String>,
                               onStop: (AgentRow) -> Unit, onOpenParent: (String) -> Unit)
  // Tags 新增：AGENT_ROW="agent-"(+childId)、AGENT_STOP="agent-stop-"(+childId)、AGENTS_EMPTY="agents-empty"
  ```
  基準：每次連上（`muxState` 回到 OPEN）用 `listSessions()` 取有 `parentId` 的會話（`$events` 不重送，所以需要清單當基準），之後用 `SessionAdded/Status/Removed` 增量。標籤＝`label ?: "子代理人"`。面板「代理人」「AGENTS」meta「在跑 %1$d / 上限 %2$d」（上限＝`AgentSettingsStore.load(phantom, hasClaude).maxConcurrent`）；每列：水藍發光點（執行中）或奶茶點（已結束）、標籤、「來自：父對話標題」（點了開那個對話）、在跑時「叫它停」（AQUA 按鈕，按下後顯示「停止中…」直到狀態變 false）；空清單「目前沒有在跑的代理人」。此頁到第三階段由監看牆取代。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class RunningAgentsTest {
    @Test fun childrenFromTheListAreTheBaseline()          // 只收 parentId != null
    @Test fun addedStatusAndRemovedEventsUpdateRows()
    @Test fun reconnectRebuildsTheBaseline()               // muxState RECONNECTING→OPEN → 再叫一次 listSessions
    @Test fun stopInterruptsByParentWithTheRightIds()
    @Test fun runningCountCountsOnlyRunning()
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class AgentsScreenTest {
    @Test fun rowsShowLabelParentAndStatus()
    @Test fun stopOnlyForRunningAndShowsStopping()
    @Test fun emptyStateSaysSo()
    @Test fun headerShowsRunningAndLimit()                 // "在跑 1 / 上限 6"
}
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: agents tab lists running subagents and stops them`

---

### Task 12: 總覽基本版

**Files:**
- Create: `overview/OverviewSource.kt`、`overview/ReminderLog.kt`、`ui/overview/OverviewScreen.kt`
- Modify: `gateway/CostMeter.kt`、`heartbeat/HeartbeatScheduler.kt`（`nextRunAt`）、`DshMobileApp.kt`（`reminderLog`；`HeartbeatDeps.notify` 先記錄再通知）、`ui/MainActivity.kt`、`ui/shell/Tags.kt`、`strings.xml`
- Test: `test/.../gateway/CostMeterTest.kt`（加測試）、`test/.../overview/ReminderLogTest.kt`、`OverviewSourceTest.kt`、`test/.../ui/overview/OverviewScreenTest.kt`、`test/.../heartbeat/HeartbeatSchedulerTest.kt`（加測試）

**Interfaces:**
- Consumes: `CostMeter`、`AgentSettingsStore`、`KeyVault`、`PhantomProcessDetector`；Task 6 `SessionIndex.rows`；Task 11 `RunningAgents.runningCount`；Task 3 `HudRing`、`Meter`、`CountUpText`。
- Produces:
  ```kotlin
  // CostMeter 新增（既有 API 與鍵不變；todayUsd() 仍是總和）
  fun todayUsd(p: Provider): Double
  fun monthUsd(): Double                    // Asia/Taipei 的當月
  val revision: StateFlow<Long>             // 每次 record、alertUsd/hardCapUsd 改變 +1
  data class Reminder(val title: String, val body: String, val at: Long)
  class ReminderLog(prefs: SharedPreferences, clock: Clock) { fun add(title: String, body: String); fun recent(limit: Int = 3): List<Reminder>  // 最多存 10 則
      val revision: StateFlow<Long>; companion object { const val PREFS = "dshm_reminders" } }
  fun HeartbeatScheduler.nextRunAt(context: Context): Flow<Long?>   // WorkManager getWorkInfosForUniqueWorkFlow(WORK_NAME) 的 nextScheduleTimeMillis
  data class OverviewState(val deepseekUsd: Double, val claudeUsd: Double, val monthUsd: Double, val alertUsd: Double,
      val agentsRunning: Int?, val agentsLimit: Int, val nextHeartbeatMin: Int?, val reminders: List<Reminder>,
      val recent: List<SessionRow>, val unverifiedDsh: String?)
  class OverviewSource(meter: CostMeter, reminders: ReminderLog, agentsLimit: () -> Int, nextHeartbeatAt: Flow<Long?>,
                       shell: StateFlow<ShellSession?>, dshVersion: () -> String?, clock: Clock) { fun state(): Flow<OverviewState> }
  @Composable fun OverviewScreen(state: OverviewState, onOpenSession: (String) -> Unit, onOpenAgents: () -> Unit)
  // Tags 新增：OVERVIEW_RING="overview-ring"、OVERVIEW_SPLIT="overview-split"、OVERVIEW_AGENTS="overview-agents"、OVERVIEW_HEARTBEAT="overview-heartbeat"、
  //   OVERVIEW_REMINDERS="overview-reminders"、OVERVIEW_RECENT="overview-recent-"(+id)、UNVERIFIED_BANNER="unverified-banner"
  ```
  版面（照樣品第 0 頁）：面板「API 花費」TODAY meta「通知 US$%s」→ `HudRing(progress = min(1, (ds+cl)/alert), 240dp)` 中心 `US$` ＋兩位小數（Chakra Petch 數字）＋「今天花了」「/ %s USD 通知門檻」，圓環 `contentDescription`＝「今日花費 US$%.4f」；下排三格（白α.4 圓角 16）「DeepSeek」「Claude」「這個月」。兩格小面板：「代理人」`%d / %d 在跑`＋格點（點了切代理人分頁；dsh 未連上時「—」）、「心跳」`%d 分鐘後整理記憶`＋水藍進度條（＝已過時間／間隔）。面板「記憶提醒」REMIND「%d 則」（最新 3 則，空時「沒有新的提醒」）、「最近的對話」（最新 3 列，點了開該對話）。`unverifiedDsh` 有值時最上方一行警告「目前的 dsh %s 未經這版 App 驗證，請到設定更新」。`VerifiedDsh` 由 Task 13 建立；本任務先以 `dshVersion = { null }` 接上，Task 13 再接實值。
  記憶提醒只收心跳 dream 的提醒（`HeartbeatDeps.notify` 包一層：`reminderLog.add(title, body)` 後 `notifier.show`）；閘道 `onNotify`（dsh 插件主動通知）不進來。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
// CostMeterTest 加：
    @Test fun perProviderTodayAddsUpToTheTotal()
    @Test fun monthRollsOverAtTaipeiMidnightOnTheFirst()   // 2026-10-31T16:00Z（台北 11/01 00:00）之後 monthUsd 從 0 開始
    @Test fun revisionBumpsOnRecordAndOnSettings()
    @Test fun oldStoresWithoutTheNewKeysStillReportTheTotal() // 只有 day/day_usd 的舊資料 → todayUsd() 不變、各供應商 0
class ReminderLogTest { @Test fun keepsTheNewestTen() ; @Test fun recentIsNewestFirst() }
class OverviewSourceTest {
    @Test fun combinesCostAgentsHeartbeatRemindersAndRecent()
    @Test fun withoutDshAgentsAreUnknownAndRecentIsEmpty()
    @Test fun nextHeartbeatIsWholeMinutesRoundedUp()
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class OverviewScreenTest {
    @Test fun ringDescribesTodaysSpend()                    // contentDescription "今日花費 US$0.0123"
    @Test fun splitShowsDeepseekClaudeAndMonth()
    @Test fun tappingARecentConversationOpensIt()
    @Test fun agentsPanelOpensTheAgentsTab()
    @Test fun unverifiedBannerOnlyWhenSet()
}
// HeartbeatSchedulerTest 加：nextRunAtReportsTheScheduledTime（work-testing 的 TestDriver）
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**（新 SharedPreferences 鍵：`day_usd_deepseek`、`day_usd_anthropic`、`month`、`month_usd`；圓環描繪與數字遞增照 `Effects`）
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: Commit** `feat: overview with spend ring, agents, heartbeat, reminders and recent conversations`

---

### Task 13: 設定搬進新介面（效果強度、寫小說用的模型、只更新到驗證過的 dsh）

**Files:**
- Create: `runtime/VerifiedDsh.kt`
- Modify: `ui/SettingsScreen.kt`（霧城面板樣式、新段落）、`ui/shell/SettingsEnvFactory.kt`、`runtime/DshVersions.kt`（`updateWithAutoRollback` 加 `target` 參數）、`config/AgentSettingsStore.kt`（`novelModel`）、`ui/MainActivity.kt`（`MainOps` 的 previousVersion 過濾、總覽的 `dshVersion`）、`ui/shell/Tags.kt`、`strings.xml`
- Test: `test/.../runtime/VerifiedDshTest.kt`、`test/.../runtime/DshVersionsTest.kt`（加測試）、`test/.../ui/SettingsScreenTest.kt`、`test/.../config/AgentSettingsStoreTest.kt`

**Interfaces:**
- Consumes: Task 3 `UiPrefs`、`EffectLevel`、`Segmented`、`FogPanel`；`SettingsEnv`、`SettingsVersions`、`SettingsJobs`。
- Produces:
  ```kotlin
  object VerifiedDsh { val VERSIONS: List<String> = listOf("0.2.1-rc.5"); fun isVerified(v: String?): Boolean; fun newest(): String }
  // DshVersions
  suspend fun updateWithAutoRollback(runtime: DshRuntime, health: HealthCheck, target: String? = null): UpdateOutcome  // target 有值時不跑 npm view
  // SettingsVersions 新增
  fun verified(): List<String>
  // SettingsEnv 新增
  val uiPrefs: UiPrefs; val onEffectLevel: (EffectLevel) -> Unit
  // AgentSettingsStore 新增
  var novelModel: String     // 預設 "claude-sonnet-5-5"（planner's call：照樣品；沒有 Claude 金鑰時 resolve 成 DeepSeek Flash）；顯示時 AgentModels.resolve(novelModel, hasClaude)
  // Tags 新增：EFFECT="effect-"(+0..2)、NOVEL_MODEL="novel-model-"(+id)、UNVERIFIED_WARNING="unverified-warning"、VERIFIED_NOTE="verified-note"
  ```
  - 驗證清單（planner's call：放 Kotlin 常數，不放 `rootfs/versions.env`——改那個檔會改 rootfs 雜湊，逼所有人重裝 rootfs）。`buildSettingsEnv` 的 `checkUpdate()`：`target = VerifiedDsh.newest()`；`current == target` → `UpToDate`；否則 `dshVersions.updateWithAutoRollback(controller, health, target)`（目前版本未驗證時這就是換回驗證版）。`rollback()` 與 `previous()`：上一版不在清單 → 視為沒有可退版本（MainActivity 的 `MainOps` 也用同一個過濾）。測試 `pinnedVersionIsVerified`：`BuildConfig.DSH_PINNED_VERSION in VERSIONS`。
  - 版本段落文字：「只會更新到這版 App 驗證過的 dsh 版本：%s」（逗號串接）；已最新「已是驗證過的最新版 %s」；目前未驗證時紅字「目前的 dsh %1$s 未經這版 App 驗證，介面可能無法正常運作。按「更新」會換回已驗證的 %2$s。」
  - 新段落「效果強度」EFFECTS：`Segmented(["全開","平衡","省電"])`＋提示「畫面一卡頓，會自動先關掉模糊效果，保持順暢。」；選了立刻存 `UiPrefs.effectLevel` 並套用（`onEffectLevel`），不需「套用並重啟」。
  - 「模型」段落加「寫小說用的模型」（單選 `AgentModels.available(hasClaude)`，顯示名稱）＋提示「在書架開新書或「下一章」時使用（下一版開放）」；選了立刻存，不重啟 dsh。
  - 其他段落（金鑰、代理人數量／深度／團隊、主模型、預算與硬上限、心跳、子行程限制、匯出、套用並重啟）功能與測試不變，只換成 `FogPanel`（標題中文＋英文：KEYS、AGENTS、MODELS、BUDGET、HEARTBEAT、DSH、SYSTEM、EXPORT）。
  - 總覽的 `dshVersion` 接 `{ runCatching { app.dshVersions.current() }.getOrNull()?.takeUnless(VerifiedDsh::isVerified) }`（IO 上讀）。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class VerifiedDshTest { @Test fun pinnedVersionIsVerified() ; @Test fun newestIsTheLastEntry() ; @Test fun nullAndUnknownAreNotVerified() }
// DshVersionsTest 加：
    @Test fun aGivenTargetSkipsNpmView()                  // FakeGuestCommand 記錄：沒有 ["npm","view",…]，有 install 該版本
// SettingsScreenTest（沿用既有 fakes，SettingsFakeVersions 加 verified()）：
    @Test fun effectStrengthDefaultsToFullAndSavesAtOnce()       // 點 EFFECT2 → uiPrefs.effectLevel == SAVER、onEffectLevel(SAVER)
    @Test fun novelModelListsOnlyAvailableModelsAndSaves()
    @Test fun theVerifiedListIsShown()
    @Test fun anUnverifiedCurrentVersionWarnsAndUpdateSwitchesToTheNewestVerified() // current "0.2.2" → UNVERIFIED_WARNING；check-update → versions.checkUpdate 被叫
    @Test fun rollbackIsDisabledWhenThePreviousVersionIsUnverified()               // previous() 回 null（已過濾）→ rollback 停用
    // 既有測試全部保留並通過（金鑰、步進、預算、心跳、匯出、apply、job 存活…）
// AgentSettingsStoreTest 加：novelModelDefaultsToSonnetAndPersists
```

- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS（含既有的 `SettingsScreenTest`、`DshVersionsTest`、`MainActivityTest` 退版測試）
- [ ] **Step 5: Commit** `feat: settings in the native shell with effect strength, novel model and verified-only dsh updates`

---

### Task 14: 又炫又順：掉格偵測、自動降級、暫停動畫、JankStats 門檻

**Files:**
- Create: `ui/fog/JankGovernor.kt`、androidTest `PerfGateTest.kt`
- Modify: `ui/MainActivity.kt`（JankStats、有效效果等級、閒置偵測、`LocalPageActive`）、`app/build.gradle.kts`＋`libs.versions.toml`（`metrics-performance 1.0.0`；androidTest `uiautomator 2.3.0`）、androidTest `FakeModel.kt`（`E2E_LONG`）
- Test: `test/.../ui/fog/JankGovernorTest.kt`、`test/.../ui/MainActivityTest.kt`（加測試）

**Interfaces:**
- Consumes: Task 3 `Effects`、`effectsFor`、`UiPrefs`、`FogSemantics.Animating`；Task 13 `onEffectLevel`。
- Produces:
  ```kotlin
  class JankGovernor(private val windowFrames: Int = 120, private val jankyPerWindow: Int = 12, private val windowsInARow: Int = 2) {
      val cap: StateFlow<EffectLevel>           // 起始 FULL；只往下降
      fun onFrame(isJank: Boolean)              // 每滿 windowFrames 判一次；連續 windowsInARow 個窗 janky ≥ jankyPerWindow → 降一級並重新計數
      fun reset()                               // 使用者改了效果強度時
      val frames: Long; val janky: Long }
  fun effectiveLevel(user: EffectLevel, cap: EffectLevel): EffectLevel   // 取較省的一個
  // MainActivity 測試接縫：internal val governorForTest: JankGovernor
  ```
  - 降級門檻（planner's call）：120 幀一窗（120 Hz 約 1 秒）、一窗 ≥ 12 幀掉格（10%）且連續兩窗 → 降一級；同一個程序內不自動升回（避免來回閃）。每次降級 `ErrorLog` 記一行 `perf: effects capped at BALANCED after N janky of M frames`；每次 onStop 若有新幀，記 `perf: frames=N janky=M (x.x%) level=…`——這就是在 S25 Ultra 上量實際數字的方法（匯出錯誤記錄即可看到）。
  - JankStats：`JankStats.createAndTrack(window) { governor.onFrame(it.isJank) }`；`isTrackingEnabled` 只在 STARTED 時為 true。`LocalPageActive` ＝ 目前分頁 且 lifecycle ≥ STARTED；`LocalIdle` ＝ 8 秒內沒有任何觸控（根部 `pointerInput` 觀察，不消耗事件）。`systemReducedMotion()` 在 onResume 重讀。
  - 門檻（planner's call：不另建 Macrobenchmark 模組——JankStats 本來就要在正式版裡跑才能自動降級；Macrobenchmark 需要另一個可 profile、另簽章的 build type 加 rootfs 打包與裝置上完成引導，而 CI 的 swiftshader 模擬器本來就驗證不了 120 Hz；實機數字靠上面的 perf 記錄）：`PerfGateTest` 在 CI 模擬器上用 UiAutomator 操作、另掛一個 JankStats 計數，三個情境各量一次：(1) 分頁 總覽→對話→代理人→設定→總覽 來回 3 次；(2) 在一則 `E2E_LONG`（約 60 段 Markdown，含表格與程式碼）回覆的對話中上下滑 5 次；(3) `E2E_SLOW` 串流中停在對話頁 10 秒。每個情境先暖身一輪（讓自動降級穩定）再量。測試開始前用 `uiAutomation.executeShellCommand("settings put global animator_duration_scale 1")`（transition、window 同）打開動畫，結束還原——CI 的 `disable-animations: true` 會讓 App 以為使用者關了動畫，量到的就不是真的霧城。初始門檻 `MAX_JANK_FRACTION = 0.10f`（每情境）；第一次在 CI 綠了之後，把三個情境各自的實測值×1.5（無條件進位到 0.01、上限 0.10）寫成各自常數，實測值寫進 KDoc。實測超過 0.10 時不准放寬，回報主腦。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
class JankGovernorTest {
    @Test fun smoothFramesNeverDegrade()
    @Test fun oneBadWindowIsNotEnough()
    @Test fun twoBadWindowsInARowStepDownOnce()        // FULL → BALANCED，再兩窗 → SAVER，再多也停在 SAVER
    @Test fun aGoodWindowBetweenResetsTheStreak()
    @Test fun resetGoesBackToFull()
    @Test fun effectiveLevelTakesTheThriftierOne()
}
// MainActivityTest：
    @Test fun degradingTurnsBlurOffInTheShell()          // governorForTest 餵兩窗掉格 → 殼的玻璃 FogSemantics.Blur == false
    @Test fun backgroundAnimationStopsWhenTheActivityStops() // stop 後 SHELL_BACKGROUND 的 Animating == false
    @Test fun changingEffectStrengthResetsTheCap()
    @Test fun perfSummaryIsLoggedOnStopWithoutContent()  // errorLog 含 "perf: frames="，不含任何對話文字
// androidTest PerfGateTest：tabCyclingStaysUnderTheGate、scrollingALongReplyStaysUnderTheGate、streamingStaysUnderTheGate
```

- [ ] **Step 2: 跑測試確認失敗**（unit）→ FAIL
- [ ] **Step 3: 實作**；`FakeModel` 加 `E2E_LONG` 路線（見 Task 15 的路線表；本任務先加這一條與 `E2E_SLOW`）
- [ ] **Step 4: 跑 unit 測試確認通過** → PASS；`:app:assembleDebugAndroidTest` 編得過；推到 CI 讓 `PerfGateTest` 跑，依上面規則定門檻常數
- [ ] **Step 5: Commit** `perf: JankStats auto-degrade, paused off-screen animations and a CI jank gate`

---

### Task 15: 驅動原生畫面的 e2e（可發版）

**Files:**
- Modify: androidTest `FakeModel.kt`、`E2eSupport.kt`、`E2eTest.kt`
- 不改 `E2eRunner.kt`、CI workflow（`connectedDebugAndroidTest` 已涵蓋新測試）

**Interfaces:**
- Consumes: 全部前面任務的 `Tags`、`MainActivity.navForTest`／`uiStateForTest`／`attachForTest`、`app.shellSessions`、`app.uiPrefs`、`app.reminderLog`、`app.costMeter`、`DshRemote.mux.dropForTest()`。
- Produces:
  ```kotlin
  // FakeModel：依「最後一則使用者訊息的文字」分路線（不是 tool_result 的那則）；跟進請求（最後一則含 tool_result）依 tool_use id 前綴分路線
  enum class Kind { DREAM, SEARCH_BACKEND, TOOL_USE, FOLLOW_UP, HELLO, SLOW, LONG, SEARCH, APPROVE, ASK, SUB, CHILD, FILE, OTHER }
  data class Seen(val path: String, val kind: Kind, val apiKeyIsTheVaultKey: Boolean, val model: String?, val body: String)
  // E2eSupport
  fun launchShell(): ActivityScenario<MainActivity>          // prepareApp 之後才啟動；awaitShellReady
  fun ComposeTestRule.tap(tag: String); fun ComposeTestRule.type(tag: String, text: String)
  fun ComposeTestRule.awaitTag(tag: String, timeoutMs: Long = 120_000); fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = 120_000)
  fun ComposeTestRule.newConversation(): String             // 對話分頁 → 清單 → NEW_SESSION；回傳 navForTest.openSessionId
  fun ComposeTestRule.send(text: String)                    // COMPOSER_INPUT 輸入 → SEND
  ```
  FakeModel 路線表（關鍵字放在提示文字裡；回覆都用 SSE，`message_delta.usage` 改為 `input_tokens 20000、output_tokens 1000`，讓花費在總覽上看得到）：
  - 搜尋後端請求（內容含 `Perform a web search for the query:`）→ **非串流** JSON：`content = [{type:"web_search_tool_result", content:[{type:"web_search_result",url:"https://example.com/e2e-weather",title:"E2E Weather"},{type:"web_search_result",url:"https://example.org/e2e-market",title:"E2E Market"}]}, {type:"text",text:"ok",citations:[{url:"https://example.com/e2e-weather",cited_text:"sunny"}]}]`、`stop_reason:"end_turn"`、usage 同上。
  - `E2E_HELLO` → thinking 區塊「E2E thinking」（含 `signature_delta`）＋分三段送出的文字 `**E2E** reply\n\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |`。
  - `E2E_SLOW` → 40 段文字，`throttleBody(64, 300, ms)`（約 20 秒）。`E2E_LONG` → 60 段含表格與程式碼的 Markdown，一次送完。
  - `E2E_SEARCH` → `tool_use` `web_search {"queries":["e2e weather"]}`（id 前綴 `toolu_search_`）；跟進 → 「Sunny today. [E2E Weather](https://example.com/e2e-weather)」。
  - `E2E_APPROVE` → `tool_use` `write {"file_path":"/tmp/e2e-outside.txt","content":"x","sandbox_permissions":"danger-full-access","justification":"E2E approval"}`；跟進 → 「E2E approved」。
  - `E2E_ASK` → `tool_use` `ask_user_question`（q1「Which color?」header「Choose」選項 Pink、Blue）；跟進：tool_result 含 Pink → 「E2E picked Pink」。
  - `E2E_SUB` → `tool_use` `subagent {"description":"E2E child","prompt":"E2E_CHILD work slowly"}`；`E2E_CHILD` → 同 SLOW 但 30 秒。
  - `E2E_FILE` → 「E2E got the file」。
  - 原本的記憶路線只在提示含 `remember the E2E mark` 時（t3 不變）；dream 判斷不變；其他 → 「E2E title」。

- [ ] **Step 1: 改寫 FakeModel 與 E2eSupport**（`createEmptyComposeRule()`；先 `prepareApp()` 再 `launchShell()`，所以 onboarding 不會擋）
- [ ] **Step 2: 寫測試**（依名稱排序執行、共用 dsh、各自新開對話；斷言用 Tags；等待用 `awaitText/awaitTag` 與 `E2e.waitFor`）

```kotlin
// 保留：t2（無 cookie 拒絕）、t3（RPC 記憶）、t4（心跳提醒，另加：總覽 OVERVIEW_REMINDERS 顯示 "E2E-REMIND"）、
//   t5（壞版本自動退版；另加：settings 的 checkUpdate 在 npm 說 9.9.9 時不安裝——FakeGuestCommand 從未收到 install）、t6、t7（picker RPC）
@Test fun t1_theNativeShellIsReadyWithoutAWebView()           // Task 4 已有；另加五個 TAB 都在、SHELL_BACKGROUND 存在
@Test fun t10_aReplyStreamsAsMarkdownWithCollapsibleThinking() // E2E_HELLO → ASSISTANT_BUBBLE 含 "reply"、"one"、表格 "2"；THINKING 收著，點開見 "E2E thinking"
@Test fun t11_webSearchShowsTheSourceCardAndCitations()        // E2E_SEARCH → SEARCH_CARD 文字 "搜尋網路 · 2 個來源"、SOURCE_CHIP1 "E2E Weather"、CITES "來源 ①"
@Test fun t12_anApprovalMustBeAnsweredAndThenTheToolRuns()    // E2E_APPROVE → PROMPT_SHEET；切到設定分頁仍在；APPROVE_ONCE → awaitText("E2E approved")、該工具卡 "完成"
@Test fun t13_aQuestionIsAnsweredFromTheCard()                 // E2E_ASK → OPTION q1-0 → QUESTION_SUBMIT → awaitText("E2E picked Pink")
@Test fun t14_stopEndsAStreamingReply()                        // E2E_SLOW → 出現 LIVE_BUBBLE 後 STOP → 15 s 內 NOTICE "已停止"、STOP 不見
@Test fun t15_aPromptSentWhileRunningIsQueuedThenAnswered()    // E2E_SLOW 進行中送 "E2E_HELLO again" → QUEUED 出現 → 之後 "reply" 第二次出現、QUEUED 消失
@Test fun t16_aFileAttachmentReachesTheModel()                 // cache/export/e2e-note.txt（內容 E2E-FILE-CONTENT）→ FileProvider URI → attachForTest → 送 "E2E_FILE read it" → "E2E got the file"；FakeModel 該請求 body 含 "e2e-note" 或 "E2E-FILE-CONTENT"
@Test fun t17_theModelSwitchSendsTheNextTurnToThePickedModel() // MODEL_CHIP → MODEL_OPTION deepseek-v4-pro → 送 E2E_HELLO → 該請求 Seen.model == "deepseek-v4-pro"
@Test fun t18_renameAndDeleteAConversation()                   // 改名 "E2E 改名" → 清單列顯示；刪除確認 → 列消失；重啟 App（scenario 重建）後仍不在
@Test fun t19_theAgentsTabListsARunningSubagentAndStopsIt()    // E2E_SUB → 代理人分頁 AGENT_ROW 標籤 "E2E child" 執行中 → AGENT_STOP → 30 s 內變已結束
@Test fun t20_overviewShowsTodaysSpendAndRecentConversations() // OVERVIEW_RING contentDescription 不是 "今日花費 US$0.0000"；OVERVIEW_RECENT+最近對話 id 存在
@Test fun t21_effectStrengthPersistsAndCalmsTheShell()         // 設定 EFFECT2（省電）→ uiPrefs.effectLevel == SAVER；SHELL_BACKGROUND Animating == false；改回 EFFECT0
@Test fun t22_aNotificationDeepLinkOpensTheConversation()      // 以 EXTRA_SESSION_ID 啟動 → CHAT_TITLE 是該對話標題
@Test fun t23_anApprovalPendingAcrossAReconnectIsStillAnswerable() // E2E_APPROVE → 卡片出現 → shellSessions.current!!.remote.mux.dropForTest() → 重連後仍只有一張卡 → APPROVE_ONCE → "E2E approved"
```

- [ ] **Step 3: 本地編譯** `cd android && ./gradlew :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL
- [ ] **Step 4: CI 模擬器全綠**：推到分支，`e2e` job 的 `connectedDebugAndroidTest`（E2eTest t1–t23、PerfGateTest）全 PASS；失敗時看上傳的 logcat 與錯誤記錄修到綠（整合問題的修正屬本任務範圍，如同第一期 Task 13 的 Ruling）
- [ ] **Step 5: 主流程無 WebView 的最後檢查**：`grep -rn "android.webkit" android/app/src/main` 無結果
- [ ] **Step 6: Commit** `test: release-ready e2e suite that drives the native FOGCITY UI`

---

## 任務順序與相依

1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15。
3 只依賴既有主題，可與 1–2 並行；7 只依賴 3，可與 5–6 並行；9、10、11 互不相依（都在 8 之後）；12 用到 11 的 `runningCount`，13 用到 12 的 `dshVersion` 接點；14、15 最後。

## Follow-on plans

**第二階段：書架＋閱讀模式。** 角色設定卡（存工作區 `novels/` 下，可自己寫或請 AI 整理）、「＋新書」流程（書名、角色設定、寫作模型＝本階段已存的 `AgentSettingsStore.novelModel`）＝開一個專屬對話並把角色設定放在開頭；正文回覆自動成為章節（可標「不是章節」或手動加入、一般對話可「加入書架」）；書、章節、閱讀進度存工作區（匯出時帶走）；電子書式閱讀模式（字級、夜間、百分比、記得位置、「下一章」＝在該書對話請模型續寫），閱讀時背景效果降到最低；對話頁長回覆旁加「📖 閱讀模式」；書架分頁取代第一階段的說明文字。

**第三階段：代理人監看牆。** 用 `session/control` 的 `subagentCatalog`、`subagentTiming`、`tokenUsage`（加總子會話）、`agentTeam`，與 `$events` 生命週期事件，畫上方雷達（總指揮在中心、依深度排內外圈、狀態色：水藍執行、粉紅等待、奶茶完成、深玫瑰褐閃爍出錯）、並行分段條、本次任務花費與 token；下方每個代理人一塊小螢幕（目前動作圖示與正在產生的文字，來自子會話的 `session/follow` subagent address）；點開看完整步驟、`subagents/prompt` 傳話、`interruptByParent` 叫停；Agent Teams 隊友與任務板。取代第一階段的簡易清單。

**第四階段：dsh 提議分頁。** App 的霧城零件庫（大小圓環、長條、進度條、格子、清單、數字讀數、雷達、文字卡）＋宣告式 JSON 分頁格式；新 dsh 插件工具 `dshm_page_propose`／`dshm_page_update`（不能帶程式碼）；玻璃提案卡（縮小預覽、加上去／不要／改一下，拒絕過的不再提）；已核准分頁的資料更新直接生效；零件可綁 App 即時數值；需要定期更新的提案要寫明頻率與估計費用、同意後由心跳執行；固定頁不受影響。這一階段會改 `plugins/`（順便處理已不顯示的 `@dshm/ui` 網頁主題）。

**第五階段：dsh 進階功能頁。** 插件（`pluginManager/*`、`pluginMarket/*`）、MCP、技能（`skills/list`）、排程任務（`schedule/*`）、斜線指令（`commands/list|execute`）、權限與代理人預設（`permissionPresets/catalog`、`agentPresets/*`）的原生管理畫面，沿用 `DshRemote` 與霧城零件；每頁先用協定實測確認形狀再做，並把新用到的 dsh 版本行為補進驗證清單流程。
