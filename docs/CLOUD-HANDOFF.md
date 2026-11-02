# 雲端接手說明（給 Claude Code 雲端 session）

你接手 DSH Mobile 專案：把官方 DeepSeek Harness（dsh）原封不動放進 Android App，外加 OpenClaw 風格記憶、心跳、粉紅（櫻花淺粉）介面。老闆是「宇哥」，用繁體中文溝通，所有重大決定由他拍板。

## 先讀
1. `docs/superpowers/specs/2026-11-02-dsh-mobile-design.md`（規格，最高依據）
2. `docs/superpowers/plans/2026-11-02-dsh-mobile.md`（實作計畫）
3. `docs/sdd-ledger/progress.md`（進度帳本 + 所有裁決 Ruling，裁決優先於計畫原文）
4. `docs/dsh-findings.md`（dsh 內部偵察結果）

## 目前進度（2026-11-03）
- Task 0、1、2：完成並通過審查。
- Task 3（解壓與 proot 執行器）：已實作並推上 main（commit `d8fb307`），**尚未審查**。下一步：審查 Task 3。審查重點：tar-slip 防護會擋掉「位於已解壓符號連結底下」的條目，可能讓真正的 rootfs 裝不起來；proot 內沒有 `/etc/resolv.conf`（DNS）留給後續任務。
- Task 4～13：未開始。執行順序見帳本 Ruling：T4,5,6,7,8,9,10,11c,11d,11a,11b,12,13。

## 做法
- 用 superpowers:subagent-driven-development 繼續執行計畫，帳本改寫在 `docs/sdd-ledger/progress.md`（雲端沒有本機的 `.superpowers/`），每完成一個任務就提交並推送。
- 省額度模式（見帳本 Ruling）：實作與審查用 Sonnet；Task 5 閘道與最終審查用 Opus；只有 Critical/Important 修正才做複查（Haiku）。
- 每次修改後跑 `./gradlew :app:testDebugUnitTest`（在 `android/`）與 `pnpm -C plugins test`，並確認 GitHub Actions 綠燈。

## 環境需求（雲端容器是 Linux、每次重開都是新的）
- JDK 21、Android SDK（platforms;android-37.0、build-tools;37.0.0、platform-tools，需接受授權）、Node 24、pnpm 11。
- 網路需能連：registry.npmjs.org、dl.google.com、packages.termux.dev（或其鏡像）、nodejs.org、github.com、Debian 映像。
- `android/local.properties` 要自己寫 `sdk.dir=`（已被 .gitignore 忽略）。

## 紅線
- 不改官方 dsh 原始碼；權限只能是規格列的 9 個。
- 金鑰只存 Android Keystore，永遠不交給 dsh、不進設定檔或記錄檔；不讀 `.env`。
- 對話紀錄與使用統計上傳一律關閉（老闆 2026-11-02 決定）。
- 公開倉庫 `UTAMYLOVE/dsh-mobile-releases` 只放 APK；APK 簽章金鑰由宇哥自己填進 GitHub Secrets（Task 12 時再教他）。
