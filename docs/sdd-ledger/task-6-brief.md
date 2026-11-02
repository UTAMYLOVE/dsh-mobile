### Task 6：記憶插件（含憑證寫入守門）

計畫原文的介面，**加上帳本 Ruling 與 `docs/dsh-findings.md` §Plugins／§Memory／§Server 的結果**。本文與計畫原文衝突時以本文為準。

**Files:**
- Delete: `plugins/hello/`（Task 0 偵察用的臨時插件）
- Create: `plugins/packages/memory/package.json`（名稱 `@dshm/memory`，`private`，`type: module`，依賴 `@dshm/common: workspace:*`、`proper-lockfile`）、`src/store.ts`、`src/dream.ts`、`src/guard.ts`、`src/index.ts`
- Test: `plugins/packages/memory/test/store.test.ts`、`test/dream.test.ts`、`test/plugin.test.ts`
- Create: 插件建置：`plugins/package.json` 加 `"build"` script，用 esbuild（workspace 已允許 esbuild 建置）把 `packages/memory/src/index.ts` 打包成**單一 ESM 檔** `plugins/packages/memory/dist/index.js`（platform node、target node24、`@dshm/common` 與 `proper-lockfile` 內嵌，`node:*` 外部）。`dist/` 已被 git 忽略的話維持忽略；沒有就加進 `.gitignore`。
- Modify: `.github/workflows/ci.yml` 的 `rootfs` job：在 build-rootfs 之前裝 node 24 + pnpm 11.28.10、`pnpm -C plugins install --frozen-lockfile && pnpm -C plugins build`，把 `plugins/packages/memory/dist/index.js` 複製到 `out/staging/plugins/memory/index.js`，再以第三個參數 `out/staging` 呼叫 `build-rootfs.sh`（它會把 staging 內容放到 rootfs 的 `/opt/dshm/`）。`rootfs/smoke-test.sh` 增加檢查 `/opt/dshm/plugins/memory/index.js` 存在。
- Modify: `plugins/pnpm-lock.yaml`

**Consumes:** `@dshm/common` 的 `requireAuth(ctx, req, res): boolean`（Task 4，`plugins/packages/common/src/auth.ts`；先讀它）。

**dsh 插件 API（findings 已實測，照用）：**
- 插件模組匯出 `name`、`inject`、`apply(ctx, config)`；`ctx.tools.register({ name, description, parameters (JSON Schema), output: { schema, render }, execute(args, exec) })`（findings §Plugins「註冊工具」有完整範例）。
- 對話開始注入：`ctx.systemPrompt.context({ name: 'dshm-memory', order: 9000, interpolate: false, text: () => store.loadStartupContext() })`。
- HTTP 路由：`ctx.effect(() => ctx.webServer.register({ kind: 'exact', path, handler(req, res) }))`。**`webServer` 不放進 `inject`**（headless 組合沒有它，硬注入會卡 pending）：用 `ctx.get('webServer')` 判斷有才註冊。
- 憑證寫入守門（Ruling：key-save guard，原在 `plugins/hello/index.js`，**搬到本插件的 `src/guard.ts`**）：`ctx.on('connection/request', async (req, res, next) => …)`，路徑為 `/api/credentials/set`、`/api/credentials/unset`、`/api/account/startSignIn` 時回 403 JSON `{"ok":false,"error":"dshm: keys are managed by the app"}`，其餘 `return next()`。刪 hello 前先把它的寫法讀懂。
- 壓縮（Ruling）：沒有「壓縮前」鉤子。監聽 `ctx.on('session/event', (session, event) => …)`，`event.type === 'compaction/start'` 時，在今天的每日筆記附加一行 `- HH:mm (compaction) 對話已壓縮，重要事項請以 memory_write 保存`（**不呼叫模型**，最小可行；不阻擋壓縮）。這是 [未實測] API：handler 要 try/catch，任何錯誤只記 `ctx.logger.warn`，不能讓 dsh 崩。另外不需要做 `agent/created` 重注入（`systemPrompt.context` 每次組裝都會重讀）。
- 模型呼叫（dream 用）：findings 沒寫 `ctx.llm` 的介面。**請你自己查**：在 `$TMPDIR` 或 scratch 目錄 `npm install @deepseek-ai/dsh@0.2.1-rc.5`（registry.npmjs.org 可連；**不要裝進倉庫**），讀 `@deepseek-ai/dsh-llm` 等套件的 `.d.ts`／README，找出伺服器端插件如何用「目前設定的預設模型」（row `agent-default-model`）做一次非串流的單輪文字補全。把找到的用法、檔案路徑寫進報告。若真的找不到可用 API，回報 NEEDS_CONTEXT，**不要自己發明 HTTP 呼叫**。

**Interfaces（Produces）:**
- `class MemoryStore(root: string, now: () => Date = () => new Date())`
  - `loadStartupContext(): string`：`MEMORY.md` + `USER.md` + 今天與昨天的 `memory/YYYY-MM-DD.md`（缺的略過；全缺回空字串），以 `## <相對路徑>` 分段。日期用**本機時區**（App 會設 `TZ=Asia/Taipei`）。
  - `search(query: string, limit = 10): { path: string; line: number; text: string }[]`：空白分詞、全部詞都要命中、不分大小寫；範圍 `MEMORY.md`、`USER.md`、`memory/*.md`；新檔優先（每日筆記依檔名日期新到舊，`MEMORY.md`／`USER.md` 排在每日筆記之後）；`line` 從 1 起算。空查詢回空陣列。
  - `get(rel: string, from?: number, lines?: number): string`（`from` 從 1 起算）。
  - `append(target: 'daily' | 'long' | 'user', text: string): Promise<void>`（daily 寫今天的檔，每行前綴 `- HH:mm `；long → `MEMORY.md`；user → `USER.md`；檔案與 `memory/` 不存在就建立）。
  - `resolveInside(rel: string): string`：`../` 跳脫、絕對路徑、經符號連結（含中間目錄）出界 → 丟 `MemoryPathError`。只允許 `.md` 檔。
  - 所有寫入經過同一把檔案鎖（`memory/.lock`，`proper-lockfile`，有重試），同一行程內也要序列化。
- `dream(store, llm: (prompt: string) => Promise<string>, statePath: string): Promise<{ promoted: string[]; reminders: { title: string; body: string }[] }>`：讀 `memory/.dream-state.json`（`{"lastProcessedDate":"YYYY-MM-DD"}`）之後、**不含今天**的每日筆記；沒有新筆記 → 不呼叫 llm、回空結果。要求模型只回 JSON `{"promote": string[], "remind": [{"title","body"}]}`（容許包在 ```json 區塊裡）；`promote` 非空時以 `### YYYY-MM-DD 整理`（處理到的最後一天）區塊附加到 `MEMORY.md`（經鎖）；更新 state 為處理到的最後一天。模型回非 JSON 或結構不符 → 不寫任何東西、不更新 state、丟 `DreamParseError`。
- dsh 掛載（`src/index.ts`）：`name = 'dshm-memory'`；工具 `memory_search({query, limit?})`、`memory_get({path, from?, lines?})`、`memory_write({target, text})`（`MemoryPathError` 轉成工具錯誤訊息，不丟出未處理例外）；對話開始注入；壓縮監聽；守門；路由（每個 handler 第一行 `if (!requireAuth(ctx, req, res)) return`）：
  - `GET /dshm/health/memory` → `{"ok":true}`
  - `POST /dshm/heartbeat` → 呼叫 `dream`，200 `{"promoted":[…],"reminders":[…]}`；`DreamParseError` → 502 `{"error":"dream_parse"}`；其他錯誤 → 500 `{"error":"dream_failed"}`（訊息不含路徑以外的內容）。同時只跑一個 dream（第二個並行請求 → 409 `{"error":"dream_busy"}`）。
- 工作區根目錄：`process.env.DSHM_WORKSPACE`（未設 → 插件載入時 `ctx.logger.warn` 並停用工具與路由，**不要**退回 cwd）。

- [ ] **Step 1: 寫失敗測試**
```ts
it('startup context has MEMORY, USER, today and yesterday only', ...)  // 前天的不在內
it('search finds all-term matches, newest first', ...)
it('get rejects ../ and absolute paths and outward symlinks', () => {
  expect(() => s.get('../secret')).toThrow(MemoryPathError)
  expect(() => s.get('/etc/passwd')).toThrow(MemoryPathError)
  expect(() => s.get('memory/link-out.md')).toThrow(MemoryPathError)   // 指向工作區外的符號連結
})
it('concurrent appends never lose lines', async () => { /* 50 個並行 append，最後 50 行都在 */ })
it('dream promotes into MEMORY.md and advances state', ...)
it('dream on non-JSON writes nothing and keeps state', ...)
it('dream with no new notes does not call llm', ...)
// plugin.test.ts：用假 ctx（記錄 register 的工具／路由／listener）＋真的 node:http 打路由
it('routes reject unauthenticated requests via requireAuth', ...)          // 假 connection 回 401 → 401，dream 沒被呼叫
it('heartbeat returns promoted and reminders', ...)
it('credential RPCs are refused with 403 and others pass through', ...)
it('compaction/start appends a daily note line and never throws', ...)
```
- [ ] **Step 2:** `pnpm -C plugins test` → FAIL。
- [ ] **Step 3:** 實作；`pnpm -C plugins build` 產出單一檔。
- [ ] **Step 4:** `pnpm -C plugins test`、`pnpm -C plugins typecheck` PASS。**實機驗證（Linux 本機，Node 24）**：用剛才裝在 scratch 的 dsh，以 `--patch` 載入 `dist/index.js`（patch 格式見 findings §Plugins「如何讓 dsh 載入本機路徑的插件」，路徑必須是絕對路徑），`DSHM_WORKSPACE` 指向一個暫存目錄、`DSH_HOME` 指向另一個暫存目錄、`DSH_TELEMETRY_MODE=DISABLED`，啟動 `node <scratch>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --patch <abs>/memory.cordis.yml --no-open --host 127.0.0.1 --port 0`，從 stdout 的 `dsh web:` 行取得網址：(a) 無 cookie `curl /dshm/health/memory` → 401；(b) 用 cookie jar GET launch URL 之後同一路徑 → `{"ok":true}`；(c) 帶 cookie POST `/api/credentials/set` → 403。不需要真的模型金鑰。把指令與輸出貼進報告。驗證完關掉 dsh、刪暫存目錄。
- [ ] **Step 5:** Commit `feat: openclaw-style memory plugin`。
