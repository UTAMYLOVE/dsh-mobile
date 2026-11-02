### Task 0: 摸清 dsh 內部（偵察，產出 findings）

**Files:**
- Create: `docs/dsh-findings.md`
- Create: `plugins/hello/`（臨時驗證插件，Task 6 開始前刪除）

**Interfaces:**
- Produces: `docs/dsh-findings.md`，必須回答以下每一節，後續任務以節名引用：
  - §Pin：選定的 `DSH_PINNED_VERSION`（在 Node 24 上 `dsh web` 正常、Agent Teams 與 Subagent 插件可用）。
  - §Plugins：本機插件的套件格式、如何讓 dsh 載入本機路徑的插件、註冊工具、對話開始時注入上下文、對話壓縮前的鉤子（有無）、註冊 HTTP 路由的 API。
  - §Config：設定檔位置與格式；模型提供者 base URL / 金鑰欄位；各角色模型；子代理人同時數、深度、團隊人數的設定鍵。
  - §Server：`dsh web` 開了哪些埠（HTTP、WebSocket、其他）；能否改埠；是否已有內建驗證。
  - §Memory：dsh 原生讀 `MEMORY.md` 的位置與時機。
  - §UI：網頁介面的 CSS 變數或主題機制（給粉紅配色用）；對話的 URL 格式（給通知深連結用）。
  - §Providers：官方是否有 Anthropic 模型接口插件；DeepSeek 目前的模型名稱；兩家用量欄位（含串流最後一塊）；官方價格（每百萬 token 的輸入/輸出美元）。
  - §Search：官方網頁搜尋插件需要哪個服務、是否要金鑰、是否需要無頭瀏覽器。

- [ ] **Step 1:** 在電腦上（Node 24）`npx @deepseek-ai/dsh@<候選版> web`，讀官方倉庫 README、docs、插件原始碼，逐節填 `docs/dsh-findings.md`。
- [ ] **Step 2:** 寫 `plugins/hello`：註冊一個工具 `hello()` 回傳 `"hi"` 和一條 HTTP 路由 `GET /dshm/health/hello` 回 `{"ok":true}`，照 §Plugins 的方式載入。
- [ ] **Step 3:** 驗證：`curl http://127.0.0.1:3080/dshm/health/hello` 回 `{"ok":true}`；在介面叫代理人呼叫 `hello` 得到 `hi`。
- [ ] **Step 4:** 若 §Plugins 顯示做不到「HTTP 路由」或「對話開始注入」，在 findings 寫明替代做法，並回報主腦（可能要改規格）再繼續。
- [ ] **Step 5:** Commit `docs: dsh internals findings`。

---

