# DSH Mobile：設計說明（給 AI 參考用）

> 這份文件說明 DSH Mobile 是什麼、怎麼組成、為什麼這樣設計。
> 程式碼在同一個壓縮檔裡，路徑都以專案根目錄為準。
> 寫於 2026-11-14，對應分支 `claude/dsh-mobile-cloud-handoff-vfhvdh` 的最新版。已發布的是 v0.7.0+14；分支上另有「薄頂列、舒服風格首頁」的改動，還沒發版。

---

## 1. 這是什麼

一個 Android App，讓使用者在手機上用 **DeepSeek Harness（dsh）**。dsh 是官方的開源 AI 代理程式（Node.js 寫的），平常跑在電腦上、用網頁介面操作。這個 App 做了兩件事：

1. **把 dsh 本體搬到手機裡跑**，不需要電腦或伺服器。
2. **用原生 Android 介面（Jetpack Compose）取代 dsh 的網頁介面**。App 透過 dsh 自己的 RPC／WebSocket API 跟它溝通。

使用者是一個人（非工程師、看繁體中文、手機字體開很大），用途是：查台股、寫小說、記帳、查資料。

### 不能違反的原則

- **不改官方 dsh 的原始碼**。所有手機適配都在 dsh 外面做（啟動參數、設定檔、執行前注入的轉接層）。
- **API 金鑰只存在 Android Keystore**，永遠不交給 dsh、不寫進設定檔或紀錄檔。做法是：App 在本機開一個代理（gateway），dsh 打給代理，代理再補上真正的金鑰往外送。
- **對話紀錄與使用統計一律不上傳**（dsh 的遙測都在設定裡關掉）。
- **Android 權限最多 9 個**（見 `AndroidManifest.xml`）。
- **顏色只用六色**：`#FEDFE1`（底）、`#D7C4BB`（奶茶）、`#DC9FB4`（櫻粉，主色）、`#FFFFFF`、`#A5DEE4`（水藍）、`#9E7A7A`（玫瑰棕）；文字 `#5E4445`／`#7D5F60`。
- **介面文字繁體中文、白話**；按鈕至少 48dp；在 360dp 寬、字體 1.3 倍下仍可用。

---

## 2. 整體架構

```
┌──────────────────────── Android App（Kotlin）────────────────────────┐
│                                                                      │
│  UI（Compose）── 聊天、對話清單、指揮室、設定、首頁、各種卡片         │
│      │                                                               │
│  ShellSession ── 管一個 dsh 連線：對話樹、清單、每個對話的 Store      │
│      │  RPC（HTTP POST /api/<method>）+ WebSocket 多工（/api/remote.mux）│
│      ▼                                                               │
│  ┌──────────── dsh 行程（Node.js，App 啟動並看守）────────────┐       │
│  │  官方 dsh 0.2.1-rc.5 + App 自己的 3 個 dsh 插件            │       │
│  │  （memory 記憶工具、notify 手機通知、ui 介面）              │       │
│  └──────────────┬─────────────────────────────────────────────┘       │
│                 │ 打模型 API（baseURL 指向本機 gateway，假金鑰）       │
│                 ▼                                                     │
│  LocalGateway ── 補上 Keystore 裡的真金鑰、算花費、擋暴衝、轉發 SSE    │
│                 │                                                     │
└─────────────────┼─────────────────────────────────────────────────────┘
                  ▼
        DeepSeek API ／ Anthropic（Claude）API
```

### 2.1 dsh 怎麼在手機上跑（`runtime/`）

有兩種方式，設定 → 進階設定 → 執行方式 可切換：

| | 原生模式（v0.7.0 起預設） | 相容模式（舊的，留著當備援） |
|---|---|---|
| 怎麼跑 | 用 Termux 編好的 Android 版 node 直接跑 dsh | 用 proot 模擬一個 Debian Linux，在裡面跑 Linux 版 node |
| 程式 | `NativeRunner.kt` | `ProotRunner.kt`、`RootfsInstaller.kt` |
| 優缺點 | 快、省電；要處理 Android 跟 Linux 的差異 | 相容性最好；慢、耗電 |

**原生模式的關鍵：`assets/dshn/shim.mjs`**。用 `node --import` 在 dsh 之前載入，不改 dsh 任何檔案，只補 Android 的差異：

1. `process.platform` 回報 `linux`（dsh 只認得 linux）。
2. `node-addon-require-builtin` 沒有 Android 版 → 導到 `require-builtin.cjs`，配合 `--expose-internals`。
3. Android 的 SELinux 不准建立硬連結；dsh 寫紀錄時用 `link()` 防覆蓋 → 失敗時改成「排他複製＋fsync」。
4. 原生程式只能從 App 的 native library 目錄執行（W^X 限制）→ dsh 的檔案鎖 addon 和 ripgrep 換成 Android 版。
5. dsh 存圖片時會一路往上 fsync 到根目錄；Android 不准開 `/data/user/0` 這些系統層 → 被拒時回傳一個「同步什麼都不做」的假 handle。
6. 在相容模式下建立的對話，資料夾記成 `/root/workspace`；原生模式用路徑對應（`DSHN_REMAP`）把它對到手機上真正的位置，所以兩種模式共用同一份對話。

執行檔（node、bash、rg、git）以 `libdshn_*.so` 的名字放在 jniLibs，啟動時在 App 資料夾建符號連結。打包腳本在 `native/`（從 Termux 套件庫抓、鎖版本在 `native/termux.lock`）。

圖片處理用 sharp 的 WebAssembly 版（`@img/sharp-wasm32`），因為 Linux 版的原生模組在 Android node 上載不起來。安裝方式見 `rootfs/inner-setup.sh`（裝在旁邊、只複製缺的檔案，避免 npm 刪掉 dsh 的其他零件）。

### 2.2 看守與重啟（`service/`）

- `DshController`：啟動、等 dsh 印出登入網址、健康檢查（`HealthCheck`）、停止（整棵行程樹一起關，`ProcessTree`）。
- `DshService`：前景服務，聊天時讓 dsh 不被系統殺掉；dsh 掛了由 `CrashPolicy` 決定要不要自動重啟。
- `PhantomProcessDetector`：Android 12+ 會殺 App 的子行程，偵測這個限制並在設定裡教使用者處理。

### 2.3 設定怎麼交給 dsh（`config/`）

dsh 支援 `--patch <yml>` 覆蓋設定。`DshPatch.kt` 產生這份 YAML：
- 模型供應商（baseURL 指向本機 gateway，金鑰欄位只放環境變數名稱）。
- 預設模型、子代理數量上限。
- 關掉 dsh 的遙測與上傳。
- 掛上 App 自己的 3 個 dsh 插件（`plugins/packages/`，TypeScript）。

**已驗證的限制**：`--patch` 頂層的 `- id: xxx` 會整個取代那一列的設定；但 dsh「代理預設組合」（agent preset）裡面巢狀的列（例如對話壓縮門檻、子代理工具、插件管理工具）從頂層碰不到，會被默默忽略。要改那些，得自己定義一份 agent preset（還沒做）。

`AppSkills.kt` 把 App 的技能寫成 dsh 的 `SKILL.md`（`$DSH_HOME/skills/dshm-*`）：記帳、小說、每日簡報、天氣、畫圖（SVG）、成品、深度研究、沉思（三方意見）、對話卡片、生圖等。技能平常只有標題佔 AI 的注意力，用到才讀全文。寫法原則：講清楚要什麼結果，不寫「必須」「第一步第二步」這種命令式寫法（新一代模型會過度照做）。

技能也可以在 `SKILL.md` 旁邊附檔案（`AppSkills.files`，內容來自 `assets/skills/<技能>/`，每次啟動有變才重寫，不再附的就刪掉）。記帳技能（生活紀錄：記帳、熱量、運動、體重、Uber 週結算）附 `life.mjs`：所有加總、預算、分桶、熱量都由它算，AI 不心算；資料是工作區 `生活/` 裡的 CSV 和 `設定.json`（所有數字和清單都能改，沒改就用預設值）。AI 用 `node "$DSH_HOME/skills/dshm-bookkeeping/life.mjs" <指令>` 呼叫，路徑保留變數，權限守門才不會每次都問。

### 2.4 金鑰與花費（`gateway/`、`security/`）

- `KeyVault`：Android Keystore 加密保存 DeepSeek、Claude、氣象署金鑰。
- `LocalGateway`：只聽 127.0.0.1，換上真金鑰後轉發；串流（SSE）逐塊轉送。
- `CostMeter`＋`PriceTable`＋`UsageParser`：依模型單價算今日花費，可設提醒和硬上限。
- `RateGuard`：每分鐘請求數上限，防止失控的迴圈燒錢。

### 2.5 跟 dsh 對話（`remote/`、`chat/`）

- `DshRemote`／`Rpc`：呼叫 dsh 的 RPC（`session/create`、`session/prompt`、`session/selectModel`、`commands/execute` 等）。
- `MuxConnection`：一條 WebSocket 上多工多個串流（`$events`、`session/follow`…），斷線自動重連。
- `SessionTree`／`SessionIndex`：所有對話與子代理的樹狀結構、對話清單。
- `ConversationStore`＋`ConversationReducer`：把 dsh 的事件（快照、歷史、串流片段）折疊成畫面要的狀態 `ConversationState`。串流文字放在 StringBuilder，最多每 50ms 更新一次畫面，避免十萬字回覆變成平方級的工作量。
- `DshEvents`：權限請求與 AI 提問（例如「可以執行這個指令嗎」、計畫審核）→ 畫面上的確認卡。

### 2.6 介面（`ui/`）

- `ui/shell/`：外殼。`FogShell` 是頁面容器＋底部輸入列（`Dock`）＋浮在上面的確認卡；`ShellNav` 管目前在哪一頁、開哪個對話。
- `ui/chat/`：聊天畫面（`ChatScreen` 是最大的檔案）、對話清單、首頁（`ChatHome`）、各種卡片（計畫、待辦進度、沉思三方、檔案成品、對話內卡片）、鎖定對話。
- `ui/markdown/`：自己寫的 Markdown 渲染（commonmark-java 解析成小型 block 模型再畫）。長回覆切成很多小塊放進 LazyColumn，捲動才不會卡。
- `ui/agents/`：指揮室（同時在跑的代理、用量、花費、時間軸）。
- `ui/fog/`：共用元件與主題（玻璃面板、按鈕、動畫規格、特效強度、背景光暈）。
- `SettingsScreen`：設定。預設只顯示金鑰、模型、版本；其他在「進階設定」裡。

### 2.7 其他

- `heartbeat/`：WorkManager 定時喚醒 dsh 整理記憶、順便每天檢查一次更新。
- `update/`：從公開的 releases 倉庫抓新版 APK、驗 SHA-256、交給系統安裝。
- `notify/`：通知（做完了、提醒、花費警告）。鎖定的對話不顯示標題內容。
- `weather/`：App 自己做的天氣分析（六個全球模式加權、氣象署測站校正、系集算降雨機率），寫成 `.dshm/weather.md` 給 AI 讀。
- `export/`：匯出記憶、工作區、錯誤紀錄（匯出前會把金鑰樣式的字串遮掉）。

---

## 3. 功能一覽（使用者看得到的）

**一直顯示的**
- 聊天：打字、語音輸入、拍照或選圖、附檔；回答邊想邊顯示，可停止；長按訊息可複製、朗讀、編輯重送、重新回答、從這裡分支。
- 對話裡的卡片：AI 在回答最後寫一個隱藏區塊（`<!--dshm-card quote ... -->`），App 把它畫成卡片。目前有股價、記帳、提醒、天氣（`chat/InlineCards.kt`）。
- 建議下一步：回答後的 2～3 個可點問題（`chat/FollowUps.kt`）。
- 模型與思考強度：每個對話可切換；Claude 有低／中／高／超高／最高，DeepSeek 有關／低／高／最高。
- 長對話自動整理：超過約 12 萬 token 且沒在跑時，App 自動叫 dsh `/compact`（`chat/AutoCompact.kt`）。
- 指揮室：看所有正在跑的 AI 和子代理，可停止。
- 鎖定對話（用手機的指紋／臉／密碼解鎖）、臨時對話、釘選、從其他 App 分享進來。

**「顯示更多功能」打開後才出現**
- 總覽、書架（小說、檔案）、專案（自訂小助理）、小幫手（定時任務）、聊天框狀態列。

---

## 4. 設計想法

### 4.1 為什麼不用 dsh 原本的網頁介面
手機上 WebView 又慢又難用，字體放大後版面會壞。原生介面才能做到大字體、流暢捲動、系統級的鎖定與通知。

### 4.2 為什麼堅持不改 dsh 原始碼
dsh 會一直更新。只在外面加轉接層，升級 dsh 時只要確認轉接層還適用，不用重新合併程式碼。

### 4.3 「功能很多，但畫面不放按鈕」
參考 ChatGPT、Claude 的手機 App：主畫面只有聊天。功能是「對話裡跳出來的卡片」，AI 判斷需要才出現，用講的就能觸發，不用使用者去找按鈕。

### 4.4 讓 AI 變聰明的做法
- 少塞規矩：拿掉一套給寫程式用的嚴格流程（superpowers），它讓 AI 對每個問題都先反問、先寫計畫，又慢又不自然。
- 技能寫結果、不寫步驟。
- 思考強度照模型給正確選項（Claude Opus 5.5 不能關思考，預設要設「高」）。
- 長對話自動壓縮，避免越聊越笨。
- 一個對話全程用同一個模型（Claude 或 DeepSeek 各有優點，不拆）。

### 4.5 視覺方向（正在轉換中）
- 舊：「霧城」科技風（透視格線、發光、英文小標、編號、HUD 圓環、毛玻璃）。
- 新（使用者要求「不要科技感，要舒服的感覺」）：同樣六色；圓角軟卡片、多留白、少邊框、不發光；系統字型；一個畫面只有一個粉紅主按鈕；動畫往 iOS 那種彈簧式、可中斷、跟手的感覺做。
- 已完成：首頁、背景（拿掉格線）、字型、頂部改成一條薄列、點空白處收起選單。
- 還沒做：聊天畫面、指揮室、設定的改造；彈簧動畫。

---

## 5. 已知問題與下一步

| 項目 | 狀態 |
|---|---|
| 原生模式下長回覆「一段一段跳」 | 原因已找到：回覆超過 2000 字後，每次更新都把整則回覆重新解析，越長越慢。修法：只重新解析最後一段（已完成的段落快取起來），並把一次湧進來的文字分幾格平順顯示。已寫好但還沒合併 |
| App 太大（約 210MB） | 原生和模擬器兩套執行環境都還在。確認原生穩定後刪掉模擬器那套 |
| AI 自己從插件庫裝插件 | dsh 內建「找插件」「裝插件」工具但預設關著，而且藏在 agent preset 裡碰不到。計畫：自訂一份 preset 打開它們、在手機上補一個套件安裝程式、App 端做安全掃描（綠：直接裝／黃：問使用者／紅：擋掉） |
| 生圖 | 只留接口：設定裡填電腦上生圖服務的位址（使用者的 Windows 電腦，AMD 9070 XT），AI 讀 `.dshm/image-gen.md` 知道怎麼用 |

---

## 6. 建置與測試

- Android：`cd android && ./gradlew :app:testDebugUnitTest`（Robolectric，約 1200 個單元測試，含介面測試）。
- 模擬器整合測試在 CI 跑（`.github/workflows/ci.yml` 的 e2e 工作，`androidTest/` 裡的 `E2eTest`、`NativeModeE2eTest`；用 `FakeModel` 假裝模型回答）。
- 發版：`release.yml`，推一個 `v<版本>+<版本號>` 標籤觸發；可以先用 `dry_run` 試打包。
- dsh 插件：`plugins/`（pnpm、vitest）。
- 技能附的腳本：`node --test 'android/app/src/test/node/**/*.test.mjs'`（`life.mjs` 的測試，假資料在 `fixtures/`）。
- 相容模式的 Debian 映像：`rootfs/build-rootfs.sh`；原生執行環境：`native/build.sh`。

## 7. 讀程式碼建議的順序

1. `android/app/src/main/kotlin/app/dshmobile/DshMobileApp.kt`：所有元件在這裡組起來。
2. `runtime/NativeRunner.kt`、`assets/dshn/shim.mjs`：dsh 怎麼在 Android 上跑。
3. `config/DshPatch.kt`、`gateway/LocalGateway.kt`：設定與金鑰怎麼處理。
4. `remote/DshRemote.kt`、`chat/ConversationStore.kt`、`chat/ConversationReducer.kt`：跟 dsh 的資料流。
5. `ui/shell/FogShell.kt`、`ui/chat/ChatScreen.kt`：畫面。
6. `docs/dsh-findings.md`、`docs/dsh-ui-protocol.md`：當初研究 dsh API 的筆記。
