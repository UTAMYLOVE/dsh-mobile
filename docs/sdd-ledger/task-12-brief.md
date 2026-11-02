### Task 12：外殼 App 更新與發佈流程

計畫原文 Task 12，**加上下列 Ruling 與前面任務的實際介面**。本文與計畫原文衝突時以本文為準。

**Rulings：**
1. **rootfs 進 APK 的方式**：不做 product flavor（會改掉所有 Gradle 任務名）。改用 Gradle 屬性 `dshm.rootfsAbi`（`arm64` 或 `x86_64`）與 `dshm.rootfsFile`（tar.xz 路徑）：有設定時，建置把該檔複製成 `assets/rootfs.tar.xz`（用產生的 assets 目錄，不放進原始碼樹、不提交），並把 `ndk.abiFilters` 限制成對應 ABI（`arm64-v8a` 或 `x86_64`）；沒設定時（一般單元測試／CI android job）不打包 rootfs，行為與現在相同。`androidResources.noCompress += "xz"`（避免對已壓縮的 130 MB 檔再壓一次，也讓 `available()` 正確）。
2. **發佈只發 arm64**（目標機 S25 Ultra）。x86_64 只給 Task 13 模擬器測試用。
3. **版本**：tag 格式 `v<versionName>+<versionCode>`（例 `v0.2.0+2`）。Gradle 屬性 `dshm.versionName`、`dshm.versionCode` 有設定時覆蓋 `defaultConfig`（預設維持 `0.1.0`／`1`）。
4. **APK 安裝**：下載到 `cacheDir/update/`；`res/xml/file_paths.xml`（Task 11d 建立，只開 `cache/export/`）**加一條** `cache/update/`；以 FileProvider URI + `ACTION_VIEW`（`application/vnd.android.package-archive`、`FLAG_GRANT_READ_URI_PERMISSION`）交給系統安裝程式（使用既有的 `REQUEST_INSTALL_PACKAGES`，系統會逐次詢問；**不得新增權限**）。若 `packageManager.canRequestPackageInstalls()` 為 false，先開 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（`package:` URI）讓使用者授權。
5. **試跑**：release workflow 另有 `workflow_dispatch` 輸入 `dry_run`（預設 true）：跑測試、建 rootfs、建**未簽章** release APK，到「簽章」之前停止（不需要任何 Secret）。只有 `push: tags: v*` 才簽章與發佈。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/update/ApkUpdateChecker.kt`、`update/ApkInstaller.kt`
- Create: `.github/workflows/release.yml`
- Modify: `android/app/build.gradle.kts`（Ruling 1、3；release `signingConfig` 從環境變數 `DSHM_KEYSTORE_FILE`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 讀，**沒有就不設**，讓 dry run 產出未簽章 APK）、`res/xml/file_paths.xml`（Ruling 4）
- Modify: `heartbeat/HeartbeatWorker.kt`（帳本 Ruling：每天第一次心跳時檢查更新；「今天已檢查」存 SharedPreferences，以 `Asia/Taipei` 日期判斷；檢查失敗只寫 ErrorLog，不影響心跳結果）、`notify/Notifier.kt`（`fun showUpdateAvailable(info: ReleaseInfo)`：點擊開 MainActivity，extra `"action"="install_update"`）、`ui/MainActivity.kt`（處理 `install_update`：背景下載＋驗證＋安裝，顯示進度 Toast／錯誤）
- Test: `android/app/src/test/kotlin/app/dshmobile/update/ApkUpdateCheckerTest.kt`、`ApkInstallerTest.kt`；`HeartbeatWorkerTest` 補「每天只檢查一次」

**Consumes（先讀）：** `BuildConfig.RELEASES_REPO`（Gradle 屬性 `dshm.releasesRepo`，現值 `UTAMYLOVE/dsh-mobile-releases`）、`BuildConfig.VERSION_CODE`、`notify/Notifier.kt`、`heartbeat/HeartbeatWorker.kt`（Task 10，簽名見 `docs/sdd-ledger/task-10-report.md`）、`export/Exporter.kt` 與 `res/xml/file_paths.xml`（Task 11d）、`ui/MainActivity.kt`（Task 11a）、`.github/workflows/ci.yml`（rootfs job 的建置與 staging 步驟：release 要重用同樣步驟，包括 `pnpm -C plugins build` 與 staging 三個插件）、`rootfs/build-rootfs.sh`。

**Interfaces（Produces）:**
- `data class ReleaseInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)`
- `class ApkUpdateChecker(http: OkHttpClient, repo: String, currentVersionCode: Int, apiBase: HttpUrl = "https://api.github.com".toHttpUrl())`；`suspend fun latest(): ReleaseInfo?`：`GET {apiBase}/repos/<repo>/releases/latest`（`Accept: application/vnd.github+json`，不帶任何權杖）；tag 必須完全符合 `^v(\d+\.\d+\.\d+)\+(\d+)$`；只在 versionCode **大於**目前時回傳；assets 中找唯一一個 `.apk` 與同名加 `.sha256` 的檔；再 GET 那個 `.sha256` 下載網址，內容第一個欄位必須是 64 個 hex；任何格式不符、缺檔、非 2xx → null（寫 ErrorLog 由呼叫端負責）。只接受 `https://github.com/` 或 `https://objects.githubusercontent.com/` 開頭的下載網址（測試時可注入允許的 base）。
- `class ApkInstaller(context: Context, http: OkHttpClient)`；`suspend fun download(info: ReleaseInfo): File?`：下載到 `cacheDir/update/dsh-mobile-<versionCode>.apk.part`，邊下載邊算 SHA-256，**不符就刪掉並回 null**，符合才改名為 `.apk`；`fun install(apk: File)`：Ruling 4。
- `release.yml`：觸發 `push: tags: ['v*']` 與 `workflow_dispatch`（`dry_run`）。步驟：checkout → 驗證 tag 格式並解析 versionName／versionCode（dry run 用 `0.0.0`／`1`）→ 跑 android 單元測試與 plugins 測試 → 建 arm64 rootfs（重用 ci.yml 的 staging 做法）→ `./gradlew :app:assembleRelease -Pdshm.rootfsAbi=arm64 -Pdshm.rootfsFile=… -Pdshm.versionName=… -Pdshm.versionCode=…` → **dry run 到此結束，上傳未簽章 APK 為 artifact** → （非 dry run）把 `ANDROID_KEYSTORE_BASE64` 解碼成 runner 暫存檔、設 `DSHM_KEYSTORE_FILE` 等環境變數、重新 assembleRelease 簽章、`apksigner verify` → 算 `.sha256` → 用 `RELEASES_TOKEN` 以 `gh release create <tag> --repo <dshm.releasesRepo> <apk> <apk>.sha256 --title … --notes …` 發到公開倉庫 → 刪除暫存 keystore。Secrets 只在需要的步驟以 `env:` 傳入，任何步驟都不得 `echo` 它們；`permissions: contents: read`。

- [ ] **Step 1: 寫失敗測試**（MockWebServer）：較新版回 ReleaseInfo；同版回 null；舊版回 null；tag 格式錯（`v1.2`、`1.2.3+4`、`v1.2.3+x`）回 null；缺 `.sha256` 回 null；sha256 內容不是 64 hex 回 null；下載網址不在允許清單回 null；`ApkInstaller.download` 在 sha256 不符時檔案被刪除且回 null、相符時回 `.apk` 檔；`HeartbeatWorker` 同一天兩次心跳只檢查一次、檢查丟例外時心跳仍 success。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 全套單元測試綠燈、輸出乾淨（`ManifestPermissionsTest` 仍通過）；`./gradlew :app:assembleRelease` 在**沒有** rootfs 屬性與簽章環境變數時也能成功（產出未簽章 APK）；`release.yml` 以 `python3 -c "import yaml…"` 解析成功。實際 workflow 由控制者用 `workflow_dispatch dry_run=true` 觸發驗證（你不用觸發）。
- [ ] **Step 5:** Commit `feat: shell app self-update and release workflow`。
