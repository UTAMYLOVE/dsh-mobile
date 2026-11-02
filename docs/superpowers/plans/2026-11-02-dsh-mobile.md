# DSH Mobile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一個 Android App，在手機上原封不動地跑官方 DeepSeek Harness（dsh），外加 OpenClaw 風格記憶、口袋裡也會跑的心跳，以及粉紅色（櫻花淺粉）介面。

**Architecture:** Kotlin 外殼 App 內建 proot + Debian + Node.js 24 的 Linux 環境，在裡面跑官方 `@deepseek-ai/dsh`，用 WebView 顯示它的網頁介面。App 內一個本機閘道（LocalGateway）負責：注入 API 金鑰（dsh 永遠拿不到金鑰）、統計花費、接收通知請求。兩個自寫 dsh 插件（記憶、通知）和一個 Node 前置載入檔（通行碼閘門）外掛上去，不改官方原始碼。

**Tech Stack:** Kotlin、Jetpack Compose（設定頁）、WorkManager、OkHttp/MockWebServer、Ktor server CIO（閘道）、JUnit5/Robolectric；TypeScript + pnpm + vitest（插件）；proot（Termux 版，打包為 jniLibs）、Debian bookworm rootfs、Node.js 24；GitHub Actions（含 Android 模擬器）。

**Spec:** `docs/superpowers/specs/2026-11-02-dsh-mobile-design.md`

## Global Constraints

- 新的 GitHub **私人**倉庫 `dsh-mobile`，與 openclaw-v4 完全分開；本計畫與規格書建倉後搬入 `docs/`。
- 目標裝置 Galaxy S25 Ultra、Android 17；`minSdk 34`（Android 14，可在開發人員選項關子行程限制）、`targetSdk` 用最新穩定版。
- Node.js 24.x；官方 dsh 版本在 Task 0 選定一個已驗證版號並寫死為初始版本 `DSH_PINNED_VERSION`。
- **官方 dsh 原始碼一行不改**；只用插件、設定檔、Node 前置載入、WebView CSS 注入。
- 權限只能有：`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`WAKE_LOCK`、`POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`RECEIVE_BOOT_COMPLETED`、`REQUEST_INSTALL_PACKAGES`（外殼更新，由系統逐次詢問）、`ACCESS_NETWORK_STATE`（WorkManager「有網路才跑」條件需要，只能看有沒有網路）。共 9 個。CI 有一個測試檢查合併後的 manifest 不多不少。
- 前景服務類型 `specialUse`（不用 `dataSync`）。
- 金鑰：只存在 Android Keystore 保護的儲存；**不進環境變數、設定檔、記錄檔**；dsh 設定裡的金鑰欄位填假值 `dshm-proxy`。
- dsh 與閘道只綁 `127.0.0.1`；所有請求需帶通行碼（每次啟動重新產生，32 bytes 隨機，hex）。
- 環境變數（傳給 dsh）：`DSHM_TOKEN`、`DSHM_GATEWAY`（`http://127.0.0.1:<port>/<token>`）、`DSHM_WORKSPACE=/root/workspace`、`NODE_OPTIONS=--require /opt/dshm/auth-gate.cjs`。
- 心跳：WorkManager 週期 45 分鐘（設定頁可調 30～60）、需網路；只做記憶整理（做夢）。
- 費用：時區 `Asia/Taipei` 換日；每日超過 US$5 通知一次、不暫停；硬上限預設關閉。
- dsh 連續當掉 3 次（10 分鐘內）停止重開並通知。
- dsh 版本最多保留 2 個（目前 + 上一個）。
- 子代理人預設：子行程限制已關 → 同時 16 個；未關或未知 → 6 個；分派深度 2；團隊人數 8。
- 配色「櫻花淺粉」：底 `#fff5f8`、主色 `#d6336c`、我方氣泡 `#f06595`、標籤底 `#ffd6e3`／字 `#c2255c`、框線 `#f3c6d3`、文字 `#5a2a3a`。
- App 名稱「DSH Mobile」，套件名 `app.dshmobile`。
- APK 簽章金鑰只放 GitHub Secrets（宇哥自己填）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；公開發佈倉庫寫入權杖 `RELEASES_TOKEN`。
- 公開下載倉庫名稱由 gradle 屬性 `dshm.releasesRepo`（`owner/dsh-mobile-releases`）提供，不寫死。

## Review Focus

1. **路徑跳脫**：`memory_get`、匯出收到 `../../`、絕對路徑或符號連結時要拒絕，不能讀到工作區外 → Task 6、Task 11d 測試。
2. **串流回應**：模型回應是 SSE 串流時，閘道必須逐塊轉送（不能等整包），且串流中途斷線也要記到已收到的用量 → Task 5 測試。
3. **心跳撞上正在使用**：App 在前景、dsh 正在跑時心跳觸發，不能再冷啟動第二個 dsh，也不能和對話同時寫壞 `MEMORY.md` → Task 6（寫入鎖）、Task 10 測試。
4. **更新途中失敗**（空間不足、網路斷、新版裝到一半）：目前版本必須照常可用，不留半成品 → Task 9 測試。
5. **只有 DeepSeek 金鑰**：沒有 Claude 金鑰時，代理人設定不能引用 Claude 模型，任何角色都要能跑 → Task 11b 測試。

---

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

### Task 1: 倉庫骨架與 CI

**Files:**
- Create: `android/`（Gradle Kotlin DSL、`app` 模組、`app.dshmobile`）
- Create: `plugins/package.json`、`plugins/pnpm-workspace.yaml`、`plugins/vitest.config.ts`
- Create: `.github/workflows/ci.yml`
- Create: `android/app/src/test/kotlin/app/dshmobile/ManifestPermissionsTest.kt`

**Interfaces:**
- Produces: 指令 `./gradlew :app:testDebugUnitTest`、`pnpm -C plugins test`；CI 在每次 push 跑這兩個。`BuildConfig.ROOTFS_VERSION: String`、`BuildConfig.DSH_PINNED_VERSION: String`、`BuildConfig.RELEASES_REPO: String`。

- [ ] **Step 1: 寫失敗測試** — 讀合併後的 manifest（Robolectric `PackageManager`），斷言權限集合恰好等於 Global Constraints 的 9 個：

```kotlin
@Test fun permissionsAreExactlyTheAllowedSet() {
    val perms = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions!!.toSet()
    assertEquals(ALLOWED_PERMISSIONS, perms)   // 9 個，常數定義在測試裡
}
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL。
- [ ] **Step 3:** 建 manifest 宣告這 9 個；WorkManager 帶進的權限若超出，用 `tools:node="remove"` 移除。
- [ ] **Step 4:** 測試 PASS；`pnpm -C plugins test` 在空 workspace 下 PASS。
- [ ] **Step 5:** CI 綠燈；Commit `chore: scaffold android app, plugins workspace, CI`。

---

### Task 2: Rootfs 打包腳本

**Files:**
- Create: `rootfs/build-rootfs.sh`
- Create: `rootfs/smoke-test.sh`
- Create: `rootfs/auth-gate.cjs` 的放置位置 `/opt/dshm/`（檔案本身由 Task 4 產生，這裡只負責複製）
- Modify: `.github/workflows/ci.yml`（加 rootfs job）

**Interfaces:**
- Consumes: Task 0 §Pin。
- Produces: `rootfs/build-rootfs.sh <arm64|x86_64> <out.tar.xz>` 產生的 rootfs 內含：
  - Debian bookworm 最小系統、`/usr/local/bin/node`（24.x）
  - `/opt/dsh/<DSH_PINNED_VERSION>/`（`npm install --prefix` 安裝）、`/opt/dsh/current` → 該版本的符號連結
  - `/opt/dshm/auth-gate.cjs`、`/opt/dshm/plugins/{memory,notify}`（建置產物）
  - `/root/workspace/`（空）
  - `/etc/dshm-rootfs-version`（內容 = `ROOTFS_VERSION`）

- [ ] **Step 1:** 寫 `smoke-test.sh <tar>`：解開後用 `proot`（CI 上配 qemu-user-static）執行並檢查：`node --version` 以 `v24.` 開頭；`/opt/dsh/current/node_modules/.bin/dsh --version` 等於 `DSH_PINNED_VERSION`；`/etc/dshm-rootfs-version` 存在。
- [ ] **Step 2:** 在還沒有 build 腳本時跑 smoke test → FAIL。
- [ ] **Step 3:** 寫 `build-rootfs.sh`（debootstrap 或 proot-distro 公開 tarball 二擇一，實作者決定；裁掉 man、doc、locale）。
- [ ] **Step 4:** CI 兩種架構都 build + smoke test PASS；壓縮後大小印在 job summary。
- [ ] **Step 5:** Commit `build: rootfs with node 24 and pinned dsh`。

---

### Task 3: 解壓與 proot 執行器

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/RootfsInstaller.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/ProotRunner.kt`
- Create: `android/app/src/main/jniLibs/{arm64-v8a,x86_64}/libproot.so`、`libproot-loader.so`（Termux proot 建置產物，CI 下載後放入；Android 10+ 只能從 nativeLibraryDir 執行檔案，所以要偽裝成 .so，並設 `extractNativeLibs=true`）
- Test: `android/app/src/test/kotlin/app/dshmobile/runtime/RootfsInstallerTest.kt`、`ProotRunnerTest.kt`

**Interfaces:**
- Produces:
  - `class RootfsInstaller(open: (String) -> InputStream, filesDir: File, expectedVersion: String)`；`fun isInstalled(): Boolean`；`fun install(onProgress: (percent: Int) -> Unit)`；rootfs 在 `filesDir/rootfs`。
  - `class ProotRunner(nativeLibDir: File, rootfsDir: File, tmpDir: File)`；`fun command(args: List<String>, env: Map<String, String>): ProcessBuilder`。

- [ ] **Step 1: 寫失敗測試**

```kotlin
@Test fun installIsIdempotentAndVersioned() {
    installer.install {}
    assertTrue(installer.isInstalled())
    File(filesDir, "rootfs/etc/dshm-rootfs-version").writeText("old")
    assertFalse(installer.isInstalled())          // 版本不符要重裝
}
@Test fun interruptedInstallLeavesNoHalfRootfs() {
    // open() 在一半丟 IOException
    assertThrows<IOException> { brokenInstaller.install {} }
    assertFalse(brokenInstaller.isInstalled())
    assertFalse(File(filesDir, "rootfs").exists()) // 先解到 rootfs.tmp，成功才改名
}
@Test fun commandUsesBundledProotAndLoader() {
    val pb = runner.command(listOf("node", "-v"), mapOf("A" to "1"))
    assertEquals(File(nativeLibDir, "libproot.so").path, pb.command()[0])
    assertEquals(File(nativeLibDir, "libproot-loader.so").path, pb.environment()["PROOT_LOADER"])
    assertTrue(pb.command().containsAll(listOf("-r", rootfsDir.path, "-0", "-w", "/root/workspace")))
    assertEquals("1", pb.environment()["A"])
}
```

- [ ] **Step 2:** 跑 → FAIL。
- [ ] **Step 3:** 實作；`install` 解到 `rootfs.tmp` 再原子改名。proot 參數另含 `--link2symlink`、綁 `/proc`、`/dev`、`/sys`。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: rootfs installer and proot runner`。

---

### Task 4: 通行碼閘門（Node 前置載入）與 SessionToken

**Files:**
- Create: `plugins/auth-gate/auth-gate.cjs`、`plugins/auth-gate/auth-gate.test.ts`
- Create: `android/app/src/main/kotlin/app/dshmobile/security/SessionToken.kt`
- Test: `android/app/src/test/kotlin/app/dshmobile/security/SessionTokenTest.kt`

**Interfaces:**
- Consumes: Task 0 §Server。
- Produces: `auth-gate.cjs` 讀 `DSHM_TOKEN`，包住**所有** `http.Server` 的 `request` 與 `upgrade` 事件；放行條件：cookie `dshm_token=<token>` 或標頭 `x-dshm-token: <token>`。`DSHM_TOKEN` 未設時程序直接以錯誤結束（寧可起不來也不裸奔）。
  `class SessionToken(val value: String)`；`companion fun generate(): SessionToken`（64 hex 字元）。

選用前置載入而非插件：它涵蓋 dsh 和其他插件開的每一個伺服器，也不受 dsh 改版影響（規格 §6 允許的做法）。

- [ ] **Step 1: 寫失敗測試**（vitest，用 `node --require` 起一個普通 http + ws 伺服器）

```ts
it('rejects request without token', async () => expect((await get('/')).status).toBe(403))
it('accepts cookie token', async () => expect((await get('/', { cookie: `dshm_token=${T}` })).status).toBe(200))
it('accepts header token', async () => expect((await get('/', { 'x-dshm-token': T })).status).toBe(200))
it('destroys websocket upgrade without token', async () => expect(await wsConnect()).toBe('closed'))
it('rejects wrong token of same length', async () => expect((await get('/', { cookie: `dshm_token=${'0'.repeat(64)}` })).status).toBe(403))
it('exits when DSHM_TOKEN is missing', async () => expect(await runWithoutToken()).not.toBe(0))
```

Kotlin：`generate()` 兩次結果不同、長度 64、只含 `[0-9a-f]`。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作；比對用 `crypto.timingSafeEqual`。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: auth gate preload and session token`。

---

### Task 5: 本機閘道（金鑰注入、花費、通知入口）

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/gateway/LocalGateway.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/gateway/CostMeter.kt`、`PriceTable.kt`、`UsageParser.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/security/KeyVault.kt`（介面 + `AndroidKeyVault`）
- Test: `android/app/src/test/kotlin/app/dshmobile/gateway/LocalGatewayTest.kt`、`CostMeterTest.kt`、`UsageParserTest.kt`

**Interfaces:**
- Consumes: `SessionToken`（Task 4）；Task 0 §Providers（用量欄位、價格）。
- Produces:
  - `enum class Provider { DEEPSEEK, ANTHROPIC, SEARCH }`
  - `interface KeyVault { fun get(p: Provider): String?; fun put(p: Provider, key: String); fun clear(p: Provider) }`；`class AndroidKeyVault(context: Context) : KeyVault`
  - `data class Usage(val inputTokens: Long, val outputTokens: Long)`；`object UsageParser { fun fromJson(p: Provider, body: String): Usage?; fun fromSseEvent(p: Provider, data: String): Usage? }`
  - `class CostMeter(store: SharedPreferences, prices: PriceTable, zone: ZoneId = ZoneId.of("Asia/Taipei"), clock: Clock)`；`fun record(p: Provider, model: String, u: Usage)`；`fun todayUsd(): Double`；`fun consumeAlert(): Boolean`（當日首次超過 `alertUsd` 回 true，之後當日都回 false）；`fun isBlocked(): Boolean`（`hardCapUsd != null && todayUsd() >= hardCapUsd`）；屬性 `alertUsd = 5.0`、`hardCapUsd: Double? = null`
  - `data class NotifyRequest(val title: String, val body: String, val sessionId: String?)`
  - `class LocalGateway(token: SessionToken, vault: KeyVault, meter: CostMeter, upstreams: Map<Provider, HttpUrl>, onNotify: (NotifyRequest) -> Unit)`；`fun start(): Int`（綁 `127.0.0.1` 隨機埠，回傳埠號）；`fun stop()`
  - 路由：`/{token}/deepseek/**` → `https://api.deepseek.com/**`（加 `Authorization: Bearer <key>`）；`/{token}/anthropic/**` → `https://api.anthropic.com/**`（加 `x-api-key`）；`/{token}/search/**`（若 §Search 需要金鑰）；`POST /{token}/notify`（JSON → `onNotify`）。token 錯 → 403；`isBlocked()` → 429 並附 JSON `{"error":"dshm_daily_cap"}`。
  - 進來請求裡原有的 `Authorization`／`x-api-key`（假值 `dshm-proxy`）一律丟掉再換上真金鑰。

- [ ] **Step 1: 寫失敗測試**（MockWebServer 當上游）

```kotlin
@Test fun injectsDeepseekKeyAndStripsFake()          // 上游收到 Bearer real-key，沒有 dshm-proxy
@Test fun wrongTokenIs403AndNeverCallsUpstream()
@Test fun streamsSseChunkByChunk()                  // 上游分 3 塊、每塊間隔 200ms；客戶端在第 1 塊後 <150ms 內收到資料
@Test fun countsUsageFromFinalSseChunk()            // meter.todayUsd() > 0
@Test fun countsPartialUsageWhenStreamAborts()      // 上游送出含 usage 的塊後斷線，用量仍入帳
@Test fun dailyCapReturns429WhenEnabled()
@Test fun notifyRouteInvokesCallback()              // 收到 NotifyRequest("t","b","s1")
@Test fun missingKeyReturns401WithReadableError()   // {"error":"dshm_missing_key","provider":"ANTHROPIC"}
```

`CostMeterTest`：台北時間 23:59 記一筆、00:01 `todayUsd()` 歸零；`consumeAlert()` 超過 5.0 時第一次 true、第二次 false、隔天再超過又 true。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作（Ktor CIO server + OkHttp client，回應 body 以串流轉送）。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: local gateway with key injection, cost meter, notify route`。

---

### Task 6: 記憶插件

**Files:**
- Delete: `plugins/hello/`
- Create: `plugins/memory/src/store.ts`、`src/dream.ts`、`src/index.ts`、`package.json`
- Test: `plugins/memory/test/store.test.ts`、`test/dream.test.ts`

**Interfaces:**
- Consumes: Task 0 §Plugins、§Memory。
- Produces:
  - `class MemoryStore(root: string, now: () => Date = () => new Date())`
    - `loadStartupContext(): string`：`MEMORY.md` + `USER.md` + 今天與昨天的 `memory/YYYY-MM-DD.md`（缺的略過），以 `## <檔名>` 分段。
    - `search(query: string, limit = 10): { path: string; line: number; text: string }[]`：關鍵字（空白分詞、全部命中、不分大小寫）搜尋 `MEMORY.md`、`USER.md`、`memory/*.md`，新檔優先。
    - `get(rel: string, from?: number, lines?: number): string`
    - `append(target: 'daily' | 'long' | 'user', text: string): void`（daily 寫今天的檔，前綴 `- HH:mm `）
    - `resolveInside(rel: string): string`（跳脫、絕對路徑、符號連結出界 → 丟 `MemoryPathError`）
    - 所有寫入經過同一把檔案鎖（`memory/.lock`，`proper-lockfile`）。
  - `dream(store: MemoryStore, llm: (prompt: string) => Promise<string>, statePath: string): Promise<{ promoted: string[]; reminders: { title: string; body: string }[] }>`：讀 `memory/.dream-state.json` 記錄的上次處理日之後的每日筆記，要求模型回 JSON `{promote: string[], remind: {title, body}[]}`；`promote` 以 `### YYYY-MM-DD 整理` 區塊附加到 `MEMORY.md`；更新 state；模型回非 JSON → 不寫任何東西、不更新 state、丟 `DreamParseError`。
  - dsh 掛載（`index.ts`）：工具 `memory_search`、`memory_get`、`memory_write`；對話開始注入 `loadStartupContext()`；壓縮前鉤子（§Plugins 有才做）提示先存重要內容；路由 `GET /dshm/health/memory` → `{"ok":true}`、`POST /dshm/heartbeat` → 呼叫 `dream`（llm 用 dsh 目前設定的工作模型）並回 `{promoted, reminders}`。
  - 工作區根目錄：`process.env.DSHM_WORKSPACE`；`MEMORY.md` 與 dsh 原生讀取的是同一個檔（照 §Memory 對齊路徑）。

- [ ] **Step 1: 寫失敗測試**

```ts
it('startup context has MEMORY, USER, today and yesterday only', ...)  // 前天的不在內
it('search finds all-term matches, newest first', ...)
it('get rejects ../ and absolute paths and outward symlinks', () => {
  expect(() => s.get('../secret')).toThrow(MemoryPathError)
  expect(() => s.get('/etc/passwd')).toThrow(MemoryPathError)
  expect(() => s.get('memory/link-out.md')).toThrow(MemoryPathError)
})
it('concurrent appends never lose lines', async () => { /* 50 個並行 append，最後 50 行都在 */ })
it('dream promotes into MEMORY.md and advances state', ...)
it('dream on non-JSON writes nothing and keeps state', ...)
it('dream with no new notes does not call llm', ...)
```

- [ ] **Step 2:** `pnpm -C plugins test` → FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS；在電腦上用 Node 24 + dsh 載入插件，`curl -H "x-dshm-token: …" /dshm/health/memory` 回 ok。
- [ ] **Step 5:** Commit `feat: openclaw-style memory plugin`。

---

### Task 7: 通知插件

**Files:**
- Create: `plugins/notify/src/index.ts`、`src/send.ts`、`package.json`
- Test: `plugins/notify/test/send.test.ts`

**Interfaces:**
- Consumes: 閘道 `POST /{token}/notify`（Task 5）；Task 0 §Plugins。
- Produces: `sendNotify(gateway: string, req: { title: string; body: string; sessionId?: string }): Promise<void>`（非 2xx 丟錯）；工具 `notify_user(title, body)`，自動帶入目前對話 id；路由 `GET /dshm/health/notify` → `{"ok":true}`。標題截 60 字、內文截 500 字。

- [ ] **Step 1:** 測試：對假閘道送出後收到正確 JSON；閘道回 500 時丟錯；超長標題被截到 60 字。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: notify plugin`。

---

### Task 8: dsh 程序控制與常駐服務

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/service/CrashPolicy.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/service/DshController.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/service/DshService.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/notify/Notifier.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/HealthCheck.kt`
- Test: `CrashPolicyTest.kt`、`DshControllerTest.kt`、`HealthCheckTest.kt`

**Interfaces:**
- Consumes: `ProotRunner`、`RootfsInstaller`（Task 3）、`SessionToken`（Task 4）、`LocalGateway`、`KeyVault`、`CostMeter`（Task 5）。
- Produces:
  - `class CrashPolicy(max: Int = 3, windowMs: Long = 600_000, clock: () -> Long)`；`fun onCrash(): Decision`（`RESTART` / `GIVE_UP`）；`fun reset()`
  - `class DshController(...)`（單例，服務與心跳共用）
    - `suspend fun ensureRunning(): RunningDsh`（已在跑就直接回傳，**絕不起第二個**）；`data class RunningDsh(val dshPort: Int, val gatewayPort: Int, val token: SessionToken)`
    - `fun stop()`；`val isRunning: StateFlow<Boolean>`
    - 啟動：產生 token → 起閘道 → 帶 Global Constraints 的環境變數用 `ProotRunner` 起 `dsh web`（埠依 §Server，預設 3080）→ 等 `HealthCheck` 通過。
  - `class HealthCheck(http: OkHttpClient)`；`suspend fun check(port: Int, token: SessionToken): Boolean`：`GET /`（帶 cookie）200，且 `/dshm/health/memory`、`/dshm/health/notify` 都回 ok；逾時 60 秒。
  - `class DshService : Service`：前景服務（`specialUse`），App 在前景時持有 `DshController`；行程死掉 → `CrashPolicy`；`GIVE_UP` 時發通知，兩個動作「退版」「查看錯誤記錄」。
  - `class Notifier(context)`：`fun show(req: NotifyRequest)`，點擊開 `MainActivity` 並帶 `sessionId`；`fun showCostAlert(usd: Double)`；`fun showCrashGiveUp()`。

- [ ] **Step 1:** 測試：`CrashPolicy` 10 分鐘內第 3 次回 `GIVE_UP`、跨出視窗重算；`DshController` 同時呼叫兩次 `ensureRunning()` 只啟動一次（假 runner 計次）；`HealthCheck` 在 notify 健康路由 404 時回 false。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: dsh controller, foreground service, crash policy`。

---

### Task 9: 版本更新、健康檢查、退版

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/DshVersions.kt`
- Test: `DshVersionsTest.kt`

**Interfaces:**
- Consumes: `ProotRunner`、`DshController`、`HealthCheck`（Task 8）。
- Produces:
  - `class DshVersions(optDsh: File, runner: ProotRunner)`
    - `fun current(): String`；`fun previous(): String?`
    - `suspend fun latestAvailable(): String`（`npm view @deepseek-ai/dsh version`）
    - `suspend fun install(version: String): Result<Unit>`：裝到 `/opt/dsh/<version>.tmp`，成功才改名；失敗刪掉 tmp。
    - `fun switchTo(version: String)`：原子替換 `current` 連結，記下 `previous`；刪掉兩版以外的舊版。
    - `fun rollback(): Boolean`
  - 更新流程（給 Task 11b 呼叫）：`suspend fun updateWithAutoRollback(controller: DshController, health: HealthCheck): UpdateOutcome`（`UPDATED(v)` / `ROLLED_BACK(v, reason)` / `FAILED(reason)` / `UP_TO_DATE`）。

- [ ] **Step 1:** 測試（假 runner 模擬 npm）：

```kotlin
@Test fun installFailureLeavesCurrentUntouchedAndNoTmp()   // 模擬空間不足
@Test fun keepsOnlyTwoVersions()
@Test fun unhealthyNewVersionRollsBackAutomatically()      // 結果 ROLLED_BACK，current 回到舊版
@Test fun manualRollbackSwapsCurrentAndPrevious()
@Test fun sameVersionIsUpToDate()
```

- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: dsh versions with health check and rollback`。

---

### Task 10: 心跳

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/heartbeat/HeartbeatWorker.kt`、`HeartbeatScheduler.kt`
- Test: `HeartbeatWorkerTest.kt`（`androidx.work:work-testing`）

**Interfaces:**
- Consumes: `DshController`、`Notifier`、`CostMeter`；記憶插件 `POST /dshm/heartbeat`。
- Produces: `object HeartbeatScheduler { fun schedule(context: Context, intervalMin: Int = 45) }`（`PeriodicWorkRequest`、`NetworkType.CONNECTED`、`ExistingPeriodicWorkPolicy.UPDATE`，30～60 以外的值夾回範圍）。`HeartbeatWorker : CoroutineWorker`：
  1. 記下 dsh 原本是否在跑；`ensureRunning()`；
  2. 呼叫 `/dshm/heartbeat`；每個 reminder → `Notifier.show`；
  3. 原本沒在跑 → `stop()`；原本在跑 → 不動；
  4. 任何錯誤 → `Result.success()`（本次跳過，不重試）並寫錯誤記錄；
  5. 用 `setForeground`（`specialUse`）讓冷啟動在背景合法。

- [ ] **Step 1:** 測試：dsh 已在跑時不會冷啟動、結束後仍在跑；dsh 沒在跑時結束後被關掉；閘道回錯時結果為 success 且沒發通知；兩個 reminder 發兩則通知；`schedule(context, 5)` 實際排 30 分鐘。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: heartbeat worker`。

---

### Task 11a: 主畫面（WebView + 櫻花配色）

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/MainActivity.kt`
- Create: `android/app/src/main/assets/theme-sakura.css`
- Create: `android/app/src/main/res/values/colors.xml`、`themes.xml`、桌面圖示（粉紅）
- Test: `android/app/src/androidTest/kotlin/app/dshmobile/ui/MainActivityTest.kt`

**Interfaces:**
- Consumes: `DshController`、`Notifier` 的 `sessionId` extra；Task 0 §UI。
- Produces: 開啟時 `ensureRunning()` → 用 `CookieManager` 設 `dshm_token` → 載入 `http://127.0.0.1:<dshPort>/`（帶 `sessionId` 時依 §UI 的對話網址）→ 頁面載完注入 `theme-sakura.css`（只覆蓋顏色，用 §UI 的變數；顏色值照 Global Constraints）。WebView 只允許導覽到 `127.0.0.1`，外部連結交給系統瀏覽器。檔案下載交給 Task 11d 的 `Exporter`。

- [ ] **Step 1:** 儀器測試：開啟後 WebView 載到 dsh 頁面（標題或 DOM 標記依 §UI）；頁面 `getComputedStyle(document.body).backgroundColor` 為 `rgb(255, 245, 248)`；點外部連結不在 WebView 內開。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 在 x86_64 模擬器 PASS（Task 13 的 CI 也會跑）。
- [ ] **Step 5:** Commit `feat: main webview with sakura theme`。

---

### Task 11b: 設定頁與代理人設定

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/SettingsScreen.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/config/AgentConfigWriter.kt`、`AgentSettings.kt`
- Test: `AgentConfigWriterTest.kt`

**Interfaces:**
- Consumes: `KeyVault`、`CostMeter`、`DshVersions.updateWithAutoRollback`、`PhantomProcessDetector`（Task 11c）、`HeartbeatScheduler`、Task 0 §Config、§Providers。
- Produces:
  - `data class AgentSettings(val maxConcurrent: Int, val maxDepth: Int = 2, val teamSize: Int = 8, val leadModel: String, val workerModel: String)`；`fun defaults(phantom: PhantomState, hasClaudeKey: Boolean): AgentSettings`（`DISABLED` → 16，否則 6；沒 Claude 金鑰 → lead 與 worker 都是 DeepSeek 模型，名稱依 §Providers）
  - `class AgentConfigWriter(rootfsDir: File)`；`fun write(s: AgentSettings, gatewayBase: String)`：寫 dsh 設定檔（§Config 位置），提供者 base URL = `<gatewayBase>/deepseek`、`<gatewayBase>/anthropic`，金鑰欄 = `dshm-proxy`；只有存在 Claude 金鑰時才寫入 Anthropic 提供者。
  - 設定頁區塊：金鑰（DeepSeek、Claude、搜尋—若 §Search 需要）；代理人（同時數、深度、團隊、總指揮模型、子代理人模型）；預算（今日花費、通知門檻、硬上限開關）；心跳間隔；dsh 版本（目前、上一版、檢查更新、手動退版）；子行程限制狀態；匯出錯誤記錄、匯出記憶備份。

- [ ] **Step 1:** 測試：

```kotlin
@Test fun noClaudeKeyMeansNoAnthropicReferences()   // 輸出設定檔不含 "anthropic"
@Test fun configNeverContainsRealKey()              // vault 放 "sk-real"，設定檔不含 "sk-real"，只含 "dshm-proxy"
@Test fun defaultsDependOnPhantomState()            // DISABLED→16, ENABLED→6, UNKNOWN→6
@Test fun baseUrlsPointAtGateway()
```

- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作（Compose，配色同 Global Constraints）。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: settings screen and agent config writer`。

---

### Task 11c: 首次啟動引導與子行程限制偵測

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/OnboardingScreen.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/system/PhantomProcessDetector.kt`
- Test: `PhantomProcessDetectorTest.kt`

**Interfaces:**
- Produces: `enum class PhantomState { DISABLED, ENABLED, UNKNOWN }`；`class PhantomProcessDetector(resolver: ContentResolver)`；`fun state(): PhantomState`（讀 `Settings.Global` 的 `settings_enable_monitor_phantom_procs`：`"false"` → DISABLED、`"true"` → ENABLED、讀不到 → UNKNOWN）。
- 引導步驟（依序，可略過但會記住）：① 通知權限 ② 忽略電池最佳化 ③ 子行程限制：說明為什麼、按鈕開啟「開發人員選項」（`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`；開發人員選項還沒開時，先教他到「關於手機 → 軟體資訊」連點版本號碼 7 次）、回來後重新偵測並顯示結果 ④ 輸入 DeepSeek 金鑰 ⑤ 解壓 rootfs（進度條）。**App 不自行修改任何系統設定。**

- [ ] **Step 1:** 測試：三種設定值對應三種狀態；設定不存在 → UNKNOWN。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: onboarding and phantom process detector`。

---

### Task 11d: 匯出與備份

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/export/Exporter.kt`、`WorkspacePaths.kt`
- Create: `android/app/src/main/res/xml/file_paths.xml`（FileProvider，只開 cache/export）
- Test: `WorkspacePathsTest.kt`、`ExporterTest.kt`

**Interfaces:**
- Produces: `object WorkspacePaths { fun resolveInside(root: File, rel: String): File? }`（跳脫／絕對路徑／符號連結出界 → null）；`class Exporter(context)`：`fun share(file: File)`（複製到 cache/export → FileProvider → 系統分享選單）；`fun memoryBackupZip(workspace: File): File`（`MEMORY.md`、`USER.md`、`memory/`，不含 `.lock`）；`fun errorLogZip(): File`（錯誤記錄，匯出前過濾任何像金鑰的字串：`sk-` 開頭或長度 ≥ 32 的英數串換成 `***`）。WebView 的下載事件與 dsh 介面的匯出動作都走 `share`。

- [ ] **Step 1:** 測試：`resolveInside` 擋 `../`、`/etc/passwd`、出界符號連結；備份 zip 含三樣、不含 `.lock`；錯誤記錄裡的 `sk-abc…` 被換成 `***`。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: export and memory backup`。

---

### Task 12: 外殼 App 更新與發佈流程

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/update/ApkUpdateChecker.kt`
- Create: `.github/workflows/release.yml`
- Test: `ApkUpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.RELEASES_REPO`、`Notifier`。
- Produces: `class ApkUpdateChecker(http: OkHttpClient, repo: String, currentVersionCode: Int)`；`suspend fun latest(): ReleaseInfo?`（`GET https://api.github.com/repos/<repo>/releases/latest`；tag 格式 `v<versionName>+<versionCode>`；只在 versionCode 較大時回傳）；`data class ReleaseInfo(val versionCode: Int, val apkUrl: String, val sha256: String)`；下載到 cache、**比對 sha256 不符就刪掉並放棄**、交給系統安裝程式。每天檢查一次（掛在心跳 worker 裡、每日第一次心跳時）。
- `release.yml`：tag `v*` 觸發 → 跑全部測試 → 打包兩個 ABI 的 rootfs → 簽章 APK（Global Constraints 的 Secrets）→ 用 `RELEASES_TOKEN` 發佈到公開倉庫的 Release，附 APK 與 `.sha256`。

- [ ] **Step 1:** 測試（MockWebServer）：較新版回 ReleaseInfo；同版回 null；tag 格式錯回 null；sha256 不符時檔案被刪除且不觸發安裝。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** PASS；在 fork 上用假 Secrets 跑一次 release workflow 到簽章步驟前。
- [ ] **Step 5:** Commit `feat: shell app self-update and release workflow`。

---

### Task 13: 模擬器端到端測試（CI）

**Files:**
- Create: `android/app/src/androidTest/kotlin/app/dshmobile/E2eTest.kt`
- Modify: `.github/workflows/ci.yml`（加 `reactivecircus/android-emulator-runner`，x86_64、API 34 以上，用 x86_64 rootfs）

**Interfaces:**
- Consumes: 全部前面任務。DeepSeek 請求在 E2E 中指向 MockWebServer（閘道 upstream 可注入），CI 不需要真金鑰、不花錢。

- [ ] **Step 1:** 寫測試，規格 §9 的五項逐一對應：
  1. 啟動後 WebView 打得開 dsh 介面；
  2. 從測試行程直接打 `127.0.0.1:<dshPort>/`（不帶通行碼）→ 403；
  3. 透過 dsh 對話叫代理人寫一條記憶（假模型回固定工具呼叫）→ `MEMORY.md` 或今日筆記出現該行；
  4. 手動觸發 `HeartbeatWorker`（假模型回一個 reminder）→ 系統通知列出現該通知；
  5. 假 npm 讓「新版」健康檢查失敗 → `updateWithAutoRollback` 回 `ROLLED_BACK`，介面仍可用。
- [ ] **Step 2:** 本地模擬器跑 → 未整合前 FAIL。
- [ ] **Step 3:** 修到全綠。
- [ ] **Step 4:** CI 上 `./gradlew :app:connectedDebugAndroidTest` PASS。
- [ ] **Step 5:** Commit `test: emulator end-to-end suite`。

---

## 任務順序與相依

Task 0 → 1 → 2 → 3 → 4 → 5 → (6, 7 可並行) → 8 → 9 → 10 → (11a, 11b, 11c, 11d) → 12 → 13。
11b 依賴 11c 的 `PhantomState`；其餘 11x 互不相依。
