### Task 7：通知插件

**Files:**
- Create: `plugins/packages/notify/package.json`（名稱 `@dshm/notify`，`private`，`type: module`，依賴 `@dshm/common: workspace:*`）、`src/send.ts`、`src/index.ts`
- Test: `plugins/packages/notify/test/send.test.ts`、`test/plugin.test.ts`
- Modify: `plugins/package.json` 的 `build` script 也打包 notify → `plugins/packages/notify/dist/index.js`（同 Task 6 的 esbuild 設定，單一 ESM 檔）。
- Modify: `.github/workflows/ci.yml` rootfs job 的 staging 步驟，加複製到 `out/staging/plugins/notify/index.js`；`rootfs/smoke-test.sh` 加檢查 `/opt/dshm/plugins/notify/index.js`。
- Modify: `plugins/pnpm-lock.yaml`（若需要）

**Consumes:** 閘道 `POST {DSHM_GATEWAY}/notify`（Task 5；`DSHM_GATEWAY` 環境變數 = `http://127.0.0.1:<port>/<token>`，已含通行碼，**不可**寫進 log）；body JSON `{"title","body","sessionId"?}`，成功回 2xx `{"ok":true}`。`@dshm/common` 的 `requireAuth`（Task 4）。dsh 插件寫法照 `plugins/packages/memory/src/index.ts`（Task 6，先讀它：工具註冊、`webServer` 用 `ctx.get` 可選、路由第一行 `requireAuth`）。**注意（Task 6 實測）**：`webServer` 會比插件晚掛上，`apply` 當下 `ctx.get('webServer')` 是 undefined；要用 Task 6 的做法 `ctx.inject(['webServer'], (ctx) => …)` 延後註冊路由，否則路由 404。

**Interfaces（Produces）:**
- `sendNotify(gateway: string, req: { title: string; body: string; sessionId?: string }, fetchImpl = fetch): Promise<void>`：標題截到 60 個字元、內文截到 500 個字元（以 Unicode code point 計，不切斷代理對；被截時尾端加 `…` 並計入上限）；POST JSON、`content-type: application/json`；非 2xx 丟 `NotifyError`（訊息含狀態碼，**不含** gateway URL）；網路錯誤同樣包成 `NotifyError`；10 秒逾時。
- dsh 掛載（`src/index.ts`）：`name = 'dshm-notify'`；工具 `notify_user({ title, body })`：說明寫清楚「傳一則手機通知給使用者，只在真正需要使用者注意時使用」；自動帶入目前對話 id（從工具 `execute(args, exec)` 的執行情境取得 session id — **請查** dsh 的工具執行情境型別：在 scratch 目錄 `npm install @deepseek-ai/dsh@0.2.1-rc.5`，讀 `@deepseek-ai/dsh-tools` 的 `.d.ts`；取不到就送 `sessionId` 省略，並在報告說明）；成功回 `"sent"`，失敗回可讀錯誤字串（不丟未處理例外）。`DSHM_GATEWAY` 未設 → 工具仍註冊但回 `"notifications unavailable"`、`ctx.logger.warn` 一次。路由 `GET /dshm/health/notify` → `{"ok":true}`（經 `requireAuth`）。

- [ ] **Step 1:** 測試：對假閘道（真的 `node:http` 伺服器）送出後收到正確 JSON 與路徑 `/notify`；閘道回 500 時丟 `NotifyError`；超長標題被截到 60 字（含 emoji 不被切壞）；內文截到 500；`notify_user` 帶入 session id；健康路由未驗證 → 401。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** `pnpm -C plugins test`、`pnpm -C plugins typecheck`、`pnpm -C plugins build` PASS。
- [ ] **Step 5:** Commit `feat: notify plugin`。
