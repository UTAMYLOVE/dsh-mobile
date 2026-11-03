# DSH Mobile 第二期（0.3.0）設計補充

日期：2026-11-04。對象：DSH Mobile（`app.dshmobile`），目標機 Galaxy S25 Ultra（412×915 dp）。
上位文件：`docs/superpowers/specs/2026-11-03-dsh-mobile-fogcity-ui-design.md`（霧城原生介面規格，仍有效）。
需求來源（**有約束力**）：`docs/superpowers/specs/2026-11-04-stage2-requirements.md` A–G 節與紅線。
問題 A 的根因與修法：`docs/sdd-ledger/bug-writer-held.md`（F1–F10）。
實作計畫：`docs/superpowers/plans/2026-11-04-dsh-mobile-stage2.md`。

本文件只寫「怎麼做」的設計決定。凡是需求沒有指定、由規劃者決定的，標為 **planner's call**；凡是需求做不到或彼此衝突的，列在 §9，並寫明改用的做法。

---

## 1. 範圍與發佈

宇哥說「你直接做下一版」：A–G 全部進 0.3.0（versionCode 5），一次發佈，不拆小版本。上位規格 §5.10 原本的「第 2 階段書架」「第 3 階段監看牆」在本版一起完成；第 4 階段（dsh 提議分頁）、第 5 階段（進階功能頁）不在本版。

紅線不變：官方 dsh 一行不改（固定 0.2.1-rc.5）；權限仍是那 9 個；金鑰只在 Keystore；對話與使用統計上傳關閉；dsh、閘道只綁 127.0.0.1；配色只用霧城六色＋兩個文字色，錯誤紅 `#B3261E` 只用於錯誤狀態；字體 Orbitron、Chakra Petch（中文用系統字，見 §9-1）。

**planner's call：本版不改 `plugins/`。** 依第一期的發現，`plugins/**` 的內容雜湊是 rootfs 版本的一部分，改一個字就會讓每台手機重裝約 130 MB 的 rootfs。本版所有功能都靠 App 端與 dsh 現有的協定完成（見 §3.2 的取捨）。

---

## 2. A：重啟卡住與 `session/writer-held`

根因（`bug-writer-held.md`）：Android 的 `Process.destroy()`／`destroyForcibly()` 都只送 SIGTERM，而 proot 忽略 SIGTERM，所以舊的 proot＋node 從沒被殺掉；它的 Agent 還握著 `session.lock` 的 flock，新 dsh 一 resume 就是 `SessionAlreadyOwnedError` → `session/writer-held`。

### 2.1 行程樹的結束方式（F1）

新的 `ProcessTreeKiller`（`runtime/`），`DshController.tearDownLocked` 與 `ProotGuestCommand` 都改用它：

1. **找到 proot 的 pid**：解析 `Process.toString()` 的 `pid=N`（Android 與桌面 JVM 都印這個格式），解不出來時用反射讀 `pid` 欄位。同時記下 `/proc/<pid>/stat` 第 22 欄（開始時間），之後每次送訊號前比對，**pid 被重用的行程絕不誤殺**。
2. **找子孫**：掃 `/proc/[0-9]*/stat` 的 ppid 鏈（App 看得到自己 uid 的行程）。node 是 proot 的子行程，工具開的 bash、python 是 node 的子孫。
3. **依序結束**：
   1. 對 node（proot 的直接子行程）送 **SIGTERM**（`android.os.Process.sendSignal`）。dsh 收到 SIGTERM 會優雅 dispose，關掉寫入把手，鎖乾淨地釋放。最多等 3 秒。
   2. 對 proot 送 **SIGQUIT**：proot 的處理器會 SIGKILL 所有 tracee。
   3. 對樹裡還活著的每個 pid（含 proot）送 **SIGKILL**。
   4. 輪詢 `/proc` 直到整棵樹消失，最多 5 秒。
4. **有任何一個沒死**：不准回報「已停止」。錯誤記錄寫 `controller: old dsh pid N still alive after stop`；之後的 `ensureRunning` 丟 `DshStillRunningException`，**絕不啟動第二個 dsh**。

### 2.2 啟動前清掃殘留（F2）

每次 `ensureRunning` 在 `launcher.start` 之前：

- 讀 `filesDir/dsh.pid`（上一次啟動寫下的 `pid starttime`），還活著且開始時間相符就用 2.1 結束它；
- 再掃 `/proc` 找本 uid、cmdline 含 `libproot.so` 或 `/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js`、且不是目前這一次的行程，一律結束；
- 這一步在畫面上是一個獨立階段「清掃殘留的 dsh」（§2.3）。

這樣任何一條停止路徑（回退、更新、背景寬限、心跳、服務 onDestroy、崩潰放棄、未來新增的路徑）漏掉的行程，下一次啟動都會被清掉。`ProotGuestCommand` 的逾時也改用同一套結束方式（F3）。

### 2.3 階段、秒數與「卡住了」的說法（F6–F8 ＋ G）

`DshController` 新增 `phase: StateFlow<DshPhase>`，每一步都記錄時間（`controller: stopped pid=… in 850 ms` 這類，**不含任何 token 或網址**）：

| 階段 | 畫面上的字（G：要詳細） | 正在等什麼（G） |
|---|---|---|
| `Stopping(pid)` | 停止舊的 dsh | 等 dsh 自己收尾（最多 3 秒） |
| `WaitingForOldExit` | 強制結束舊的 dsh | 等整個行程樹消失（最多 5 秒） |
| `SweepingLeftovers(n)` | 清掃殘留的 dsh（找到 n 個） | 等殘留行程結束 |
| `Launching` | 啟動 dsh | 等 dsh 印出啟動網址（最多 60 秒） |
| `HealthCheck` | 健康檢查 | 等 dsh 的網頁伺服器回應（最多 60 秒） |
| `Registering` | 登記工作區 | 等 dsh 回覆工作區登記（最多 15 秒） |
| `Ready` | （不顯示） | — |
| `Failed(step, failure)` | 哪一步失敗＋白話原因 | — |

- 設定頁「套用並重啟」與主畫面的狀態面板都顯示**目前階段＋這個階段已經幾秒**，取代沒有資訊的「處理中…」。
- 30 秒後加一句「比平常久」。
- 90 秒，或遇到 `DshStillRunningException`：出現「強制結束 dsh 並重啟」（清掃全部殘留，再啟動）。
- 那也失敗：出現「到系統設定強制停止」（開 `ACTION_APPLICATION_DETAILS_SETTINGS`），並說明「按『強制停止』後再打開 App，對話都還在」。
- 失敗原因的白話說法（G）：舊的 dsh 關不掉／dsh 沒有在 60 秒內啟動／dsh 啟動了但沒有回應／dsh 一啟動就結束了（結束碼 N）／還沒裝好執行環境／其他錯誤（看錯誤記錄）。
- 設定頁的「重新啟動」改成**真的**停止再啟動（F8）；狀態面板的「重試」在 dsh 還在但不能用時也一樣。
- 停止時，服務的 `WorkTracker` 立刻與舊 dsh 脫鉤，放掉 `"work"`、收起「正在工作」通知（F10）。

**planner's call：** 第一版（Task 1，已在 `s2-killfix` 分支實作中）完成 F1–F10 與上表的階段、秒數、強制鈕；「正在等什麼」那一欄與逐步的白話失敗原因由後面的 Task 12 補齊（Task 1 已在做，不擴大它的範圍）。

### 2.4 `session/writer-held` 只說一次（F9）

- 錯誤碼 `session/writer-held` 的說法：「這個對話還被舊的 dsh 佔著」，附「強制重啟 dsh」按鈕（走 2.3 的強制路徑）。它**不**列入 `DshErrors` 的永久錯誤（重啟就能好）。
- 同一個送出被拒，只在對話頁的錯誤列出現一次；工具列（dock）不再重複說 `dsh 沒有收下這則訊息：…`。dock 只說它自己才知道的錯（建立對話失敗、附件讀取／上傳失敗、記憶體不足）。

---

## 3. B：對話框的控制

位置：對話頁標頭的模型晶片旁，排成一列三個晶片：**模型**、**思考強度**、**審查**。

### 3.1 思考強度

- 資料：dsh 的 `session/modelCatalog` → `groups[].models[].reasoning.efforts[{id,name}]`、`defaultEffort`。這就是 pi-ai 與 DeepSeek 轉接器各自宣告的等級。**planner's call：以 dsh 執行中回報的清單為準**（每次連線後讀一次、快取在 `ShellSession`）；讀不到時用內建備援表：DeepSeek `off/low/high/max`；Claude Sonnet 5.5、Opus 5.5 `low/medium/high/xhigh/max`；Haiku 5.5 另有 `off`。
- 中文名：off 關閉、minimal 最低、low 低、medium 中、high 高、xhigh 很高、max 最高。
- 設定方式：`session/selectModel {request:{sessionId, provider, model, reasoningEffort}}`，與模型晶片一樣「下一輪生效」；晶片立刻顯示所選，dsh 的 `modelSelection.next.reasoningEffort` 到了就以它為準，被拒就退回。
- 換模型時，若目前強度不在新模型的清單內，改用新模型的預設（不送 `reasoningEffort`）。
- 線上格式（e2e 用來驗證）：DeepSeek 轉接器把 `off` 送成 `thinking:{type:"disabled"}`，其他送成 `thinking:{type:"enabled"}` ＋ `output_config:{effort}`。

### 3.2 審查幾次（N = 0…3）——選擇「提示指令」而不是 dshm 插件工具

**planner's call：用提示指令。**

| | 提示指令（採用） | dshm 插件工具 |
|---|---|---|
| 改 `plugins/` | 不用 | 要 → 每台手機重裝 130 MB rootfs |
| 審查員 | dsh 內建的 `subagent` 工具（已存在、已受「同時幾個／深度」設定約束） | 插件要自己呼叫 dsh 的子代理人服務，碰到未公開的內部 API |
| 進度 | 審查員的 `description` 固定為「審查 k/N」，App 從子會話的 `subagent.label` 讀到 | 插件要另外送事件給 App |
| 可靠度 | 靠模型照做；不照做時就是一般回覆（不會卡住） | 較確定，但 dsh 改版時最容易壞 |

做法：

- 每個對話一個設定（App 本地 `SharedPreferences`，預設 0）。N>0 時，送出的提示在使用者文字後面**多一段文字**（`PromptPart.Text`），用 `<dshm-review rounds="N">…</dshm-review>` 包住。內容（逐字，計畫 Task 3 固定）要求總指揮：先擬好完整回答但**不要**直接回覆；用 `subagent` 工具派審查員，`description` 必須是「審查 k/N」，`prompt` 裡放使用者的問題與完整草稿，請它只逐點列出錯誤、遺漏、不清楚之處；等審查員完成的通知，依意見修改；重複到「審查 N/N」；最後只回覆修改後的完整回答。
- `subagent` 工具是背景式的（呼叫後立刻返回，子代理人結束時 dsh 用 `subagent-settled` 訊息叫醒總指揮），所以這個流程本來就能跨好幾步完成；草稿留在工具呼叫的參數裡，總指揮修改時看得到。
- 顯示：
  - 使用者氣泡、對話清單的最後一句、標題都**去掉**這段指令（`ReviewPrompt.strip`）。
  - 執行中顯示「審查 k/N」：取這個對話的子會話中 label 符合 `^審查 ([1-3])/([1-3])$` 的最大 k。
  - 「代理人」深度設定是 0 時，審查晶片停用並說明「設定裡的代理人深度是 0，無法派審查員」。
  - 選單上註明「每多審查一次，費用約多一倍」。
- 模型不照做：沒有子代理人出現，回覆照常顯示；本輪結束時進度消失，不會卡住。

---

## 4. C：數據從哪裡來

**原則：錢一律由 App 的 `PriceTable` 算；dsh 不提供金額。** 各數字的來源：

| 顯示 | 位置 | 來源 |
|---|---|---|
| 每秒 token | 每則回覆下方 | 該輪各步 `assistant/message.usage.outputTokens` ÷ 解碼時間；解碼時間＝每步 durable `stream` 陣列裡第一個與最後一個 chunk 的時間差（`chunk.time`、`*-chunks.time0+Σdt`） |
| 首字時間 | 每則回覆下方 | 該輪第一個 `request/context` 事件的 `time` → 第一步 stream 第一個 chunk 的時間 |
| 輸入／輸出／快取 token | 每則回覆下方 | 該輪各步 `usage.inputTokens`、`outputTokens`、`cacheReadTokens`（`cacheWriteTokens` 有就加）的合計 |
| 花費 US$ | 每則回覆下方 | 每步依該步的 `request/context {provider, model}` 用 `PriceTable.costUsd` 計算後加總 |
| 回覆時間 | 每則回覆下方 | `turn/end.time − turn/start.time` |
| 上下文填滿條 | 輸入列上方 | `session/control` 投影 `contextPressure {pressureTokens, contextWindow}`；`contextWindow` 為 0 或缺少時顯示「—」 |
| 本對話合計 token／花費 | 輸入列上方 | 本對話與其所有子孫會話的 `tokenUsage` 投影合計；花費以各會話 `modelSelection.lastUsed` 的模型計價（標「約」，見 §9-6） |
| 各模型今日／本月花費、快取命中率、平均每秒 token、工具呼叫次數 | 總覽 | App 閘道每一次模型呼叫的實際用量（`CostMeter` 擴充成按模型記帳，另記 `tool_use` 區塊數與 SSE 解碼時間） |

- 每則回覆的數字掛在**一輪的最後一則文字回覆**下方（一輪可能有好幾步工具呼叫；宇哥看到的「一則回覆」就是一輪）。缺資料的欄位顯示「—」，不顯示 0、不除以 0。
- 填滿條顏色（planner's call）：<70% 水藍、70–90% 粉紅、≥90% 玫瑰褐＋「快滿了」。
- `PriceTable` 修正：Sonnet 5.5 快取讀取改為 US$0.20/M。逐一核對官方價目（輸入／輸出／快取讀／快取寫，每百萬）：

| 模型 | 輸入 | 輸出 | 快取讀 | 快取寫 |
|---|---|---|---|---|
| Opus 5.5 | 4 | 20 | 0.20 | 5.00 |
| Sonnet 5.5 | 2 | 10 | **0.20**（原 0.10，錯） | 2.50 |
| Haiku 5.5（≤100K 提示） | 0.10 | 0.50 | 0.01 | 0.125 |
| Haiku 5.5（>100K 提示） | 0.50 | 2.50 | 0.05 | 0.625 |
| Fable 5.1 | 10 | 50 | 0.25 | 12.50 |

  只有 Sonnet 5.5 的快取讀取是錯的，其餘與官方一致（快取寫為 5 分鐘快取，1.25 倍輸入價）。

---

## 5. D：書架、角色設定卡、閱讀模式

### 5.1 資料模型與在工作區的位置（planner's call）

全部是工作區裡的一般檔案，「匯出工作區」自然帶走；不用資料庫。

```
/root/workspace/novels/
  cards/<cardId>.md              角色設定卡：第一行「# 名稱」，其餘是設定內文（Markdown）
  books/<bookId>/book.json       書的索引（App 是唯一寫入者）
  books/<bookId>/chapters/<seq>.md   每一章的正文（以對話訊息的 seq 命名，不因重編號而改名）
  books/<bookId>/reading.json    閱讀狀態（讀到哪、字級、夜間），與 book.json 分開，常寫也不碰索引
```

`book.json`：`{version:1, id, title, sessionId, cardId, model, createdAt, chapters:[{seq, title, chars}], excludedSeqs:[…], manualSeqs:[…], lastSyncedSeq}`；章號由 `chapters` 依 seq 排序後的順序決定。`reading.json`：`{seq, anchorChar, fontSp, night, updatedAt}`。

- 寫檔一律先寫暫存檔再原子改名；壞掉的 JSON 不當機：那本書不出現在書架，錯誤記錄只寫 `library: unreadable book <id>`（不寫內容）。
- id：書 `bk-<yyyyMMdd>-<6 位 16 進位>`，卡 `card-<6 位 16 進位>`。

### 5.2 角色設定卡

- 書架上方「角色設定」→ 卡片清單、新增、編輯、刪除。
- 「請 AI 整理」（planner's call）：開一個新對話，請模型把這張卡整理成角色／世界觀／文風分段，並用 `write` 工具寫回 `/root/workspace/novels/cards/<cardId>.md`（第一行保留「# 名稱」）；回到書架時重新讀卡。這樣不必另做「等回覆再套用」的流程，而且整理過程宇哥在對話裡看得到。

### 5.3 開新書（一本書＝一個對話）

1. 「＋新書」：書名、選一張卡或當場新建、寫作模型（預設「寫小說用的模型」`AgentSettingsStore.novelModel`）、第一句指示（預設「請寫第一章。」）。
2. App 依序：`session/create` → `session/selectModel`（寫作模型）→ 寫 `book.json` → 送出開頭訊息 → 打開這個對話，讓宇哥看著它寫。
3. 開頭訊息（第一則使用者訊息）＝角色設定卡＋寫作規則＋第一句指示，用 `<dshm-book title="…">…</dshm-book>` 包住設定部分，要求每章只回覆正文、以「# 第 N 章 章名」開頭。

### 5.4 章節

- **自動收章（planner's call）：** 在書的對話裡，「下一則使用者訊息之前的最後一則助理文字」且長度 ≥ 200 字，就是一章（有審查時，自然是修改後的版本）。
- 同步時機：
  - 書的對話從「執行中」變成「閒置」時（`SessionTree` 的狀態轉換），用 `session/page` 往回讀到 `lastSyncedSeq` 為止；
  - 打開書架或閱讀模式時也補一次。
- 章名：正文第一行若是 Markdown 標題就用它，否則「第 N 章」。
- 宇哥可以在書的對話裡把某則回覆標成「不是章節」（記入 `excludedSeqs`、刪掉該章檔），或把任何回覆「加入為章節」（`manualSeqs`）。
- 「加入書架」：任何對話的助理回覆 → 選一本書（或新書）→ 該回覆成為那本書的一章（記入 `manualSeqs`，正文寫成章檔；那本書的對話不變）。

### 5.5 閱讀模式

- 蓋在殼上的全螢幕層（planner's call：不另開 Activity。另開 Activity 會讓主畫面 onStop，放掉 dsh 的持有、10 分鐘後停 dsh，「下一章」就要等重啟）。
- 打開時殼的背景動畫停止、頁面不組合、工具列隱藏；閱讀層只有純色底與文字，沒有玻璃模糊、沒有光暈與網格（§5.9「閱讀模式背景效果降到最低」）。
- 內容：一章一個 Markdown 區塊清單，每段一個 lazy item（10 萬字一章也不卡）。
- 控制：字級 14–26sp（步進 2）、夜間、上一章／下一章、目錄。
- 讀到幾 %：整本書（之前各章字數＋本章首個可見段落之前的字數）÷ 全書字數；底部同時顯示本章 %。
- 記得位置：`reading.json` 存章（seq）＋首個可見段落的起始字元位置，停止捲動 1 秒後寫入（去抖動）。
- 最後一章時「下一章」變成「請 AI 寫下一章」：在這本書的對話送「請接著寫下一章。」，閱讀層顯示 §6.4 的狀態列（例如「撰寫中 · 12 秒」）；新章同步進來後自動跳過去。
- 書的對話被刪除：章節仍可讀，「請 AI 寫下一章」停用並說明「這本書的對話已刪除」。
- 日間配色：底 `#FFFFFF` α.92 疊在 `#FEDFE1` 上，字 `#5E4445`。
- 夜間配色（planner's call）：底 `#5E4445`、字 `#FEDFE1`、次要字 `#D7C4BB`（樣品的 `#fff6f8`／`#2a1f21` 不在色票內，不用）。
- 對話頁長回覆（≥ 1500 字）旁有「📖 閱讀模式」，用同一個閱讀層讀這一則。

### 5.6 書架頁

- 兩欄書格：封面＋「第 N 章 · 讀到 X%」＋進度條。
- 封面＝霧城色漸層＋書名＋細橫線紋。**planner's call：** 由 bookId 雜湊在 4 組色票漸層（粉紅→奶茶、水藍→淡櫻粉、奶茶→白、玫瑰褐→粉紅）中挑一組，完全用 Compose 繪製，不用任何圖片素材。
- 點書 → 閱讀模式（回到上次位置）；長按 → 選單：打開對話、重新命名、刪除（刪除只刪書架資料，不刪對話）。

---

## 6. E＋G：代理人監看牆（科幻 FUI）

宇哥更正：**不是像素辦公室**。要的是「一眼看到每個代理人在做什麼」的想法，用他的參考圖（`agents-fui-reference.jpg`，類似《遺落戰境》的電影介面）的科幻 FUI 畫法：多邊形雷達與發光標記、帶軌道點的圓環量表、成排的小標籤數字讀數、堆疊長條、細線幾何、密集的表格數字。配色只用霧城色。**沒有任何從參考圖、電影或像素代理人專案複製的美術素材**，全部用 Compose Canvas 以線、多邊形、圓弧畫出。

### 6.1 版面（412 dp 直向，由上而下）

1. **任務控制面板（MISSION CONTROL）**
   - 標頭：「代理人」＋目前任務（根對話標題）；同時有多個根對話在跑時，用一排晶片切換。**planner's call：** 預設根＝目前打開的對話（它有子孫時），否則最近在跑的根。
   - **多邊形雷達**（最大 260 dp 寬，置中）：
     - 三層六邊形外框（深度 1、2、3 的軌道），外圈 120 刻度細線，十字細線；
     - 總指揮在中心：粉紅菱形發光標記（參考圖的那顆）；
     - 子代理人／隊友依深度落在對應的六邊形上，角度依建立順序平均分佈（穩定，不跳動）；父→子連線，執行中實線、其他虛線；
     - 狀態色：水藍＝執行、粉紅＝等待、奶茶＝完成、深玫瑰褐 `#5E4445`＋閃爍＝出錯；
     - 掃描扇形：全開／平衡時 5.5 秒轉一圈，省電與減少動態時靜止。
   - **讀數表**（雷達下方兩欄，Orbitron 小標籤＋數字，見 §9-1）：RUNNING 3/16、WAITING 1、DONE 5、ERROR 0、TOKENS 12.4K、COST US$0.042、CONTEXT 38%（總指揮）、TOOLS 27。
   - **並行分段條**：16 格，粉紅＝在跑、水藍＝等待、空＝可用（上限＝設定的同時代理人數）。
2. **監看牆（LIVE）**：每個代理人一塊「小螢幕」，單欄、全寬（planner's call：412 dp 放兩欄時，G 要求的細節塞不下）。每塊螢幕（G 的每一項都在上面）：
   - 第 1 行：狀態點、名稱（label）、狀態字，等待時附原因（等你核准／等你回答／等子代理人／限流中）；右側為模型與思考強度晶片（例：SONNET · 高）。
   - 第 2 行：目前動作的 HUD 圖示與標籤，加工具參數摘要，例：搜尋「台北天氣」、讀取 notes/a.md；右側為本步已經幾秒。動作有九種：搜尋、讀取、撰寫、執行（planner's call：bash／python 加這一種）、派出、詢問、錯誤、思考、完成。
   - 第 3 行：正在產生的最後一行文字（最多 2 行，粉紅游標）。
   - 第 4 區，左半是上下文填滿的**圓環量表**：外圈刻度、數值弧、執行中一顆軌道點繞行。右半是讀數格：
     - STEP 7 / TURN 2
     - TIME 1:23（本步 0:12）
     - IN 12.3K · OUT 1.2K · CACHE 8.0K
     - US$0.012
     - QUEUE 1
     - DEPTH 2 · 來自「父名稱」
   - 第 5 區：token **堆疊長條**（輸入粉紅、輸出水藍、快取奶茶）。
   - 第 6 區：最近 8 個動作的小時間軸（圖示刻點＋相對時間）。
   - 出錯時，最下方顯示錯誤文字（錯誤紅，最多 3 行）。
   - 螢幕底是玻璃填色（不做背景模糊）＋細掃描線紋；完成或出錯的螢幕透明度降到 .75。
3. **Agent Teams 面板**（dsh 有 `agentTeam` 時才出現）：隊友名單（名稱、角色、phase：準備中／運作中／失敗＋錯誤），以及任務板（待辦／進行中／完成三組，每項有主題、負責人、被擋住的標記）。
4. **最近完成**：最多 20 筆（見 §6.3）。

右上角有分段控制「牆／清單」。清單模式＝第一期的簡易清單（加上狀態、動作、花費一行）。**TalkBack 開著時一律用清單**，不畫雷達動畫。

### 6.2 每個讀數由什麼驅動

| 讀數 | 來源 |
|---|---|
| 存在、父子關係、深度 | `SessionTree`（`session/list`＋`$events api-session/added|removed`），深度＝沿 parentId 往上數 |
| 執行／閒置 | `$events api-session/status` |
| 等待：核准／回答 | `DshEvents.prompts` 裡 `agentId` 等於該代理人的待答卡 |
| 等待：子代理人 | 自己沒有在產生、但有執行中的子會話 |
| 等待：限流 | App 閘道的 RateGuard 在 60 秒內對該代理人模型的供應商觸發過（dsh 不提供，見 §9-10） |
| 出錯 | `$events api-session/error(sessionId, message)`；被跟隨時的 `turn/end.reason` 非 completed／aborted；隊友 `phase:'failed'`＋`error` |
| 完成 | 執行中→閒置後未出錯；或 dsh 移除（`api-session/removed`）時最後狀態為閒置 |
| 目前動作、工具摘要、本步秒數、最後一行、時間軸 | 該代理人的 `session/follow`（子代理人用 `address:{kind:'subagent', parentSessionId, childSessionId, mode:'continuable'}`）的即時串流與 durable 事件，**只對可見的螢幕跟隨**（§6.4） |
| 沒被跟隨時的最後一行 | 投影 `turnOutline` 最後一筆的 response（截斷）；沒有就「—」 |
| STEP／TURN | 投影 `sessionStats {turns, steps}` |
| TIME（從開始） | 投影 `subagentTiming.active.since`；總指揮用本輪 `turn/start` |
| IN／OUT／CACHE | 投影 `tokenUsage {uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}` |
| 花費 | 上列 token × `PriceTable`（該會話 `modelSelection.lastUsed`） |
| 模型＋思考強度 | 投影 `modelSelection.lastUsed {provider, model, reasoningEffort}` |
| 上下文填滿 | 投影 `contextPressure` |
| QUEUE | 投影 `inbox` 的 `next-turn`＋`next-step` 筆數 |
| 隊友與任務板 | 總指揮的投影 `agentTeam {members, tasks, failure}` |
| 任務總花費／token | 根與所有子孫的合計 |

### 6.3 已完成的代理人不會消失（F）

dsh 會在子代理人結束後把它移除，因此新增 `FinishedAgents`：

- 收到 `api-session/removed`（或狀態轉為閒置）時，把該代理人最後一份完整快照（含時間軸）存起來；
- 雷達與牆上繼續以「完成」顯示 10 分鐘，之後移到「最近完成」區；
- 最多 20 筆，存在記憶體（`ShellSession`），dsh 重啟就清空（planner's call：需求說「短暫的 App 內歷史」）；
- 點一筆已移除的代理人，看的是保留下來的時間軸與數字，看不到完整步驟（dsh 已丟掉，見 §9-8）。

### 6.4 點開一個代理人

詳情層（蓋在牆上的玻璃卡，可捲動）：

- 上方：螢幕的完整讀數；
- 中間：完整步驟，用對話頁同一套訊息元件唯讀呈現該代理人的 follow；
- 底部兩個動作：
  - 「傳話」：輸入框＋送出，走 `subagents/prompt {requestId, parentSessionId, childSessionId, mode:'continuable', delivery:'queue', content}`；
  - 「停止」：走 `subagents/interruptByParent`。

### 6.5 效能規則

- 只在代理人分頁可見、Activity 至少 STARTED 時才有任何動畫；背景暫停（沿用 `LocalPageActive`）。
- 省電＝完全靜態（不掃描、不繞行、不閃爍；出錯改為實心深玫瑰褐＋「!」）；系統「移除動畫」同省電。
- **同時跟隨上限 6**：只跟隨 lazy 清單中可見的螢幕（加上打開中的詳情），捲出畫面就取消。
- 牆的狀態最多每 250 ms 發佈一次（取樣）；每塊螢幕的文字只在內容變了才重組。
- 雷達與量表用 `drawWithCache`，整個牆只有一個動畫時鐘。

### 6.6 對話頁的「它現在在做什麼」（G）

輸入列上方一條狀態列，不只是轉圈，內容依優先順序：

1. 等你核准：「工具名」
2. 等你回答問題
3. 審查 k/N
4. 呼叫工具：搜尋「…」／讀取 …／執行 …（參數摘要）
5. 思考中
6. 撰寫中
7. 等模型回應

每一項都帶「· N 秒」（這個狀態已經多久）；有排隊的訊息時加「· 排隊 N 則」。閒置時不顯示。

---

## 7. F：小螢幕、檔案分享、其他延後項

### 7.1 小螢幕（約 640 dp 高、鍵盤打開）

對話清單的改名／刪除卡，以及 PromptSheet 類的卡：

- 卡片放在鍵盤上方（`imePadding`）；內容可捲動；按鈕列固定在卡片底部，永遠完整可見。
- 鍵盤開著時，點遮罩**只收鍵盤**，不取消卡片；鍵盤收起後再點遮罩才取消。
- 遮罩對 TalkBack 隱藏（第一期延後的小項）。
- 清單列在 640 dp 也保持 48 dp 觸控高度、不擠。

### 7.2 工作區檔案分享（兩個都做，planner's call）

- **自動偵測**：工具卡（不只 write／edit，也包含 bash、python 等）從參數與結果文字中找出工作區路徑：
  - 絕對路徑：`/root/workspace/…`；
  - 相對路徑：bash／python 指令裡帶副檔名的 token，因為工作目錄就是工作區。
  - 每張卡最多 5 個，在背景執行緒確認檔案真的存在（`WorkspacePaths.resolveInside`）才顯示「分享 檔名」晶片。
- **工作區檔案瀏覽器**：設定頁「匯出」區的「工作區檔案」→ 全螢幕瀏覽：資料夾、檔名、大小、時間，點檔案＝系統分享（沿用 `Exporter.shareWorkspaceFile`），只能瀏覽與分享、不能刪改。符號連結不跟隨出工作區。

### 7.3 延後小項的分流

從 `stage1-progress.md` 的 minor (deferred) 與 Rulings 中挑便宜的放進本版；其餘維持延後，理由是風險大或效益低。

| 小項 | 決定 |
|---|---|
| 同一個拒絕在 dock＋對話各顯示一次（T9） | 收（§2.4，Task 1） |
| 已完成的子代理人從代理人頁消失 | 收（§6.3，Task 7） |
| 分享 bash／python 產生的檔案（B2-M10） | 收（§7.2，Task 13） |
| 小螢幕卡片擠、點到遮罩（Ruling） | 收（§7.1，Task 14） |
| PromptSheet 遮罩可聚焦卻沒有標籤（T10） | 收（Task 14） |
| `ReminderLog` revision 在鎖外更新、欄位長度沒設上限（T12） | 收（Task 12） |
| 總覽第一幀閃一下空白頁（T12） | 收（Task 12） |
| 點已選的效果格會重設降級上限（T13） | 收（Task 12） |
| 主模型選項顯示原始 id（T13） | 收（Task 12） |
| `current()` 拋例外時更新的訊息不清楚（T13） | 收（Task 12） |
| 遠端 60 秒沒連上就停在錯誤、直到按重試（T4） | 收：Task 12 改為顯示是哪一步卡住，並自動重試一次 |
| `DshRemote.upload` 的 KDoc 誇大記憶體效益（T9） | 收（Task 14） |
| `visibleRect`／`snapOut`／`wantsBlur` 每次繪製都配置物件（Lane Y） | 收（Task 14） |
| 簡易清單沒有「目前沒有在跑」（T11） | 牆取代，Task 8 內含 |
| mux 沒有用戶端 ping（T2） | 延後（沒有觀察到卡住的 socket） |
| 跨頁的工具配對停在 RUNNING（T5） | 延後 |
| 停止失敗後自然完成不發完成通知（T16） | 延後 |
| 改名時 dsh 自動標題蓋掉（Ruling） | 延後，列入發佈說明 |
| 圖示背景 `#f06595` 不在色票內（Ruling） | 維持，再列給宇哥 |
| 降級上限跨重啟保存（B1-M12） | 延後（規格說每個程序） |
| 4 層模糊上限換頁時閃一下（Ruling） | 等真機回報 |
| 其他測試與文件小項（t18 正向對照等） | e2e 任務順手處理 |

---

## 8. G：總覽的 dsh 狀態區塊

總覽新增兩塊面板（Task 12）：

- **「用量」USAGE**：
  - 各模型今日與本月花費（每列：模型名、今日 US$、本月 US$）；
  - 三個讀數：快取命中（今日，`cacheRead ÷ (input＋cacheRead＋cacheWrite)`）、平均每秒 token（今日，輸出 ÷ 解碼秒數）、工具呼叫（今日）。
- **「DSH 狀態」STATUS**：
  - 狀態：執行中（有工作）／待命（沒有工作）／停止中／啟動中（含階段）／未啟動，加上已運作多久（dsh 就緒起算）；
  - 埠健康：dsh 埠與閘道埠各一個 ✓／✕。**planner's call：** 只做 127.0.0.1 的 TCP 連線探測，500 ms 逾時，總覽可見時每 30 秒一次；不送 HTTP、不帶 token。
  - 在跑的代理人數；
  - 最近一次錯誤：時間＋類別（控制器／dsh 輸出／連線／服務）。只有 App 自己寫的 `controller:` 行會顯示原文；dsh 的輸出行可能含任何東西，所以只顯示類別與時間。

---

## 9. 做不到或衝突的需求，以及改用的做法

1. **字體（E vs 上位規格）。** E 說「Orbitron 用於標籤與數字、Chakra Petch 用於文字」；上位規格與第一期是「Orbitron 英文標題、Chakra Petch 數字與英文小標籤、中文系統字」。兩者都沒有中文字形。決定：
   - 監看牆的 FUI 讀數標籤（RUNNING、TOKENS…）與大數字用 Orbitron，照宇哥的參考；
   - 會每秒跳動的小數字（秒數、token）用 Chakra Petch 等寬數字，否則寬度會抖；
   - 中文一律系統字；
   - 其他頁維持第一期的規則。
2. **思考強度的等級。** B 寫 `off | low | high | max`，那只是 DeepSeek 的等級。Claude 5.5 系列是 `low/medium/high/xhigh/max`（Sonnet、Opus 沒有 `off`，Haiku 有）。決定：每個模型顯示 dsh 回報的那一組。另外，dsh 的 `selectModel` 會把選擇存成新對話的預設（dsh 自己的行為），所以本次 dsh 執行期間新開的對話會沿用上一次的選擇；App 每次啟動 dsh 時寫入的設定會把預設改回來。
3. **審查「另派審查員」。** 用提示指令驅動，不能保證模型照做；宇哥看到的是修改後的最終回答，看不到草稿（草稿在工具卡的參數裡）。代理人深度設 0 時無法審查（晶片停用並說明）。
4. **價格「全部重新核對」。** 依官方價目只有 Sonnet 5.5 快取讀取錯；Fable 5.1 雖有定價，但 App 的模型清單沒有它，本版不加入（需求沒要求）。
5. **F 提到「像素辦公室」。** 與 E 的更正衝突，以 E 為準：就是監看牆。
6. **對話合計花費。** dsh 只給每個會話的 token 總數，不分模型。對話中途換過模型時，合計以最後使用的模型計價，畫面標「約」；總覽的花費仍以閘道實際記帳為準。
7. **子代理人的即時跟隨。** `session/follow` 的 subagent 位址在偵察時只讀過原始碼、沒有實測。決定：先用 subagent 位址；失敗就改用 session 位址試一次；再失敗，該螢幕只用投影資料，並顯示「無即時文字」。e2e 會驗證這一點。
8. **點開已完成的代理人看「完整步驟」。** dsh 移除後就讀不到了，只能看保留的時間軸。
9. **超過 6 個代理人時的「即時最後一行」。** 受跟隨上限所限，只有可見的螢幕是即時的；其他螢幕顯示投影的最後一筆。
10. **等待原因「限流」。** dsh 不提供，改由 App 閘道的 RateGuard 推得（60 秒內觸發過，且該代理人用同一供應商）。
11. **小螢幕的 e2e。** 依經驗，CI 模擬器 640 dp 上一律先明確收鍵盤再點按鈕。小螢幕測試在鍵盤開著時只檢查幾何（按鈕在鍵盤上方、完整可見），收鍵盤之後才點。
12. **閱讀夜間配色。** 樣品顏色不在色票內，改用色票（§5.5）。

---

## 10. 給實作者的共通規則（摘要；完整版在計畫的 Global Constraints）

- 每個 UI 任務都要有一個 412 dp 的 Robolectric NATIVE 繪圖截圖檢查：`decorView.draw(Canvas(bitmap))`。不要用 `captureToImage`，它會卡住。
- e2e 以省電（SAVER）執行；在 640 dp 的 CI 模擬器上，每次打字後都明確收起鍵盤。
- 新的介面文字放在各自的 `res/values/strings_<功能>.xml`，`Tags` 依功能分段新增，降低並行分支的合併衝突。
- 不新增第三方依賴。
