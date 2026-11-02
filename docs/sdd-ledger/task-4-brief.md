### Task 4（依帳本 Ruling 改寫）：dsh 啟動網址解析、SessionToken、插件路由驗證 helper

> 原計畫的 Node 前置載入（`auth-gate.cjs`、`NODE_OPTIONS=--require`）**已由帳本 Ruling 取消**：dsh web 本身每次啟動產生隨機 launch token（stdout 印 `dsh web: http://127.0.0.1:<port>/?token=<token>`），`GET /?token=` 換成 HttpOnly + SameSite=Strict 的簽章 cookie，沒有 cookie 的 `/`、`/api/*`、WebSocket 一律 401；Host/Origin 不對 403。**唯一的缺口**：插件自己用 `ctx.webServer.register` 掛的路由完全沒有驗證（見 `docs/dsh-findings.md` §Server、§Plugins「註冊 HTTP 路由」）。本任務補上那個缺口，並提供 App 端需要的兩個小元件。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/security/SessionToken.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/DshLaunchLine.kt`
- Test: `android/app/src/test/kotlin/app/dshmobile/security/SessionTokenTest.kt`、`android/app/src/test/kotlin/app/dshmobile/runtime/DshLaunchLineTest.kt`
- Create: `plugins/packages/common/package.json`（套件名 `@dshm/common`，`private: true`，`type: module`）、`plugins/packages/common/src/auth.ts`、`plugins/packages/common/src/index.ts`（re-export）
- Test: `plugins/packages/common/test/auth.test.ts`
- 若需要：更新 `plugins/pnpm-lock.yaml`（CI 用 `--frozen-lockfile`）

**Interfaces（Produces）:**
- `class SessionToken(val value: String)`；`companion object { fun generate(): SessionToken }`：`SecureRandom` 32 bytes → 64 個小寫 hex 字元。`toString()` 不得洩漏 value（回傳 `"SessionToken(***)"`），避免被記進錯誤記錄。給 Task 5 LocalGateway 用（閘道 URL `http://127.0.0.1:<port>/<token>`）。
- `object DshLaunchLine { fun parse(line: String): String? }`：輸入 dsh 的一行 stdout，若是 `dsh web: <url>` 且 url 為 `http://127.0.0.1:<port>/?token=<非空 token>` 則回傳完整 url，否則回 `null`。容忍前後空白與 ANSI 色碼；host 不是 `127.0.0.1`（例如 `0.0.0.0`、`localhost`、其他 IP）→ `null`；沒有 token → `null`。另提供 `fun port(url: String): Int`（或回傳 data class，實作者擇一，但 Task 8 需要 port 與完整 url 兩者）。Task 8 的 DshController 讀 dsh stdout 用它，HealthCheck／HeartbeatWorker 會用 OkHttp + cookie jar GET 這個 url 取得 cookie。
- `@dshm/common`：`export function requireAuth(ctx: { get(name: 'connection'): { requestRejection(req: IncomingMessage): number | undefined } | undefined }, req: IncomingMessage, res: ServerResponse): boolean`
  - `ctx.get('connection')` 回 `undefined`（例如 headless 組合沒有 connection 服務）→ 回應 503、回傳 `false`（**寧可拒絕，不裸奔**）。
  - `requestRejection(req)` 回數字（401/403…）→ 以該狀態碼回應（JSON body `{"ok":false,"error":"dshm_unauthorized"}`、`cache-control: no-store`）、回傳 `false`。
  - 回 `undefined` → 不寫任何東西、回傳 `true`。
  - Task 6、Task 7 每一個 `/dshm/*` 路由的 handler 第一行都呼叫它。型別只描述用到的最小介面（不要 import dsh 套件）。

**插件的建置方式（Ruling，給後續任務）**：每個插件是 `plugins/packages/<name>/` 的 TS 套件；Task 6／7 會以 esbuild 打包成單一 ESM 檔（`@dshm/common` 內嵌進去），所以 `@dshm/common` 只需能被 workspace 匯入，不需要自己的建置步驟。

- [ ] **Step 1: 寫失敗測試**
  - Kotlin `SessionTokenTest`：兩次 `generate()` 不同；長度 64；只含 `[0-9a-f]`；`toString()` 不含 value。
  - Kotlin `DshLaunchLineTest`：`"dsh web: http://127.0.0.1:3080/?token=abc123"` → 該 url、port 3080；帶 ANSI 色碼與前後空白也能解析；`"dsh web: http://0.0.0.0:3080/?token=x"` → null；`"dsh web: http://127.0.0.1:3080/"`（無 token）→ null；任意其他 log 行 → null。
  - vitest `auth.test.ts`（真的 `node:http` 伺服器 + 假 ctx，打真實 HTTP 請求）：
    ```ts
    it('unauthenticated plugin route gets 401 and handler body never runs', ...)  // requestRejection 回 401
    it('wrong host gets 403', ...)                                                // requestRejection 回 403
    it('authenticated request passes through', ...)                               // 回 undefined → handler 回 200
    it('fails closed with 503 when connection service is missing', ...)
    ```
- [ ] **Step 2:** `cd android && ./gradlew :app:testDebugUnitTest` 與 `pnpm -C plugins test` → FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 兩個測試指令都 PASS；`pnpm -C plugins typecheck` 通過。
- [ ] **Step 5:** Commit `feat: launch-url parser, session token, plugin route auth helper`。
