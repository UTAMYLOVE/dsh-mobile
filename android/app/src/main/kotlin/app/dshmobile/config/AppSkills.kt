package app.dshmobile.config

import java.io.File

/**
 * Skills the app ships to dsh (stage 2 batch 2 「讓 dsh 進化」), through dsh's official skill root `$DSH_HOME/skills/<name>/SKILL.md`
 * (dsh-skill-filesystem). Only folders named `dshm-*` are the app's: they are rewritten on every start and removed when the app
 * no longer ships them; anything else in that folder is the owner's and never touched.
 */
object AppSkills {
    const val PREFIX = "dshm-"

    val all: Map<String, String> = linkedMapOf(
        "dshm-followups" to skill(
            "dshm-followups",
            "After an ordinary answer, offer the owner two or three short follow-up questions he can tap (hidden marker block).",
            """
            # 建議下一步
            一般的問答回覆（查資料、解釋、建議、比較）結束時，在最後加上這個區塊，App 會把它變成可以點的按鈕，主人看不到區塊本身：

            <!--dshm-followups
            外資買了哪些股票？
            下週要注意什麼？
            做成一頁報告
            -->

            - 2～3 行，每行一個主人可能接著想問的問題，繁體中文，15 字以內，不加編號。
            - 要具體、接著這次的內容，不要「還有其他問題嗎」這種空話。
            - 不要加在：小說章節、記帳記錄、每日簡報、只是確認收到的短回覆、正在等主人回答的問題、工具還在進行的中途。
            - 區塊一定放在回覆最後，前面不要提到它。
            """,
        ),
        "dshm-novel" to skill(
            "dshm-novel",
            "Write fiction chapter by chapter for the app's bookshelf: consistent characters from the setting card, one chapter per reply.",
            """
            # 小說寫作（書架）
            這個對話是 app 書架上的一本書。你的每一則長篇回覆會自動變成一章。
            - 每次只寫一章正文。第一行是章名（例如「第三章　霧裡的燈」），之後直接是正文；不要前言、不要解說、不要問「要不要繼續」。
            - 一章大約 1500–3000 字，除非主人另外指定。
            - 嚴格遵守開頭的設定卡（角色、世界觀、文風）。人物說話方式、稱呼、能力前後一致。
            - 主人說「請接著寫下一章」時，接續上一章結尾，不要重複已寫過的情節。
            - 主人提出修改時，重寫指定的那一章，整章輸出。
            - 需要記住的長期設定（新角色、伏筆）用 memory_write（target "long"）記下，標題寫書名。
            """,
        ),
        "dshm-daily-brief" to skill(
            "dshm-daily-brief",
            "Prepare the owner's daily briefing (weather, news, schedule reminders, memory) and optionally schedule it every morning.",
            """
            # 每日簡報
            主人說「早安」「今天的簡報」「每天早上給我簡報」時使用。
            1. 用 memory_search 找主人在意的事（城市、追蹤的主題、待辦）。沒有就問一次，再用 memory_write（target "user"）記下。
            2. 用網路搜尋查：主人城市今天的天氣、3–5 則和主人興趣相關的新聞（附來源連結）。
            3. 列出今天的提醒與待辦（從記憶和排程）。
            4. 用繁體中文、條列、手機好讀的長度（約 300 字內）。
            5. 主人要「每天」時，用排程工具（schedule）建立每天早上的提醒，內容是「請給我今天的簡報」；要提醒主人時用 notify_user。
            """,
        ),
        "dshm-bookkeeping" to skill(
            "dshm-bookkeeping",
            "Record expenses and income the owner tells you into monthly CSV files in the workspace and summarise them.",
            """
            # 記帳
            主人說「午餐 120」「收入 3000 薪水」「這個月花多少」時使用。
            - 帳本放在工作區 `帳本/YYYY-MM.csv`（台北時間的月份）。第一行標題：`日期,類別,項目,金額,備註`。支出金額為負數，收入為正數。
            - 記一筆：判斷類別（餐飲、交通、購物、居家、娛樂、醫療、收入、其他），追加一行，回覆一句確認（例如「已記：餐飲 午餐 -120」）。
            - 金額或項目不清楚時先問，不要猜。不要改舊的紀錄，除非主人明確要求。
            - 統計：讀當月 CSV，給總支出、總收入、各類別小計、和上個月比較。數字要算對，可以用 python 計算。
            - 這是主人的私人資料：只放在工作區，不要寫進記憶檔，不要貼到網路上。
            """,
        ),
        "dshm-phone" to skill(
            "dshm-phone",
            "What you can do on the owner's Android phone through the DSH Mobile app (notifications, sharing files, limits).",
            """
            # 在手機上
            - 通知：用 notify_user 送一則手機通知（標題 + 內容）。適合「做完了」「提醒我…」。不要濫發。
            - 檔案：寫在工作區的檔案，主人可以在 app 的「對話 → 數據 → 工作區檔案」點一下分享（存到手機、傳 LINE）。做好檔案時告訴主人檔名。
            - 記憶：memory_search / memory_get / memory_write 是長期記憶，跨對話都在。
            - 排程：要定時做的事用排程工具（schedule）。
            - 限制：子代理人數、團隊人數、委派深度、模型都在 app 的「設定 → 代理人」；不夠用時請主人去改，不要改 dsh 的設定檔。workflow 在手機上沒有沙盒，不能用。
            """,
        ),
        "dshm-pages" to skill(
            "dshm-pages",
            "Propose and build small single-file HTML pages (trackers, dashboards, mini tools) the owner can open and share from the phone.",
            """
            # 自己提案的小頁面
            主人說「幫我做一個…頁面／小工具／追蹤表」，或你發現一個小頁面能幫主人時使用。
            - 先用一兩句提出你想做的頁面和它的用途，主人同意再做。
            - 做成單一個 HTML 檔，放在工作區 `pages/名稱.html`：CSS、JS 全部寫在檔案裡，不載入外部網址（手機可能離線）。
            - 配色用 app 的霧城色：#FEDFE1 底、#DC9FB4 主色、#A5DEE4 水藍、#D7C4BB 奶茶、文字 #5E4445。手機直式 412px 寬要好用。
            - 資料要保存時用 localStorage。完成後告訴主人檔名，並說明可以在「工作區檔案」分享或用瀏覽器打開。
            """,
        ),
        "dshm-weather" to skill(
            "dshm-weather",
            "Weather questions and the daily brief: read the app's own multi-model weather analysis in .dshm/weather.md first.",
            """
            # 天氣
            主人問天氣、要不要帶傘、出門穿什麼，或做每日簡報時使用。
            - 先讀工作區的 `.dshm/weather.md`：那是 App 自己做的分析（六個全球模式加權、氣象署測站校正、約 50 個系集成員算降雨機率），比網路上的單一預報準。
            - 回答要說地點、時間、溫度範圍、降雨機率，最後一句說「信心：高／中／低」和原因（檔案裡有）。
            - 檔案的地點不是主人問的地方、或更新時間超過 3 小時：照樣說檔案裡的內容並註明時間，再建議主人到 App 總覽的「天氣分析」換地點或按「重新分析」；需要的話再用 web_search 補充，並說明那是單一來源。
            - 不要自己編數字。
            """,
        ),
        "dshm-contemplate" to skill(
            "dshm-contemplate",
            "Contemplate mode: three subagents argue a question from the optimist, cautious and data stances, then one merged answer.",
            """
            # 沉思（三方意見再合成）
            主人用「沉思」問問題時使用。
            1. 同時開三個 subagent，各給一個立場，任務內容一樣，只有立場不同：
               - 樂觀派：找機會、好處、最好的情況會怎樣。
               - 保守派：找風險、缺點、最壞的情況和要防的事。
               - 數據派：只看事實和數字，需要就用 web_search 查，附來源和日期。
               subagent 的名字就寫「樂觀派」「保守派」「數據派」，App 會用這三個名字顯示卡片。
            2. 三個都回來後，你自己寫合成答案：先一句話結論，再列三方的重點（各 2～3 點），最後寫「我的判斷」和理由、信心（高／中／低）。
            3. 三方說法衝突的地方要明講，不要硬湊成一致。不要給股票的買賣指示，只給資訊和判斷依據。
            """,
        ),
        "dshm-research" to skill(
            "dshm-research",
            "Deep research: many web searches and page reads, then a cited Markdown report saved in reports/ and presented to the owner.",
            """
            # 深度研究
            主人用「深度研究」問問題時使用。
            1. 先列 5～10 個不同角度的搜尋關鍵字（中文、英文都可以），逐一用 web_search 查。
            2. 挑最有用的 5～12 個網頁用 web_fetch 讀內容；官方資料、原始公告、財報優先，農場文不要用。
            3. 寫成 Markdown 報告，存到工作區 `reports/日期-主題.md`（日期用 2026-11-06 這種格式，主題 10 字以內）：
               - 開頭「重點摘要」5 行以內。
               - 分段標題（##），每個事實後面用 [1] 這種編號標來源。
               - 最後「資料來源」列出編號、標題、網址、日期。
               - 資料互相矛盾或查不到的地方直接寫出來。
            4. 用 present 把報告檔交給主人，App 會顯示研究卡片並存到書架。
            5. 回覆本身只寫 5 行以內的摘要，加上「完整報告已存到書架」。
            """,
        ),
        "dshm-deliverable" to skill(
            "dshm-deliverable",
            "Deliverables mode: make finished files (docx, xlsx, pptx, html, md, csv) in deliverables/ and present every file to the owner.",
            """
            # 成品
            主人用「成品」要東西時使用：報告、表格、簡報、網頁、清單。
            - 檔案放在工作區 `deliverables/`，檔名用中文、看得懂是什麼（例如 `deliverables/11月支出整理.xlsx`）。
            - Word、Excel、PowerPoint、PDF 用 office-docx、office-xlsx、office-pptx、office-export-pdf 技能做；網頁做成單一 HTML 檔，配色用 #FEDFE1、#DC9FB4、#A5DEE4、#D7C4BB、#5E4445。
            - 每個做好的檔案都要用 present 交給主人，App 會列成成品卡片，可以打開和分享。
            - 回覆只說做了哪些檔案、每個一句話說明內容；不要把檔案內容整份貼在對話裡。
            - 主人沒指定格式時：表格類用 xlsx，文件類用 docx，要在手機上看的用 html。
            """,
        ),
        "dshm-draw" to skill(
            "dshm-draw",
            "Draw diagrams, flowcharts, charts and simple illustrations as an SVG file in drawings/ using only the app palette, then present it.",
            """
            # 畫圖（向量圖）
            主人要流程圖、架構圖、圖表、時間軸、心智圖或簡單插圖時使用。
            - 寫一個 SVG 檔到工作區 `drawings/名稱.svg`，然後用 present 交給主人，App 會直接顯示，可以放大和存到書架。
            - viewBox 最大 800×600（例如 `viewBox="0 0 800 600"`），手機直式看得清楚：字至少 14，線至少 2。
            - 顏色只能用這幾個：#FEDFE1、#DC9FB4、#A5DEE4、#D7C4BB、#9E7A7A、#5E4445、#7D5F60、#A98F90、#FFFFFF，可以加 opacity。不要用其他顏色。
            - 不要外部連結、圖片、字型、script；文字用 `font-family="sans-serif"`，中文直接寫。
            - 圖表的數字要正確，標題和單位寫清楚。
            - 回覆只用一兩句說明這張圖。
            """,
        ),
    )

    private fun skill(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: ${description.replace("\n", " ")}\n---\n\n" + body.trimIndent() + "\n"

    /** Writes every app skill under [dshHome]`/skills` and removes `dshm-*` folders the app no longer ships. Never throws. */
    fun install(dshHome: File) {
        runCatching {
            val root = File(dshHome, "skills").apply { mkdirs() }
            for ((name, text) in all) {
                val dir = File(root, name).apply { mkdirs() }
                val file = File(dir, "SKILL.md")
                if (!file.isFile || file.readText() != text) {
                    val tmp = File(dir, "SKILL.md.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name !in all }?.forEach { it.deleteRecursively() }
        }
    }
}
