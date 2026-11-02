# dsh 內部偵察結果（Task 0 findings）

調查日期：2026-11-02。調查環境：Windows 11、Node v24.22.1、npm。
資料來源（只用官方）：
- npm 套件 `@deepseek-ai/dsh@0.2.1-rc.5`（以及它安裝進來的 `@deepseek-ai/dsh-*` 套件內附 README 與 `lib/` 程式碼）
- 官方倉庫 `github.com/deepseek-ai/deepseek-harness`（淺層 clone，HEAD `ce7073d`，2026-10-30；`docs/`、`docs/user/`）
- 官方價格頁（網址列在 §Providers）

標示說明：**[實測]** = 本機實際跑過並看到結果；**[文件]** = 讀官方 README/docs/原始碼得知、沒有實測。

---

## §Pin

**`DSH_PINNED_VERSION = 0.2.1-rc.5`**

- npm dist-tags（2026-11-02）：`latest` = `next` = `0.2.1-rc.5`（2026-10-29 發布）；`alpha` = `0.2.2-alpha.2`（不採用）。
- [實測] 在 Node 24.22.1 上 `dsh web` 正常啟動、介面可開、可對話（接本機假模型伺服器）。
- [實測] Subagent 工具（`subagent`、`subagent_fork`、`list_agents`、`send_message`、`interrupt_agent`、`workflow`）在預設 `standard` 預設組裡可用。
- [實測] Agent Teams（`@deepseek-ai/dsh-experimental-agent-team-profile`）開啟後可正常啟動，模型看得到 `spawn_teammate`、`wait_agent`、`team_task_create/get/list/update`。
- Node 需求：各套件 `engines.node` 為 `>=22.19.0`（少數 `>=20`）；dsh CLI 本身未宣告 engines。Node 24 OK。
- 安裝方式：`npm install @deepseek-ai/dsh@0.2.1-rc.5`（共 614 個套件；npm 安裝約 3 分鐘）。啟動：`node node_modules/@deepseek-ai/dsh/lib/bin.js --profile web ...`。
- 注意：**啟動器旗標（`--profile`、`--patch`）必須寫在 app 旗標（`--no-open`、`--host`、`--port`）之前**。`dsh web --patch x` 會報 `unknown option '--patch'`；要寫 `dsh --profile web --patch x --no-open --port 3080`。

## §Plugins

dsh 基於 Cordis（`@deepseek-ai/cordis` ~4.0.5），一切都是插件。

### 套件格式
- 最小插件 = 一個 ESM 模組，匯出 `name`、`inject`（需要的服務陣列）、`apply(ctx, config)`。也可用物件形式或 class（Service）形式。[文件 `docs/user/develop/basic/index.md`]
- 設定：匯出同名 `Config`（Schemastery schema），載入時驗證並補預設值。[文件 `develop/basic/config.md`]
- 可安裝的「bundle」：`package.json` 加 `"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }`，patch 裡用套件名插入列；可加 `engines.dsh` 版本範圍（不符就拒絕載入）。[文件 `develop/basic/publish.md`]
- [實測] **純 JS、零 import 也行**：`ctx.tools.register()` 直接吃 ToolDefinition 物件，不必 import `@deepseek-ai/dsh-tools` 的 `defineTool`（`defineTool` 只多做參數驗證）。我們的 `plugins/hello/index.js` 就是這樣寫的，不需要 node_modules。

### 如何讓 dsh 載入本機路徑的插件
三種方式，由簡到繁：
1. **`--patch` 疊加檔**（[實測] 用這個）：
   ```yaml
   # plugins/hello/cordis.yml
   - insert:
       - id: dshm-hello
         name: 'C:/dsh-mobile/plugins/hello/index.js'   # 必須是絕對路徑
   ```
   啟動：`dsh --profile web --patch /abs/path/cordis.yml --no-open --host 127.0.0.1 --port 3080`。`--patch` 可重複。
2. **寫進使用者 patch 層**（持久化，不用旗標）：`$DSH_HOME/profiles/web/cordis.patch.yml`（只影響 web profile）或 `$DSH_HOME/cordis.patch.yml`（所有 profile）。同樣是 `- insert:` 列、絕對路徑。
3. **bundle 安裝**：`dsh plugin --profile web add ./plugins/memory`（轉給 pnpm，需要 pnpm）。內建 bundle（`@deepseek-ai/*`）不用 pnpm：直接把名字加進 `$DSH_HOME/profiles/web/package.json` 的 `dsh.profile.bundles` 陣列即可（[實測] Agent Teams 就是這樣開的）。

組合順序（後蓋前，以 `id` 為單位、整個 `config` 取代不合併）：bundles 依序 → profile 的 `cordis.patch.yml` → `$DSH_HOME/cordis.patch.yml` → 各 `--patch`。`dsh --profile web --dump-config` 可不啟動直接印出組合結果。

**建議（給 App）**：App 啟動時用 `--patch <App 內固定路徑>/dshm.cordis.yml` 插入記憶與通知插件；不動使用者的 profile 檔，升級/退版時也不必改 profile。

> **路徑注意**：`plugins/hello/cordis.yml` 與本文出現的 `C:/dsh-mobile/plugins/...` 是**這台開發電腦專用**的絕對路徑，只供本次偵察。正式 App **絕不複製這個路徑**；App 的設定產生器在執行時依自己的安裝位置產生 patch，插件放在 rootfs 內，例如：
> ```yaml
> - insert:
>     - id: dshm-memory
>       name: '/opt/dshm/plugins/memory/index.js'   # 由 App 依實際安裝路徑寫入
> ```

### 註冊工具 [實測]
```js
export const inject = ['tools']
export function apply(ctx) {
  ctx.tools.register({                    // 回傳 disposer；本身已綁 ctx 生命週期
    name: 'hello',
    description: '...',
    parameters: { type: 'object', properties: {} },     // JSON Schema
    output: { schema: { type: 'string' },
              render: (_args, value) => [{ type: 'text', text: value }] },
    async execute(args, exec) { return 'hi' },
  })
}
```
- 在根層（root ctx）註冊 = 全域工具，web 每個對話（不論哪個 agent 預設組）都看得到。[實測：web 的 `standard` 預設組對話裡模型拿到 `hello`，呼叫後得到 `hi`]
- 其他選項：`timeoutMs`、`deferLoading`、`isConcurrencySafe`、`presentCall/presentResult`；`ctx.tools.restrict()` 只能在 agent scope 用。[文件 `dsh-tools` 型別]

### 對話開始時注入上下文 [實測]
**可以，最好用 `ctx.systemPrompt.context()`**：
```js
export const inject = ['systemPrompt']
ctx.systemPrompt.context({
  name: 'dshm-memory',
  order: 9000,
  interpolate: false,           // 保留原文，不處理 {{變數}}
  text: (assembleCtx) => readMemoryFilesSync(),   // 每次組裝時重新讀
})
```
- 這會出現在每次請求第一個 user 訊息裡的「Current runtime context」區塊（durable user-role snapshot），不動 system prompt，對 KV 快取較友善。[實測：假模型收到的第一則 user 訊息含我們的探針字串 `DSHM_HELLO_CONTEXT_PROBE`]
- 文字有變時會在下一次 admission 以新的 snapshot 補上（"This snapshot supersedes earlier runtime-context snapshots"）。
- 其他做法：
  - `ctx.systemPrompt.section({ name, order, text })`：放進 system prompt（內容變動會破壞前綴快取）。[文件]
  - `ctx.on('agent/pre-step', async ({agent, messages, step, signal}, next) => ...)`（waterfall）：可在第一步把自訂 user 訊息插進該步；官方 `dsh-agent-instructions` 就是這樣注入 AGENTS.md。較複雜。[文件+讀原始碼]
  - `ctx.on('agent/created', ({agent, source}) => ...)`（serial）：agent 建立時呼叫，`source` 區分 fresh/resume/clear/compaction。[文件 `dsh-agent` 型別]

### 對話壓縮前的鉤子（有無）
**沒有專門的「壓縮前」事件。** [文件 + 讀 `dsh-compaction-basic` 原始碼]
- 壓縮（`compaction-basic`）由它自己的 `agent/pre-step` serial listener 觸發；事件層只宣告了 `compaction/summary-error`（失敗補救用 waterfall）。
- 壓縮是一筆交易：先同步寫入 session 事件 `compaction/start`，再做摘要，再寫 `compaction/summary` + 替換訊息 + `compaction/end`。
- **替代做法**（給「壓縮前先把重點寫進記憶」）：
  1. 監聽 `ctx.on('session/event', (session, event) => { if (event.type === 'compaction/start') ... })`，拿到 session 後另開一次 `ctx.llm` 呼叫把要記的東西寫進 `memory/YYYY-MM-DD.md`。這是「同時」而非「之前」，無法阻擋壓縮，但壓縮前的原始歷史仍在 session log 裡可讀。
  2. 另外監聽 `ctx.on('agent/created', ({source}) => ...)`，`source` 為 compaction 時代表壓縮後新 agent，可再補注入。
  3. 不在意即時性的話，最穩的是定期（心跳）或每回合結束時寫記憶，不依賴壓縮時機。
  - 上述 1/2 尚未實測。

### 註冊 HTTP 路由 [實測]
```js
export const inject = ['webServer']
ctx.effect(() => ctx.webServer.register({
  kind: 'exact',                     // 或 'prefix'
  path: '/dshm/health/hello',        // 絕對路徑、不帶結尾斜線
  handler(req, res) { /* node:http IncomingMessage / ServerResponse */ },
}))
```
- `register()` 回傳 disposer，要自己包 `ctx.effect()` 讓卸載時移除。另有 `registerUpgrade({path, handler(req, socket, head)})` 可掛 WebSocket。
- 比對順序：exact → 最長 prefix → fallback（SPA）。重複路徑會丟錯。
- **重要：`webServer` 路由沒有任何驗證、也不檢查 Host 標頭**。[實測：`curl -H 'Host: evil.example' /dshm/health/hello` 仍回 200]
  要保護就在 handler 裡呼叫 dsh 自己的驗證：`ctx.get('connection').requestRejection(req)` → 回 `401 | 403 | undefined`（套用 Host/Origin 檢查 + 瀏覽器 cookie 驗證）。[實測：`/dshm/health/hello-auth` 無 cookie 回 401、帶登入 cookie 回 200]
- `webServer` 只在 web 組合存在；`headless` profile 沒有，插件若硬性 `inject: ['webServer']` 會停在 pending（[實測] headless 印出 `pending (waiting for service: webServer)`）。記憶插件應把 webServer 設為可選（不放 `inject`，改 `ctx.get('webServer')`）。
- 另一種：`ctx.connection` 的 exact Fetch route（只能在 `/api/...` 下、自動受驗證、非 owner 回 403）。[文件]

### 驗證插件 `plugins/hello/`
- `GET /dshm/health/hello` → `{"ok":true}`（無驗證）
- `GET /dshm/health/hello-auth` → 經 `connection.requestRejection` 驗證
- 工具 `hello()` → `"hi"`
- runtime context 探針、index 注入 `<style>` 探針
- 憑證寫入守門探針（round 1 新增）：`connection/request` waterfall 擋掉 `/api/credentials/set`、`/api/credentials/unset`、`/api/account/startSignIn`（見 §Server「隱私與金鑰」）
- 載入（僅限本機偵察，路徑為開發機專用）：`dsh --profile web --patch C:/dsh-mobile/plugins/hello/cordis.yml --no-open --host 127.0.0.1 --port 3080`
- （Task 6 前刪除）

## §Config

### 位置
- `$DSH_HOME`（預設 `~/.dsh`；環境變數 `DSH_HOME` 可改）。另有共用根 `$DSH_AGENTS_HOME`（預設 `~/.agents`，放全域 `AGENTS.md` 與 `skills/`）。[文件 `dsh-home-paths`]
- [實測] 首次啟動後 `$DSH_HOME` 內容：
  ```
  .anonymous-user-id          匿名使用者 ID（請求標頭 x-deepseek-harness-user-id 會帶）
  .credentials.yaml           憑證（API 金鑰、cookie 簽章密鑰）— 不要讀/印
  cordis.patch.yml            （可選）全 profile 共用的使用者 patch；ui-theme 設定預設也存這裡
  profiles/web/package.json   profile 清單：dsh.profile.bundles
  profiles/web/cordis.patch.yml  使用者 patch 層（設定頁存檔寫這裡）
  profiles/web/cordis.yml, pnpm-workspace.yaml
  sessions/<編碼後的工作目錄>/session-<uuid>   對話紀錄（jsonl）
  storages/workspace.json     工作區清單
  ```
- 格式：YAML 陣列（Cordis loader patch：以 `id` 覆寫、`disabled`、`insert`；可用 `!!js` 運算式）。

### 模型提供者 base URL / 金鑰欄位
- **DeepSeek 官方路由** `deepseek-official`（`@deepseek-ai/dsh-llm-deepseek-api-key`，row id `llm-deepseek`）：[文件 + 實測]
  - 協定：**Anthropic Messages**（不是 OpenAI 格式）。預設 `baseURL: https://api.deepseek.com/anthropic`，會接 `/v1/messages`、`/v1/files`。
  - `baseURL` 優先序：config 值 → `$DEEPSEEK_BASE_URL` → 官方預設。[實測：`DEEPSEEK_BASE_URL=http://127.0.0.1:3999` 後請求打到 `POST /v1/messages`]
  - 金鑰：`apiKeyEnv: DEEPSEEK_API_KEY`（憑證參照，每次請求解析；設定頁存的金鑰進 `$DSH_HOME/.credentials.yaml`）。送出標頭 **`x-api-key`**。不跟隨轉址。
  - 其他欄位：`reasoningEffort`（off/low/high/max，預設 high）、`thinking`、`maxTokens`（256000）、`models`、`streamIdleTimeoutMs`、`retryPolicy` 等。
  - [實測] 每個請求還帶：`user-agent: deepseek-harness/0.2.1-rc.5`、`anthropic-version: 2023-06-01`、`x-deepseek-harness-user-id`、`x-deepseek-harness-session-id`，以及 body 頂層的 **`dsh_session_log`、`dsh_plugin_packages`**（官方擴充欄位：出廠預設開啟的對話紀錄上傳與插件清單，不進模型輸入）。→ 依主腦裁定**預設關閉**，用設定開關關掉（見 §Server「隱私與金鑰」）；閘道仍剝除這兩個欄位與 `x-deepseek-harness-*` 標頭，作為第二道防線。
- **Anthropic 路由（pi-ai）** [實測，假模型]：請求打到 `POST {baseURL}/v1/messages?beta=true`，金鑰放在 **`x-api-key`**（已確認）；另帶 `anthropic-version`、`anthropic-beta`、`anthropic-dangerous-direct-browser-access` 與 `x-stainless-*` 標頭；**不帶** `x-deepseek-harness-*` 標頭，也不帶 `dsh_*` body 欄位。純文字回合往返成功（模型 `claude-sonnet-5-5`）。此路由的工具往返**未驗證**：我的簡易假模型回 `tool_use` 時，dsh 在 200 秒內連發 3,672 個請求，始終沒有送回 tool_result。多半是假模型的串流格式不合 pi-ai 的解析，但也表示回應格式異常時 dsh 可能快速重送。→ App 閘道要有每分鐘請求數與每日花費上限。
- **其他提供者（含 Anthropic）** 走 `@deepseek-ai/dsh-llm-pi-ai`（row id `llm-pi-ai`），內建 pi-ai 1.5.0 型錄：
  ```yaml
  - id: llm-pi-ai
    config:
      providers:
        anthropic:
          apiKeyEnv: ANTHROPIC_API_KEY
          baseURL: http://127.0.0.1:<代理埠>     # 指向 App 代理
  ```
  自訂提供者需 `api`（`openai-completions` / `openai-responses` / `anthropic-messages`）、`baseURL`、`models`。
- **網頁搜尋** 另有獨立端點設定（見 §Search）：`$DEEPSEEK_SEARCH_BASE_URL`，**不跟對話的 `DEEPSEEK_BASE_URL` 共用**。
- 金鑰代理方案可行：設 `DEEPSEEK_BASE_URL` / `DEEPSEEK_SEARCH_BASE_URL` / pi-ai `baseURL` 指向 App 代理，`DEEPSEEK_API_KEY` 給一個假值（dsh 會把它放進 `x-api-key`，代理改寫成真金鑰）。

### 各角色模型
| 角色 | 設定鍵 |
|---|---|
| 新對話預設模型 | row `agent-default-model`：`provider`（預設 `deepseek-official`）、`model`（預設 `deepseek-flash`）、`reasoningEffort` |
| 使用者選擇 | 介面選模型時存成新對話預設；已開始的對話沿用 log 裡的模型 |
| 子代理人 | row `tool-subagent`（在 preset 的 `delegation` 群組內）`modelSelection`、`agentOptions`；全域開關 row `subagent-model-selection-settings` |
| 壓縮摘要 | row `compaction-basic`：`summarizationProvider` + `summarizationModel`（不設 = 沿用對話模型），可用 `modelPolicies` 依模型覆寫 |
| 對話標題 | row `session-title-llm`（`dsh-session-title-first-prompt-llm`）— [實測] 用對話模型另發一次請求產生標題 |
| 網頁搜尋 | row `web-search-deepseek`：`model`（預設 `deepseek-v4-flash`） |

### 子代理人同時數、深度、團隊人數
| 項目 | 設定鍵 | 預設 |
|---|---|---|
| 同時活著的子代理人數 | row `subagent`（`@deepseek-ai/dsh-subagent`）`config.maxActiveSubagents` | 8 |
| 委派深度 | row `subagent` `config.maxDepth`（全域預設）；個別工具 row `tool-subagent` `config.maxDepth`（數字或 `'provider-managed'`，0 = 禁止委派） | 1 |
| 團隊人數 | row `agent-team`（`@deepseek-ai/dsh-experimental-agent-team`）`config.maxMembers` | 8 |
| 團隊任務上限 / 訊息大小 | `agent-team` 的 `maxTasks` / `maxMessageBytes` / `disposalTimeoutMs` | 256 / 65536 / 5000 |

開啟 Agent Teams：把 `@deepseek-ai/dsh-experimental-agent-team-profile` 加進 `profiles/web/package.json` 的 `dsh.profile.bundles`（或在介面「插件」頁開啟）。注意：在 web 組合中開了 Agent Teams 後，preset 內的 `subagent`/`subagent_fork` 工具仍然存在（[實測] 兩組工具同時出現），因為 bundle 只停用根層那幾列，web 的子代理人工具在 preset 裡。

**Agent Teams 與子代理人並存時的 maxDepth** [讀原始碼，未實測]：
- 隊友是 Lead 的直接子代理（深度 1），團隊是扁平的（只有 Lead 能建隊友，沒有巢狀團隊）。Agent Teams 啟動隊友時不帶 `maxDepth`。
- preset 的 `subagent` 工具沒寫 `maxDepth`，所以每次委派都讀 host 的 `subagent.maxDepth`（預設 1）。深度檢查是 `childDepth > maxDepth` 就拒絕。
- 結論：只用團隊（隊友不再往下委派）時，**`maxDepth: 1` 就夠**。若要讓隊友或子代理人再叫自己的子代理人，**設 `maxDepth: 2`**。
- `maxActiveSubagents` 是所有「可續接子代理」共用的上限，隊友也佔名額。建議 `maxActiveSubagents ≥ maxMembers + 預期同時子代理人數`，例如團隊 4 人時設 12。

### 設定產生器要輸出的 patch（逐項片段）

下面的片段我組成一份 `--patch`，以 web profile 啟動 [實測]：
- 啟動時零警告，所有插件都正常啟用（沒有 "did not activate"）。
- 沒設 `DEEPSEEK_BASE_URL` 環境變數，請求仍打到 yaml 的 `baseURL`（假模型收到請求、帶 `x-api-key`）。
- 新對話自動使用我們插入的 `dshm` 預設組（介面顯示 "dshm"），`/compact` 成功（「已壓縮 4 條歷史記錄」）。
- 列名與結構來自 `--dump-config`。

每列 `config` 是**整個取代**，不會合併，所以每個片段都要寫齊該列要的所有鍵。

**1. 提供者 base URL 與金鑰欄位**
```yaml
# DeepSeek 對話（Anthropic Messages 協定；dsh 會接 /v1/messages）
- id: llm-deepseek
  config:
    apiKeyEnv: DEEPSEEK_API_KEY          # 只放「環境變數名」；App 給假值，閘道換真金鑰
    baseURL: http://127.0.0.1:<閘道埠>/deepseek
# DeepSeek 網頁搜尋（另一個端點；dsh 會接 /messages，所以要含 /v1）
- id: web-search-deepseek
  config:
    apiKeyEnv: DEEPSEEK_API_KEY
    baseURL: http://127.0.0.1:<閘道埠>/deepseek/v1
# Anthropic（pi-ai 內建提供者；dsh 打 {baseURL}/v1/messages?beta=true，金鑰在 x-api-key）
- id: llm-pi-ai
  config:
    providers:
      anthropic:
        apiKeyEnv: ANTHROPIC_API_KEY
        baseURL: http://127.0.0.1:<閘道埠>/anthropic
```
（`web-search-deepseek.baseURL` 只做了 schema 載入驗證，搜尋請求本身沒有實測。DeepSeek 對話也可以只用環境變數 `DEEPSEEK_BASE_URL`，兩種都實測過。）

**2. 各角色模型**
```yaml
- id: agent-default-model            # Lead（新對話預設）
  config:
    provider: deepseek-official
    model: deepseek-flash
```
- 子代理人與隊友：不設定就**沿用父代理的模型**。要讓模型自選，就在 preset 的 `tool-subagent` 開 `modelSelection: true` [文件]。Agent Teams 的 `tool-agent-team` 只設 provider（`spawn`/`fork`），沒有模型欄位，隊友沿用 Lead 的模型 [文件]。
- 壓縮摘要模型在 **preset 裡**，根層 patch 打不到。[實測：以 `id: tool-subagent` 下的 patch 只會改到根層那一列，那列在 web 本來就停用]
  做法是**插入我們自己的 preset，並設成預設**。內容複製 `--dump-config` 裡的 `preset-standard` 整列，改 `id`、`order` 和要調整的子列：
  ```yaml
  - id: agent-preset-registry
    config:
      default: dshm
  - insert:
      - id: preset-dshm
        name: '@deepseek-ai/dsh-agent-preset'
        config:
          id: dshm
          order: 0
          plugins:
            # ……其餘子列原樣照抄 preset-standard（persona、agent-instructions、tool-*、planning、delegation 群組……）
            - id: compaction
              name: cordis:group
              group: true
              isolate: { compaction: true, toolResultPruner: true }
              config:
                - id: compaction-basic
                  name: '@deepseek-ai/dsh-compaction-basic'
                  config:
                    summarizationProvider: deepseek-official   # 兩個要一起設
                    summarizationModel: deepseek-flash
                - id: command-compact
                  name: '@deepseek-ai/dsh-command-compact'
                - id: tool-result-pruner
                  name: '@deepseek-ai/dsh-compaction-tool-result-pruner'
                  config: { thresholdChars: 8192, headChars: 4096, tailChars: 1024 }
  ```
  註：這次摘要模型與主模型相同，所以只驗證到「設定被接受、壓縮成功」，沒有驗證到「摘要確實改用另一個模型」。另外，dsh 升級時 `preset-standard` 可能會變，**設定產生器應在執行時從 `--dump-config --dump-default-config` 取出 `preset-standard` 再改寫**，而不是寫死一份。
- 網頁搜尋模型：`web-search-deepseek` 的 `model`（預設 `deepseek-v4-flash`），要寫在上面第 1 點的同一列裡。

**3. 同時數、深度、團隊人數**
```yaml
- id: subagent
  config:
    maxActiveSubagents: 12
    maxDepth: 1            # 隊友要再往下委派就改 2
- id: agent-team           # 只有在 agent-team-profile bundle 有開時才存在
  config:
    maxMembers: 4
    maxTasks: 256
    maxMessageBytes: 65536
    disposalTimeoutMs: 5000
```

**4. 遙測關閉、不上傳**：見 §Server「隱私與金鑰」的 privacy patch，另設環境變數 `DSH_TELEMETRY_MODE=DISABLED`。

**5. 網頁內瀏覽式資料夾選擇器**
```yaml
- id: directory-picker
  disabled: true
- insert:
    - id: directory-picker-browse
      name: '@deepseek-ai/dsh-host-directory-picker-browse'
```

**6. 插件路徑**：見 §Plugins 的路徑注意。路徑由 App 在執行時依 rootfs 內的安裝位置（例如 `/opt/dshm/plugins/...`）產生。

## §Server

- [實測] `dsh web` 只開 **一個 TCP 監聽埠**：預設 `127.0.0.1:3080`。沒有 UDP。HTTP 與 WebSocket 同一埠。
  - WebSocket：`/api/remote.mux`（API Gateway 多工串流；瀏覽器 console 可見）。
  - HTTP：`/`（index，需驗證）、`/api/<namespace>/<method>`（RPC，POST）、`/connect`（API 客戶端換 cookie）、`/plugins/<id>/client.js`（前端插件，公開）、靜態資源（公開）。
- **子行程**：[實測] 平常沒有。按「選擇工作區」時，`directory-picker-auto` 在有桌面環境時會起一個 `dsh-host-directory-picker-native/lib/worker.cjs` 子行程開 **作業系統原生資料夾對話框**。手機（proot、無顯示）上 auto 應會選瀏覽式 picker；保險起見 App 可 patch 掉：
  ```yaml
  - id: directory-picker
    disabled: true
  - insert:
      - id: directory-picker-browse
        name: '@deepseek-ai/dsh-host-directory-picker-browse'
  ```
  （[實測] 這個 patch 可用，介面改走網頁內瀏覽。）
- 其他可能開埠的（預設都沒開）：`dsh-experimental-inspector`（另一個 listen）、語音辨識 worker；MCP stdio 子行程視設定。
- 對外連線（dsh 出廠預設開啟）：`session-telemetry-otel`（`DSH_TELEMETRY_MODE`，預設 `FEEDBACK_ONLY`，端點 `https://dsh-otel-collector.deepseeksvc.com/v1/logs`）、`dsh_session_log` / `dsh_plugin_packages` 上傳。**本 App 預設全部關閉**，見下方「隱私與金鑰」。

### 隱私與金鑰（主腦裁定：預設遙測關閉、不上傳對話紀錄）

**(a) 關閉 `session-telemetry-otel`** [文件 `dsh-session-telemetry-otel` README；實測見下]
- 模式只有 `FEEDBACK_ONLY`（預設，只在使用者按回饋時上傳）與 `DISABLED`（不建立任何 coordinator/exporter，沒有任何紀錄離開行程）。`FULL` 不接受。
- 兩種寫法擇一（建議兩個都做）：
  - 環境變數 `DSH_TELEMETRY_MODE=DISABLED`（base 的 row 是 `mode: !!js process.env.DSH_TELEMETRY_MODE || 'FEEDBACK_ONLY'`）
  - patch：`- id: session-telemetry-otel` / `config: { mode: DISABLED }`（DISABLED 不需要 exporter URL）
- 另外 `desktop-product-telemetry` / `product-analytics` 兩列只在 profile 名為 `desktop` 時啟用，web 不會跑。[文件，dump-config 確認]

**(b) 停止 `dsh_session_log` / `dsh_plugin_packages`** [文件 + 實測]
- dsh 有開關：row `session-log-deepseek` 的 `enabled: false`、row `plugin-package-inventory-deepseek` 的 `enabled: false`。
- 介面「設定 → 一般 → Upload Session Log」也會寫 `enabled`，但 `--patch` 疊加層與 home patch 優先權較高，會**拒絕衝突的表單寫入**。[文件] → App 一律用 `--patch` 下發，使用者在介面也改不回來。
- 依然無法關的：`x-deepseek-harness-user-id`（匿名安裝 ID，「沒有設定」，只送 DeepSeek 路由）與 `x-deepseek-harness-session-id` 標頭。→ **閘道剝除**這兩個標頭，並再剝一次 `dsh_*` body 欄位當第二道防線。
- [實測] 用假模型、headless、同一提示詞比較：
  - 不加 patch：body 頂層 `[..., 'dsh_session_log', 'dsh_plugin_packages']`
  - 加 privacy patch：body 頂層只剩 `['model','stream','messages','max_tokens','thinking','output_config','system','tools']`
  - 加 privacy patch 的整個執行期間，監看 dsh 行程（及子行程）所有 TCP 連線：**沒有任何非 loopback 連線**（含 otel 端點）。註：遙測本來就只在使用者回饋時上傳，所以「沒連線」對 (a) 只是旁證；(a) 的保證來自文件「DISABLED 不建立 transport」。

完整 privacy patch（App 下發）：
```yaml
- id: session-telemetry-otel
  config:
    mode: DISABLED
- id: session-log-deepseek
  config:
    enabled: false
- id: plugin-package-inventory-deepseek
  config:
    enabled: false
```
（`--dump-config` 確認三列都被覆寫。）

**(c) 防止真金鑰經由 dsh 設定介面存進 `$DSH_HOME/.credentials.yaml`**
- `dsh-credentials-local` **沒有唯讀模式**（設定只有 `path`、`dshHome`、`watch`、`debounceMs`），而且它還存 cookie 簽章密鑰，不能整個拿掉。[文件]
- 憑證解析優先序：**已存檔案 > 啟動環境變數 > 專案 `.env` > `$DSH_HOME/.env`**。[文件] 意思是：使用者若在介面存了真金鑰，會蓋過我們給的假金鑰 → 真金鑰落地在 agent 可讀的檔案。另外 dsh 會讀工作目錄與 `$DSH_HOME` 的 `.env`，App 不要在那裡放任何東西。
- 寫入入口（瀏覽器 RPC）只有三個：`credentials/set`、`credentials/unset`（設定頁「模型」與「網頁搜尋」的金鑰欄位）、`account/startSignIn`（DeepSeek 帳號登入，會存 grant）。pi-ai 的 OAuth 授權流程在這個組合沒有開放成 Remote。[讀原始碼]
- **不改原始碼的擋法（已實測）**：我們的插件掛 `connection/request` waterfall（每個已驗證的 `/api` HTTP 請求在讀 body 前都會經過），對上面三個路徑直接回 403：
  ```js
  const BLOCKED = new Set(['/api/credentials/set', '/api/credentials/unset', '/api/account/startSignIn'])
  ctx.on('connection/request', async (req, res, next) => {
    if (BLOCKED.has(new URL(req.url, 'http://x').pathname)) { res.writeHead(403); res.end(); return }
    return next()
  })
  ```
  [實測] 三個都回 403，`credentials/describe` 正常，`.credentials.yaml` 的 mtime 不變、探針 ref 沒出現。unary Remote 一律走 HTTP POST（WebSocket mux 只給 stream 方法），所以這裡擋得住。
- 再加兩道保險 [文件，未實測]：
  1. 在 `--patch` 裡把 `llm-deepseek`、`llm-pi-ai`、`web-search-deepseek` 三列整個寫死（都只用 `apiKeyEnv`），讓介面的 `settings/mutate` 寫入（例如 `web-search-deepseek.apiKey` 字面值、pi-ai `headers`）因為與疊加層衝突而被拒。
  2. 可選擇停用前端設定卡片列 `ui-settings-account`（以及必要時 `ui-settings-web-search`），隱藏入口。`ui-settings-models` 也有模型清單編輯，先不停用，靠 RPC 守門即可。
- 殘餘風險：agent 自己用 shell 寫 `$DSH_HOME/.credentials.yaml`。web 預設檔案政策是 `workspace-write`（只能寫工作區），`$DSH_HOME` 不在工作區內；但使用者若授權，agent 仍可寫。閘道一律忽略傳入的 `x-api-key`、改用 Keystore 的真金鑰，就算檔案被寫入也不會被用來繞過閘道。
- **改埠**：`--port <n>`（0 = 系統配發，從 stdout 讀實際 URL）；`--host <IP>` 只接受單一具體位址，萬用位址（0.0.0.0、::）一律拒絕。另有 `--tls-cert/--tls-key`、`--public-url`、`--trusted-host`。
- **內建驗證：有。** [實測 + 文件 `dsh-client-connection`]
  - 每個行程產生隨機 launch token，啟動時 stdout 印一行 `dsh web: http://127.0.0.1:3080/?token=<token>`（`--no-open` 不開瀏覽器）。token 不能從外部指定（`randomBytes` 產生），每次重啟都不同。[實測]
  - 只在 `GET /?token=` 時接受 token → 回 303 並設簽章 cookie（`dsh-auth-<hash>`，HttpOnly、SameSite=Strict、Path=/、預設 30 天，`cookieMaxAgeDays` 可調）→ 轉址到乾淨的 `/`。
  - 沒有 cookie：`GET /` 回 401、`/api/*` 回 401；Host/Origin 不對回 403（防 DNS rebinding）。[實測]
  - 所有 RPC 與 WebSocket 都要 cookie。靜態資源與 **插件自己用 `webServer.register` 掛的路由不受保護**（見 §Plugins）。
  - cookie 簽章密鑰存在 `$DSH_HOME/.credentials.yaml`（`client-connection/browser-session`）；刪掉重啟 = 全部登出。
  - **對規格的影響**：規格 §安全的「每次啟動產生隨機通行碼、沒有通行碼的 HTTP 與 WebSocket 一律拒絕」dsh 已內建。App 只需：啟動 dsh → 從 stdout 讀 `dsh web:` 那行的 URL → 讓 WebView 載入該 URL（換到 cookie）。同手機其他 App 拿不到 token（stdout 只在 App 內），也拿不到 WebView 的 HttpOnly cookie。我們自己的路由（通知橋接等）要在 handler 裡呼叫 `connection.requestRejection(req)`，或另用 App 自己的通行碼。不需要 `--require` 包 HTTP 伺服器。
- RPC 呼叫格式（給 App 或測試直接打 API 用）[實測]：
  `POST /api/<namespace>/<method>`，body `{"type":"client-request","rpcId":"<任意字串>","method":"<namespace>/<method>","payload":{"args":{...}}}`，回 `{"type":"server-response","rpcId":...,"result":{"ok":true,"value":...}}`。例：`workspace/create` 參數 `{"request":{"path":"<絕對路徑>"}}`。

## §Memory

- **dsh 原生不讀 `MEMORY.md`。** [實測 grep：已安裝的 614 個套件的 `.js`/`.md` 與官方倉庫全文都沒有出現 `MEMORY.md`]
- dsh 原生讀的是 **AGENTS.md 類指示檔**（`@deepseek-ai/dsh-agent-instructions`，web 版放在每個 agent preset 裡）：[文件 + 讀原始碼]
  - 時機：每個 session 的 **第一個 `agent/pre-step`**（第一次送模型請求前）載入一次，作為一則 durable user 訊息（`<system-reminder>` 包起來）；之後只在 `read`/`write`/`edit` 工具碰到更深的目錄、或檔案變動時補送；resume 時對帳。不持續監看外部編輯。
  - 位置與順序（廣到窄）：`$DSH_HOME/AGENTS.md` → `$DSH_AGENTS_HOME/AGENTS.md`（`~/.agents/AGENTS.md`）→ 專案根（`.git` 所在）一路到工作目錄，每層的 `AGENTS.md`、`CLAUDE.md`、`AGENTS.local.md`、`CLAUDE.local.md`。
  - 上限：`maxBytes: 65536`（整體），單檔 `maxSourceBytes` 1 MiB。
  - 可設定 `instructionFileCandidates`（預設 `['AGENTS.md','CLAUDE.md']`），理論上可加 `'MEMORY.md'`，但那只會讀「專案目錄鏈」裡的 MEMORY.md，不會讀 `$DSH_HOME`，而且 preset 的 row 要整個重寫（4 個 preset 各一份）。不建議。
- 官方另有「記憶 MCP 伺服器」範例（Memorix、MCP Reference Memory、Engram），預設關閉。[文件 `docs/user/guide/mcp-memory.md`]
- **對規格的影響**：規格 5.3「與 dsh 原生會讀的 MEMORY.md 共用同一份」前提不成立。建議：記憶插件自己用 `ctx.systemPrompt.context()` 注入 `MEMORY.md` + `USER.md` + 今天/昨天的 `memory/YYYY-MM-DD.md`（已實測此機制可行）。檔案位置由我們決定（建議 `$DSH_HOME/memory/` 或 App 工作區根目錄）。若想讓 dsh 原生機制也看到，可把長期記憶寫進 `$DSH_HOME/AGENTS.md`，但那是使用者的指示檔，混用不好，不建議。

## §UI

### 主題 / CSS 變數
- 介面配色由 `@deepseek-ai/dsh-client-ui-theme` 提供：`light` / `dark` / `system`，設定存在 `ui-theme` namespace（預設寫入 `$DSH_HOME/cordis.patch.yml`）。[文件]
- CSS token：`--dsw-*`（約 107 個 `--dsw-alias-*` 別名 token，套在 `body` 上；暗色時 `body[data-ds-dark-theme]`）。重點 token：`--dsw-alias-brand-primary`、`--dsw-alias-state-business-primary`（主要藍色，light `#4176E6`／dark `#7AAAFF`）、`--dsw-alias-label-primary`、`--dsw-alias-bg-layer-1/2/3`、`--dsw-alias-bg-overlay`、`--dsw-focus-ring-color`；字型與字級：`--dsh-font-family-text/code/terminal`、`--dsh-content-font-size`。[實測讀 computed style：`--dsw-alias-brand-primary` = `#f9fafb`（暗色）、`--dsw-alias-state-business-primary` = `#7aaaff`]
- 官方主題擴充點：前端 `ctx.theme` 可註冊第三方主題 id 與 alias-token 覆寫（需要寫前端 client 插件 bundle）。[文件]
- **最簡單、已驗證的做法**：伺服器端插件監聽 `webserver/index-inject`，往 index.html 塞 `<style>`：
  ```js
  ctx.on('webserver/index-inject', (table) => {
    table.push({ kind: 'style', text: 'body{--dsw-alias-state-business-primary:#ff5fa2; ...}' })
  })
  ```
  [實測：index.html 出現注入的 style，瀏覽器 `getComputedStyle(body)` 讀得到 `--dshm-probe: 1`]。可注入的 row 類型：`global`、`script`、`script-src`、`script-preload`、`style`、`html`；另有 `ctx.webServer.tapIndex(html => html)` 原始字串轉換。不用改官方原始碼，也不用 WebView 注入 CSS。
- 官方前端在 `@deepseek-ai/dsh-web-frontend/dist`（index.html + `assets/`）。

**粉紅配色要覆寫的 light 模式 token**（從 `dsh-client-ui-theme/lib/client.js` 抽出 light 值；前者 light、後者 dark）[讀原始碼]：
- 主色全部來自一組 `--dsw-static-deepseek-*` 色階。**最省事的做法是直接把這組色階換成粉紅色階**，所有引用它的 alias 會一起變：

  | static token | 原值 | 被誰用（light / dark） |
  |---|---|---|
  | `--dsw-static-deepseek-50` | `#edf3fe` | 1 處引用（未逐一追查） |
  | `--dsw-static-deepseek-100` | `#e4edfd` | `--dsw-alias-state-business-tertiary`（light） |
  | `--dsw-static-deepseek-200` | `#d3e2ff` | 1 處引用（未逐一追查） |
  | `--dsw-static-deepseek-300` | `#b7c8fe` | 無 var() 引用 |
  | `--dsw-static-deepseek-400` | `#7aaaff` | dark 的 primary / link / info 按鈕 |
  | `--dsw-static-deepseek-450` | `#5686fe` | 新版主色 `--dsw-alias-brand-primary-new-colorprimary-new-color` |
  | `--dsw-static-deepseek-500` | `#4176e6` | **light 的主色**：`--dsw-alias-state-business-primary`、`--dsw-alias-link`、`--dsw-alias-button-info-fill`、`--dsw-alias-button-info-hover`、焦點框 |
  | `--dsw-static-deepseek-600` | `#4868b2` | 無 var() 引用 |
  | `--dsw-static-deepseek-800` / `-900` | `#34415b` / `#283142` | dark 的 tertiary |

- 若只想覆寫 alias（light 模式），清單如下：`--dsw-alias-state-business-primary`、`--dsw-alias-state-business-tertiary`、`--dsw-alias-link`、`--dsw-alias-button-info-fill`、`--dsw-alias-button-info-hover`、`--dsw-alias-brand-primary-new-colorprimary-new-color`、`--dsw-focus-ring-color`、`--dsw-alias-bg-document-selection`（藍 40% 透明的反白底色）。
  - 可選的整體淡粉背景：`--dsw-alias-bg-base`、`--dsw-alias-bg-layer-1/2/3`（light 皆為純白 `neutral-bluish-00`）、`--dsw-alias-bg-module-platform`、`--dsw-alias-bg-multi-select`（`neutral-bluish-60`）。
- `--dsw-alias-brand-primary` / `--dsw-alias-brand-text` / `--dsw-alias-label-primary` 在 light 是近黑（`neutral-bluish-1000`），屬於文字色，**不要**改成粉紅。
- 選擇器：light 規則在 `:root` / `body`，dark 在 `body[data-ds-dark-theme]`。注入的 `<style>` 要用相同或更高的特異度，例如 `body{...}` 與 `body[data-ds-dark-theme]{...}` 各寫一份。實際效果需在後續主題任務用截圖驗證。

### 對話的 URL 格式
- **沒有。** [實測] 開新對話、送訊息後網址始終是 `http://127.0.0.1:3080/`（只有 `document.title` 變成對話標題）；前端 bundle 沒有用 `pushState`/`location.search`/`hash` 做路由。官方 README 也寫明沒有事件深連結。
- 對話 ID 格式：`session-<uuid>`（例：`session-d7edacee-...`），存在 `$DSH_HOME/sessions/<編碼工作目錄>/session-<uuid>`。
- 前端內部有打開對話的 API：client 端服務 `ctx.uiWorkspace.openSession(sessionId)`（`ui-schedule` 就用它）。[讀原始碼]
- **替代做法（未實測）**：通知深連結改成 App 自訂格式（例 `dshm://session/<id>`）；App 收到後
  (a) 用我們插件經 `webserver/index-inject` 注入的 `<script>`／或寫一個小 client 插件，讀 `?dshm_session=<id>` 再呼叫 `ctx.uiWorkspace.openSession(id)`；需要確認 script 能拿到 client 端 Cordis ctx（client 插件一定拿得到，inline script 不一定）；或
  (b) 最低限度：只把 App 切到前景、打開 dsh 首頁，對話列表裡找。
  → 需要後續任務實作與驗證。

## §Providers

### 官方 Anthropic 模型接口插件
- **有，內建**：`@deepseek-ai/dsh-llm-pi-ai`（已包含在 dsh 安裝裡、base 組合已掛上 row `llm-pi-ai`），內附 pi-ai 1.5.0 型錄，提供者 id `anthropic`，預設端點 `https://api.anthropic.com`，金鑰 `apiKeyEnv: ANTHROPIC_API_KEY`（或介面「新增模型提供者 → anthropic」）。不需另裝插件。[文件 `docs/user/guide/providers.md` + 讀 pi-ai dist]
- 型錄內已有的 Claude id（節錄）：`claude-fable-5-1`、`claude-opus-5-5`、`claude-sonnet-5-5`、`claude-haiku-5-5`、`claude-opus-5`、`claude-sonnet-5`、`claude-haiku-4-5` 等。

### DeepSeek 目前模型名稱
來源：<https://api-docs.deepseek.com/quick_start/pricing>（2026-11-02 讀取）
- `deepseek-flash` = DeepSeek-V4.1-Flash-1017（文字 + 圖片；dsh 預設模型）
- `deepseek-v4-pro` = DeepSeek-V4-Pro-0813
- 舊名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 仍接受（對應已退役模型、映射到 `deepseek-flash`）。dsh 網頁搜尋預設仍用 `deepseek-v4-flash`。
- 兩者 context 1M；最大輸出 384K。

### 官方價格（美元 / 每百萬 token）

> 本節每個數字都是 2026-11-02 用網頁擷取工具直接讀官方頁面抄下的，**不是憑記憶**。標記 `[文件 D]` = <https://api-docs.deepseek.com/quick_start/pricing>，`[文件 A]` = <https://platform.claude.com/docs/en/about-claude/pricing>（Anthropic 官方價格頁；原網址 docs.claude.com/... 會 302 轉到這裡），`[文件 A-models]` = <https://platform.claude.com/docs/en/about-claude/models/overview>。三個都在 **2026-11-02 讀取**。
> **權威來源**：DeepSeek 以 `[文件 D]` 為準。Anthropic 以 `[文件 A]` 的價格表為準。`[文件 A-models]` 的價格欄只是摘要，與 `[文件 A]` 有出入時以 `[文件 A]` 為準（本次讀到兩者一致）。`[文件 A]` 自己也寫「最新價格請見 claude.com/pricing」；那頁本次沒讀，若日後與 `[文件 A]` 不一致，請以 claude.com/pricing 為準並更新本表。價格會變，App 的價格表要能更新。

**DeepSeek**（分尖峰/離峰，離峰 = 尖峰半價）[文件 D, read 2026-11-02]：

| 模型 | 輸入（快取命中） 離峰/尖峰 | 輸入（未命中） 離峰/尖峰 | 輸出 離峰/尖峰 | 來源 |
|---|---|---|---|---|
| `deepseek-flash` | 0.003 / 0.006 | 0.15 / 0.30 | 0.60 / 1.20 | [文件 D, read 2026-11-02] |
| `deepseek-v4-pro` | 0.022 / 0.044 | 0.66 / 1.32 | 1.98 / 3.96 | [文件 D, read 2026-11-02] |

- 尖峰時段：**週一至週五 01:00–04:00 UTC 與 06:00–10:00 UTC**，中國國定假日除外；其餘時間（含週末、國定假日整天）為離峰。[文件 D, read 2026-11-02]
  - 換算台北時間（UTC+8）：週一至週五 09:00–12:00、14:00–18:00。
  - 「中國國定假日」清單頁面沒給，App 需另外維護，或保守一律按尖峰價估。
- Search / Fetch API：每 1,000 次成功請求 $0.75。經 DeepSeek Harness / Claude Code / Codex 使用原本免費，**自 2026-11-01 00:00（北京時間，UTC+8）起開始按次計費**。[文件 D, read 2026-11-02]
- 頁面寫「$」未註明幣別，依上下文視為美元。[文件 D]

**Anthropic** [文件 A, read 2026-11-02；API id 取自 文件 A-models, read 2026-11-02]：

| 模型（API id） | 輸入 | 輸出 | 快取讀取 | 快取寫入 5m / 1h | 來源 |
|---|---|---|---|---|---|
| Claude Fable 5.1 (`claude-fable-5-1`) | 10 | 50 | 0.25 | 12.50 / 20 | [文件 A, read 2026-11-02] |
| Claude Opus 5.5 (`claude-opus-5-5`) | 4 | 20 | 0.20 | 5 / 8 | [文件 A, read 2026-11-02] |
| Claude Sonnet 5.5 (`claude-sonnet-5-5`) | 2 | 10 | 0.10 | 2.50 / 4 | [文件 A, read 2026-11-02] |
| Claude Haiku 5.5 (`claude-haiku-5-5`) ≤100K prompt | 0.10 | 0.50 | 0.01 | 0.125 / 0.20 | [文件 A, read 2026-11-02] |
| Claude Haiku 5.5 >100K prompt | 0.50 | 2.50 | 0.05 | 0.625 / 1 | [文件 A, read 2026-11-02] |
| Claude Haiku 4.5 (`claude-haiku-4-5`) | 1 | 5 | 0.10 | 1.25 / 2 | [文件 A, read 2026-11-02] |

- Web search 工具：每 1,000 次搜尋 $10，另加 token 費用。[文件 A, read 2026-11-02]
- 快取讀取倍率：Opus 5.5 / Sonnet 5.5 為 0.05 倍輸入價，Fable 5.1 為 0.025 倍，其他 0.1 倍。[文件 A, read 2026-11-02]

### 成本表用的固定模型 id（每個角色一個）

| 角色 | 預設（DeepSeek） | 計價列 | 選用 Claude 時 |
|---|---|---|---|
| Lead（主對話，`agent-default-model`） | `deepseek-flash` | `deepseek-flash` | `claude-sonnet-5-5` |
| Worker（子代理人／隊友，沿用父代理模型） | `deepseek-flash` | `deepseek-flash` | `claude-sonnet-5-5`（同 Lead） |
| Summarization（`compaction-basic.summarizationModel`；標題產生也用對話模型） | `deepseek-flash` | `deepseek-flash` | `claude-sonnet-5-5`（同 Lead；不另設） |
| Search（`web-search-deepseek.model`） | `deepseek-v4-flash`（dsh 預設；舊名，DeepSeek 視同 `deepseek-flash`） | `deepseek-flash` 的 token 價 + 每次搜尋費 | 不適用：搜尋一律走 DeepSeek |

理由：`deepseek-flash` 是 dsh 出廠預設，也是最便宜的現行模型；子代理人預設就沿用父模型。成本表一律用上表「計價列」的 id 查價。舊名 `deepseek-v4-flash` 按 `deepseek-flash` 計價 [文件 D：舊名仍接受並對應 deepseek-flash]。

### 用量欄位（含串流最後一塊）
兩家都是 Anthropic Messages 格式（DeepSeek 經 `/anthropic` 端點）：
- **Anthropic**（<https://platform.claude.com/docs/en/build-with-claude/streaming>）：
  - `message_start.message.usage`：`input_tokens`、`cache_creation_input_tokens`、`cache_read_input_tokens`、`output_tokens`（開頭值）
  - **最後一塊 `message_delta.usage`**：數字是**累計值**（不是增量），含 `output_tokens`，可能也含 `input_tokens`、`cache_*`、`server_tool_use.web_search_requests`。
  - 計費：以最後的累計值為準。
- **DeepSeek**（<https://api-docs.deepseek.com/guides/anthropic_api>）：
  - **最後的 `message_delta.usage` 就是整個請求的用量，不要再加上 `message_start` 的**。
  - `input_tokens` 不含快取命中；`cache_read_input_tokens` = 快取命中；`cache_creation_input_tokens` 目前恆為 0；總輸入 = 三者相加。另有 `output_tokens`。
  - 伺服器端搜尋：`usage.server_tool_use.web_search_requests`、`tool_search`（串流讀最後的 `message_delta.usage.server_tool_use`）。
  - 驗證標頭 `x-api-key` 或 `Authorization: Bearer`；`anthropic-version` 被忽略。
- **App 代理計費建議**：兩家都只看串流中最後一個 `message_delta.usage`（Anthropic 取累計值、DeepSeek 取最終值，結果一樣）；`input_tokens` 以未命中價計、`cache_read_input_tokens` 以命中價計。DeepSeek 要依請求時刻判斷尖峰/離峰。
- dsh 內部的用量結構（`TokenUsage`）：`inputTokens`、`outputTokens`、`totalTokens?`、`cacheReadTokens?`、`cacheWriteTokens?`、`reasoningTokens?`。[文件 `dsh-llm` 型別；實測：假模型回 100/10 + 120/12，介面顯示「用量 242 tok」]

## §Search

- 預設網頁搜尋 = `@deepseek-ai/dsh-web-search-deepseek`（row `web-search-deepseek`，`web.searchProvider: deepseek-official`），模型面工具 `web_search` 由 `dsh-tool-web` 提供（web 版在 preset 裡，`searchTimeoutMs: 60000`）。[實測：模型工具列表有 `web_search`、`web_fetch`]
- **需要的服務**：DeepSeek 自家 API 的原生伺服器端搜尋——每次搜尋是一次完整的 Messages 呼叫（帶 `web_search` server tool，`maxUses` 預設 5），從回傳的結構化搜尋結果區塊取結果。端點 `https://api.deepseek.com/anthropic/v1/messages`，模型 `deepseek-v4-flash`，`maxTokens` 4096。
- **金鑰**：要。沿用 `DEEPSEEK_API_KEY`（`apiKeyEnv`），或 DeepSeek 帳號登入 token（優先）。不需要第三方金鑰。缺金鑰時錯誤 `WEB_PROVIDER_CREDENTIAL_MISSING`。
- **端點獨立**：`baseURL` → `$DEEPSEEK_SEARCH_BASE_URL` → 官方預設；**不吃 `DEEPSEEK_BASE_URL`**。App 代理要另外設這個環境變數。
- **費用**：一次搜尋 = 一次模型回合的 token 費 +（依 DeepSeek 價格頁）搜尋請求費；自 2026-11-01 00:00（北京時間）起 Harness 使用也開始按次計費（每 1,000 次 $0.75）。[文件 <https://api-docs.deepseek.com/quick_start/pricing>, read 2026-11-02]
- **無頭瀏覽器**：**不需要**。`web_fetch` 走 `dsh-web-fetch-http`（純 HTTP 抓取 + 轉文字，`fetchMaxOutputChars` 200000）；搜尋在 DeepSeek 伺服器端執行。瀏覽器自動化（playwright/chrome-devtools MCP、stagehand）是另外的 experimental 插件，預設沒開。
- 另有官方替代搜尋提供者：`dsh-web-deepseek-api`（DeepSeek Search/Fetch API，同金鑰，不耗模型回合）、`dsh-web-search-exa`（`EXA_API_KEY`）、`dsh-web-search-perplexity`（`PERPLEXITY_API_KEY`）。
