### 總審查修正（單一修正波）

整體總審查報告：`.superpowers/sdd/2026-11-02-dsh-mobile/final-review-report.md`（先完整讀過；編號 I-1…I-8、M-1…M-11 指的是那份報告）。本文的 Ruling 與範圍優先於報告的建議。全部在**一個修正波**做完；每項修正都要有在修正前會失敗的測試（CI/工作流程類除外，改用 YAML 解析＋說明）。

**範圍與 Ruling：**

1. **I-1 閘道路徑**：`LocalGateway` 的剩餘路徑改**白名單**：只允許字元 `[A-Za-z0-9._~/-]`、不得含 `%` 或 `;`、不得有 `.`／`..` 路徑段、必須符合各 provider 允許的前綴（現有 `isConfinedTo` 規則）。不合 → 400，不轉發、不帶金鑰。測試：`..%2fv1`、`..%2Fv1`、`..;/v1`、`%252e%252e`、`/v1/messages;x`、正常 `/v1/messages` 仍通。順手把錯位的 KDoc 搬到 `isUnsafePath`（或其取代者）。
2. **I-2 主畫面退版**：改走 `app.settingsJobs`（與設定頁同一個 application-scope、互斥、`NonCancellable` 的工作機制），`DshService.start` 以 `runCatching` 包住（同設定頁）；與設定頁的更新／退版互斥。測試：Activity 銷毀時退版仍完成並啟動 dsh；背景時 `startForegroundService` 丟例外不會讓行程崩潰。
3. **I-3 發佈閘門**（`release.yml`）：
   - tag 推送時，新增最前面的 job：用 `GITHUB_TOKEN`（`permissions: checks: read, contents: read`）查 `repos/<本倉>/commits/<sha>/check-runs`，要求 `ci.yml` 的 `android`、`plugins`、`rootfs (arm64)`、`rootfs (x86_64)`、`e2e`（名稱以 ci.yml 實際 job 名為準）全部 `conclusion == success`，否則失敗並說明「先讓 CI 在這個 commit 綠燈」。dry run 跳過這個檢查。
   - `gh release create … --draft`，上傳完 `.apk` 與 `.sha256` 後再 `gh release edit <tag> --draft=false`。
   - 發佈前查公開倉庫最新 release 的 tag，解析 versionCode；新 tag 的 versionCode 必須**大於**它（沒有任何 release 時通過）。
   - Ruling：rootfs 在 release 時照舊全新建置（dsh 本體為固定版號；相依套件浮動的風險接受，報告中註明）。
4. **I-4 proot 來源**：把目前釘選的 Termux `.deb`（proot、libtalloc、libandroid-shmem 等，`proot.gradle.kts` 裡所有 fetch 的檔案，兩個 ABI）**存進本倉庫** `third_party/termux/<abi>/`，附 `README.md`（來源網址、版號、sha256、授權 GPL-2.0／LGPL 並註明原始碼位置）。Gradle 任務改讀本地檔並照舊驗 sha256，不再連網。`BundledNativeLibsTest` 照舊要過。
5. **I-5 使用者資料移出 rootfs**：
   - 新增一個集中路徑類別（例如 `runtime/AppPaths`）：host `filesDir/userdata/workspace` ↔ guest `/root/workspace`；host `filesDir/userdata/dsh-home` ↔ guest `/root/.dsh`。`ProotRunner` 以 `-b <host>:<guest>` 綁定（啟動前確保目錄存在）。所有 host 端讀寫工作區／`.dsh` 的程式（Exporter、AgentConfigWriter、心跳、記憶健康檢查、e2e 測試等）一律改用 `AppPaths`，不得再出現 `rootfs/root/workspace` 或 `rootfs/root/.dsh` 字串（加一個單元測試掃 main 原始碼確保）。
   - 遷移：啟動時若舊位置（rootfs 內）有資料而新位置不存在 → 搬過去（同檔案系統 rename）。`RootfsInstaller` 重裝 rootfs 時**絕不**碰 `userdata/`。測試：重裝 rootfs 後工作區與 `.dsh` 內容仍在；舊位置資料被遷移。
   - dsh 版本（`/opt/dsh`）留在 rootfs 內。Ruling：rootfs 升級會把 dsh 重設為該 rootfs 內建版本；`DshVersions` 在 `current` 指向不存在的版本時要能退回內建版本（加測試）。
   - **單一版本來源**：Gradle 從 `rootfs/versions.env` 讀 `ROOTFS_VERSION`，再加上 `rootfs/**` 與 `plugins/**`（排除 `node_modules`、`dist`、`.turbo` 等產物）內容的短雜湊，組成 `BuildConfig.ROOTFS_VERSION`（例 `1-3fa2c9d1`），讓插件或 rootfs 任何改動都自動觸發重裝（使用者資料已在外面，重裝安全）。刪掉 `build.gradle.kts` 裡寫死的 `"1"`。
6. **I-6**：下載完成時若 Activity 已不在 started 狀態 → `Notifier.showUpdateReady` 而不是直接開安裝程式。加測試。
7. **I-7**：
   - `MainActivity` 設一個 `WebChromeClient`：`onShowFileChooser` 用 `FileChooserParams.createIntent()` + Activity Result API 回傳 URI（取消時回 null，必須呼叫 callback）；JS `alert/confirm/prompt` 用預設行為（不要靜默吞掉）。**不得新增權限**。
   - 設定頁加「匯出整個工作區（zip）」：`Exporter` 新增方法把 `AppPaths` 工作區打包到 `cache/export/` 後分享（IO 執行緒）；排除 symlink，不跟隨 symlink 出工作區。測試。
8. **I-8**：`DshService` 改 `START_NOT_STICKY`，測試。
9. **小項一併修**：M-1（心跳在 `serviceGaveUp` 時略過做夢與啟動 dsh，回 success）；M-2（設定頁長工作期間 `retain("settings-job")`，結束 `release`＋`stopIfUnretained` 規則照既有 holder 慣例）；M-3（引導頁金鑰輸入改 `remember`，不得進 saved-state）；M-4（所有連 127.0.0.1 的 OkHttp client `proxy(Proxy.NO_PROXY)`）；M-5（`AndroidKeyVault.get` 也接住 `ProviderException`，行為同其他解密失敗）；M-6（`loadStartupContext` 注入總量上限 32 KiB：先 `MEMORY.md`，再由新到舊的每日筆記，超過截斷並加一行說明已截斷；vitest）；M-7（`CrashPolicy` 改 `SystemClock.elapsedRealtime` 來源，可注入）；M-9（刪掉未使用的 `DSH_PINNED_VERSION` 或真正使用它，二擇一並說明）；M-10（被第 5 項的雜湊取代時，CI 快取鍵也要含 `.github/workflows/ci.yml`）。
10. **Task 13 遺留**：t3 只在送訊息後新增的內容裡找 `E2E-MEMORY-MARK`；t4 先把 `DailyUpdateCheck` 今天標成已檢查，讓 e2e 不連網；report 打字錯誤不用改。
11. **不做（已 Ruling 停放）**：M-8（寬限計時用 uptime）、M-11（activity-alias／filterTouchesWhenObscured）。

**驗證與推送：** 准許推送到 `claude/dsh-mobile-cloud-handoff-vfhvdh`（不得 force push、不得開 PR、不得動其他分支）；第 4、5 項會改到 proot 啟動與 rootfs，必須讓 CI（含 `e2e`）在你的最後一個程式 commit 上**全綠**（用 `mcp__github__actions_list`／`mcp__github__get_job_logs`，需要時先 ToolSearch 載入）。每次推送前本機跑完單元測試、`pnpm -C plugins test/typecheck/build`、`assembleDebug`、`assembleDebugAndroidTest`。紅線不變：權限 9 個、不改 dsh 原始碼、金鑰不進 env／設定檔／記錄檔、測試失敗訊息不印 token。
