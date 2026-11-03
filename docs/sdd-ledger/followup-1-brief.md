### 追加 1：真機回饋（工作區、隱藏重複設定、霧城風格、畫面卡頓）

第一版 v0.1.0+1 已在宇哥的 S25 Ultra（arm64、Android 17）上跑起來。他回報四件事，本任務一次處理。紅線不變：不改官方 dsh 原始碼（只用插件、設定、啟動參數）；權限仍是 9 個；金鑰不進 env／設定檔／記錄檔；測試失敗訊息不印 token。

**1. 工作區不能選**
dsh 的工作區清單存在 `$DSH_HOME/storages/workspace.json`；DSH Mobile 的工作區是固定的 `/root/workspace`（`AppPaths.GUEST_WORKSPACE`），但從沒登記進 dsh，而 dsh 介面的選資料夾功能在 WebView 裡不能用。
- 每次 dsh 啟動並通過健康檢查後，用 `DshClient` 經 RPC 確認 `/root/workspace` 已在清單中，沒有就 `workspace/create`（findings §Server：`{"request":{"path":"<絕對路徑>"}}`）。列出清單的方法名與回傳格式**先在 scratch dsh 實測**（`npm i @deepseek-ai/dsh@<rootfs/versions.env 的版號>` 到 scratchpad），把指令與輸出貼進報告。
- 失敗只寫 ErrorLog（不印 token），不影響啟動結果；不重複建立。
- 單元測試（假 dsh／MockWebServer）＋ e2e：新的 e2e 檢查啟動後清單含 `/root/workspace`。

**2. 隱藏 dsh 介面中由 App 管理的設定（宇哥選擇「藏起來」）**
dsh 的「子智能体」等設定頁顯示「已覆盖」與「本部署没有接受这些值」，因為 DSH Mobile 以 `--patch` 管理這些值；兩邊都能改讓人混亂。
- 用 `@dshm/ui` 經 `webserver/index-inject` 注入一段 `kind: 'script'`（findings §UI 列出可用 row 類型）：`MutationObserver` 找到由 App 管理的設定區塊並隱藏（`display:none`），找不到就什麼都不做（不得讓頁面出錯；任何例外吞掉）。
- 範圍：凡是 DSH Mobile 的 patch 會覆蓋的 dsh 設定（看 `DshPatch`／`AgentConfigWriter` 實際輸出的列：模型、子代理人深度與同時數、Agent Teams、OTel、session log、外掛清單上報、provider 的 baseUrl／金鑰），以及金鑰輸入（`credentials/set` 已被 403 擋）。在 scratch dsh 帶同樣的 patch 開 web 介面（可用 Playwright，Chromium 已預裝），確認這些區塊的穩定辨識方式（優先 `data-*`／`id`／路由，最後才用文字比對），把對照表寫進報告。
- 在被隱藏區塊的位置（或該設定頁頂端）放一行小字：「這些設定由 DSH Mobile 管理，請點右上角齒輪」。
- vitest：注入列存在；script 在缺少目標元素時不丟例外（jsdom）。e2e：開啟子智能体設定頁後「最大递归深度」欄位不可見（若 e2e 難以導航到該頁，改為在 e2e 中評估注入 script 對一段模擬 DOM 的效果，並說明）。

**3. 霧城（FOGCITY）風格套用到 dsh 介面與 App 自己的畫面（宇哥選擇 B）**
用 `@dshm/ui` 的 CSS（取代現在的 SAKURA_CSS）把 dsh 介面換成「粉色液態玻璃＋科幻」風，但**以效能優先**（宇哥回報畫面卡）：
- 配色只用：`#FEDFE1` 淡櫻粉（背景）、`#D7C4BB` 奶茶、`#DC9FB4` 粉紅（主色、發光）、`#FFFFFF` 白、`#A5DEE4` 水藍（第二重點）、`#9E7A7A` 玫瑰褐；主文字 `#5E4445`、次要文字 `#7D5F60`。**水藍只當填色／發光，不當文字色**（對比不足）。dark 模式：只把強調色換成 `#DC9FB4` 系，不改深色背景。
- 字體：英文標題用 Orbitron，數字與英文小標籤用 Chakra Petch（`font-variant-numeric: tabular-nums`），中文用系統字。兩個字型取 Latin 子集 woff2，以 data URI 內嵌在 CSS（不連外網）；授權 OFL，在 `plugins/packages/ui/README.md` 註明來源與授權。內嵌後的插件 bundle 不得超過 300 KB。
- 圓角：面板 22px、彈出卡片 30px、按鈕與輸入框膠囊形；不留直角。
- 玻璃：面板用半透明白色漸層＋白→粉→水藍的細漸層邊（mask）＋柔和陰影；**`backdrop-filter` 最多只用在輸入列與彈出層兩處**。背景用淡粉底＋兩三團**靜態**模糊光暈（不要動畫、不要流動網格）。
- 不加任何持續運行的動畫；只保留 ≤200ms 的 hover／按壓過場；`prefers-reduced-motion: reduce` 時全部關閉。
- 以對應 dsh 的 `--dsw-*` token（findings §UI）為主，少量選擇器為輔；文字色用上面兩個玫瑰褐。
- App 自己的 Compose 畫面（引導、設定、載入／錯誤畫面、齒輪按鈕）：`SakuraTheme` 改用同一組顏色（主色 `#DC9FB4`、背景 `#FEDFE1`、文字 `#5E4445`／`#7D5F60`），按鈕膠囊形。
- 驗收：e2e 與儀器測試原本檢查 `rgb(255, 245, 248)`，改成新背景 `rgb(254, 223, 225)`；vitest 改成檢查新配色、沒有 6 色＋文字兩色以外的十六進位色、沒有無限動畫、`backdrop-filter` 出現次數 ≤ 2。

**4. 畫面卡（宇哥：滑動、點按時畫面卡）**
真機無法量測；做低風險、可驗證的改善，並在報告列出假設：
- 主畫面現在在 WebView 上面蓋了一個**全螢幕**的 ComposeView（只為了放齒輪按鈕與載入／錯誤畫面）。Ready 時改成只佔齒輪按鈕大小（或 Ready 時移除全螢幕圖層、只留按鈕），避免每一幀多一層全螢幕合成。
- dsh（proot 及其子行程）用較低 CPU 優先權執行，讓介面不被搶：若 `/system/bin/nice` 存在，以 `nice -n 10` 包住 proot 指令（不存在就照舊）；單元測試檢查指令組成。
- WebView：`setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)`；確認硬體加速未被關閉。
- 記錄「從開 App 到 Ready」耗時（毫秒）到 ErrorLog（tag `perf`，不含 URL／token），方便下次回報。

**驗證與推送**：base 是 `ae06129`（main，v0.1.0+1）。准許推送到 `claude/dsh-mobile-cloud-handoff-vfhvdh`（只限此分支；不得 force push、不得開 PR、不得動其他分支、不得打 tag）。每次推送前本機跑完單元測試、`pnpm -C plugins test/typecheck/build`、`assembleDebug`、`assembleDebugAndroidTest`；CI（含 e2e）在最後一個程式 commit 上全綠。版本號維持（發佈由宇哥打 tag）。報告寫到 `docs/sdd-ledger/followup-1-report.md`，最後一個 commit 推報告。
