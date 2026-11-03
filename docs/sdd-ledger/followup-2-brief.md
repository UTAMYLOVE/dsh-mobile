### 追加 2：dsh 網頁在手機寬度的版面（0.1.3）

宇哥在 S25 Ultra 上用 0.1.1：順、能用，但 dsh 的網頁是桌面版面——左側欄佔大半畫面，對話被擠成一字一行；設定視窗也被擠成兩欄、文字直排；App 的齒輪按鈕疊在 dsh 右上角自己的按鈕上。真機截圖：`.superpowers/sdd/fu1/device-chat-0.1.1.jpg`、`device-settings-0.1.1.jpg`（相對於 repo 根目錄，git 忽略）。WebView 寬約 412 dp。

紅線不變：不改 dsh 原始碼，只用 `@dshm/ui` 插件（style／script 注入）與 App 程式；權限 9 個；不印 token。

**要做的（窄螢幕，以 `max-width: 720px` 為準；寬螢幕維持原樣）：**
1. **側欄預設收起，打開時是浮在上層的抽屜**：用 dsh 自己的收合狀態（先在 scratch dsh 用 Playwright 以 412×915 的手機視窗查出側欄的元素、收合按鈕與它存狀態的方式；優先沿用 dsh 的收合機制，例如首次載入時若是窄螢幕且未收合就觸發它自己的收合），展開時側欄以 overlay 方式覆蓋（不推擠主內容），點側欄外的遮罩或選了對話後收起。任何找不到元素的情況都安靜放棄，不得讓頁面出錯。
2. **設定視窗單欄**：左側選單改到上方（可橫向捲動的列或下拉），內容使用全寬；文字不得直排。其他 dsh 的對話框（模型選單、權限詢問、提問卡）在窄螢幕下也要能完整顯示、不被裁切。
3. **齒輪按鈕不擋 dsh**：App 的設定按鈕（MainActivity 疊在 WebView 上的那顆）移到不與 dsh 頁首按鈕重疊的位置（例如左下或右下靠邊、避開 dsh 的輸入列與 ↑ 送出鈕），並確認在鍵盤彈出時也不擋輸入框。
4. 追加 1 的 FOGCITY 風格與「隱藏 App 管理的設定」照舊有效；本次的 CSS 也要遵守同樣規則（只用那 6+2 色、不加無限動畫、`backdrop-filter` 總數仍 ≤ 2）。

**驗收：**
- vitest：新規則存在、只在窄螢幕 media query 內、script 缺元素不丟例外。
- e2e（API 34 模擬器，直式手機寬度）：載入後側欄收起、對話區寬度 ≥ 視窗寬度的 90%；打開側欄後它是 overlay（主內容寬度不變）、點遮罩收起；打開 dsh 設定視窗，選單在內容上方、內容寬度 ≥ 視窗寬度的 85%；App 齒輪按鈕的範圍與 dsh 頁首按鈕不相交。
- Playwright 在 scratch dsh 以 412×915 截圖前後對照，放進報告（檔案放 scratchpad，報告附路徑）。

**驗證與推送**：准許推送到 `claude/dsh-mobile-cloud-handoff-vfhvdh`（不得 force push、開 PR、打 tag、動其他分支）。推送前本機跑完單元測試、`pnpm -C plugins test/typecheck/build`、`assembleDebug`、`assembleDebugAndroidTest`；CI（含 e2e）在最後一個程式 commit 全綠。報告寫 `docs/sdd-ledger/followup-2-report.md` 並最後推送。
