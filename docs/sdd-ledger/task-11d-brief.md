### Task 11d：匯出與備份

計畫原文 Task 11d，**加上 Task 8 的 ErrorLog（帳本 Ruling）**。本文與計畫原文衝突時以本文為準。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/export/Exporter.kt`、`WorkspacePaths.kt`、`SecretScrubber.kt`
- Create: `android/app/src/main/res/xml/file_paths.xml`（FileProvider，只開 `cache/export/`）
- Modify: `android/app/src/main/AndroidManifest.xml`：宣告 `androidx.core.content.FileProvider`（authority `${applicationId}.export`、`exported=false`、`grantUriPermissions=true`、meta-data 指向 `@xml/file_paths`）。**不得新增 uses-permission**（不需要任何儲存權限）。
- Test: `android/app/src/test/kotlin/app/dshmobile/export/WorkspacePathsTest.kt`、`ExporterTest.kt`、`SecretScrubberTest.kt`

**Consumes（先讀）：** `log/ErrorLog.kt`（`file()` → `filesDir/logs/error.log`，輪替檔 `error.log.1`）、`DshMobileApp.kt`。工作區在 host 端 = `filesDir/rootfs/root/workspace`（guest `/root/workspace`）；記憶檔在工作區根：`MEMORY.md`、`USER.md`、`memory/*.md`（另有 `memory/.lock`、`memory/.dream-state.json`）。

**Interfaces（Produces）:**
- `object WorkspacePaths { fun resolveInside(root: File, rel: String): File? }`：拒絕（回 null）`..` 跳脫、絕對路徑、空字串、NUL、以及經符號連結（含中間目錄）解析後落在 `root` 之外者；以 `root.canonicalFile`／`toRealPath()` 比對。允許的結果是存在的一般檔。
- `object SecretScrubber { fun scrub(text: String): String }`：把 `sk-` 開頭的金鑰樣字串、以及長度 ≥ 32 的連續英數（含 `-`、`_`）字串換成 `***`；`token=<非空白>` → `token=***`。
- `class Exporter(context: Context, workspace: File, errorLog: ErrorLog)`：
  - `fun share(file: File, mime: String = guess)`：先把檔案複製到 `cacheDir/export/<原檔名>`（同名覆蓋；只複製一般檔，不跟隨符號連結到工作區外——呼叫端傳入的檔案若不在 `workspace` 或 `cacheDir/export` 之內 → 丟 `IllegalArgumentException`），用 `FileProvider.getUriForFile` 取得 URI，`Intent.ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`，用 `Intent.createChooser` 開系統分享選單。
  - `fun shareWorkspaceFile(rel: String)`：`WorkspacePaths.resolveInside(workspace, rel)` → null 時丟 `IllegalArgumentException`，否則 `share`。（Task 11a 的 WebView 下載事件會用這個或 `share`。）
  - `fun memoryBackupZip(): File`：在 `cacheDir/export/` 產生 `dsh-memory-<yyyyMMdd-HHmm>.zip`，內含 `MEMORY.md`、`USER.md`、`memory/` 下所有 `.md`（保留相對路徑）；**不含** `.lock`、`.dream-state.json`、任何非 `.md`；跳過符號連結；缺的檔案略過；zip 內不得有絕對路徑或 `..`。
  - `fun errorLogZip(): File`：`cacheDir/export/dsh-error-log-<時間>.zip`，含 `error.log` 與 `error.log.1`（存在才放），內容先經 `SecretScrubber.scrub`。
  - `fun clearExports()`：刪掉 `cacheDir/export/` 內超過 1 小時的檔案（每次 share 前呼叫）。

- [ ] **Step 1: 寫失敗測試**：`resolveInside` 擋 `../`、`/etc/passwd`、出界符號連結（檔案與中間目錄兩種）、允許一般相對路徑；備份 zip 含三樣、不含 `.lock` 與 `.dream-state.json`、跳過符號連結；錯誤記錄 zip 裡的 `sk-abc…`、64 hex、`token=xyz` 被換成 `***`；`share` 對工作區外的檔案丟例外；`share` 產生的 Intent 帶 `FLAG_GRANT_READ_URI_PERMISSION` 且 URI authority 正確（Robolectric）。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 全套單元測試綠燈、輸出乾淨（`ManifestPermissionsTest` 仍通過）。
- [ ] **Step 5:** Commit `feat: export and memory backup`。
