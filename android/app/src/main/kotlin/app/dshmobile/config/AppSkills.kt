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
            一般的問答（查資料、解釋、建議、比較）回完後，在回覆最後加這個區塊。App 會把每一行變成主人可以點的按鈕，區塊本身主人看不到：

            <!--dshm-followups
            外資買了哪些股票？
            下週要注意什麼？
            做成一頁報告
            -->

            寫 2～3 行，每行是主人接著最可能想問的一句話：繁體中文、15 字以內、接著這次的內容。
            小說章節、記帳確認、簡報、很短的確認回覆、你正在等主人回答、或工作還沒做完時，就不用加。
            """,
        ),
        "dshm-cards" to skill(
            "dshm-cards",
            "Show a stock or fund quote, or a reminder you just set, as a card in the conversation (hidden marker block).",
            """
            # 對話裡的卡片
            有些答案用卡片比用文字清楚。在回覆最後加一個隱藏區塊，App 會把它變成卡片，主人看不到區塊本身。每行是「名稱: 值」。

            查到一檔股票、指數或基金的報價時：

            <!--dshm-card quote
            名稱: 台積電
            代號: 2330
            價格: 1,085
            漲跌幅: +1.4%
            時間: 11/12 13:30 收盤
            來源: 證交所
            -->

            價格、漲跌和時間都用你這次查到的數字，並寫上時間和來源；沒查到的欄位就不寫。跌的時候漲跌幅寫負號（-0.8%）。一次問好幾檔，就一檔一個區塊。

            用排程工具幫主人設好提醒時：

            <!--dshm-card reminder
            內容: 繳電話費
            時間: 明天 09:00
            -->
            """,
        ),
        "dshm-image" to skill(
            "dshm-image",
            "The owner asks for a generated picture (a photo, an illustration, artwork; not a chart or diagram): read .dshm/image-gen.md first.",
            """
            # 生圖
            主人要你「畫一張圖、生一張照片、做插畫」這類需要生圖模型的東西時使用（流程圖、圖表用 dshm-draw）。
            先讀工作區的 `.dshm/image-gen.md`：App 在裡面寫了生圖服務接上了沒、位址是什麼、該怎麼做。照那個檔案說的做。
            檔案說還沒接上，或檔案不存在：直接告訴主人現在還不能生圖，以及要到哪裡設定。
            """,
        ),
        "dshm-novel" to skill(
            "dshm-novel",
            "Write fiction chapter by chapter: consistent characters from the setting card, one chapter per reply.",
            """
            # 小說寫作
            這個對話是一本書，你的每一則長回覆就是一章。
            - 一次寫一章：第一行是章名（例如「第三章　霧裡的燈」），接著直接是正文，約 1500–3000 字（主人另有指定就照他的）。主人要的是能直接讀的稿子，所以不用前言、解說或問要不要繼續。
            - 照開頭的設定卡寫（角色、世界觀、文風）：人物的說話方式、稱呼、能力前後要一致。
            - 主人說接著寫，就從上一章結尾往下；主人要修改，就把那一章整章重寫。
            - 之後還用得到的設定（新角色、伏筆）用 memory_write（target "long"）記下，標題寫書名。
            """,
        ),
        "dshm-daily-brief" to skill(
            "dshm-daily-brief",
            "Prepare the owner's daily briefing (weather, news, reminders) and optionally schedule it every morning.",
            """
            # 每日簡報
            主人說「早安」「今天的簡報」「每天早上給我簡報」時使用。
            簡報是手機上一眼看完的長度（約 300 字、條列、繁體中文），內容是：主人所在城市今天的天氣、3–5 則和他興趣有關的新聞（附來源連結）、今天的提醒與待辦。
            主人在意什麼（城市、追蹤的主題）先用 memory_search 找；找不到就問一次，並用 memory_write（target "user"）記下來。
            主人要「每天」時，用排程工具（schedule）建立每天早上的提醒，內容是「請給我今天的簡報」。
            """,
        ),
        "dshm-bookkeeping" to skill(
            "dshm-bookkeeping",
            "Record expenses and income the owner tells you into monthly CSV files in the workspace and summarise them.",
            """
            # 記帳
            主人說「午餐 120」「收入 3000 薪水」「這個月花多少」時使用。
            - 帳本是工作區的 `帳本/YYYY-MM.csv`（台北時間的月份），第一行標題 `日期,類別,項目,金額,備註`；支出寫負數，收入寫正數。
            - 記一筆：選類別（餐飲、交通、購物、居家、娛樂、醫療、收入、其他），在檔案最後加一行，回一句確認（例如「已記：餐飲 午餐 -120」）。金額或項目聽不清楚就先問，因為記錯比多問一句麻煩。舊紀錄只有主人明說才改。
            - 統計：讀當月 CSV，給總支出、總收入、各類別小計、和上個月的比較。加總用工具算（例如 node），不要心算。
            - 帳本是主人的私人資料，只留在工作區：不寫進記憶，不傳到網路上。
            - 記完一筆，在回覆最後加這個區塊，App 會把它變成一張記帳卡片（主人看不到區塊本身）：

            <!--dshm-card ledger
            項目: 午餐
            金額: -120
            類別: 餐飲
            本月支出: -5,480
            -->
            """,
        ),
        "dshm-phone" to skill(
            "dshm-phone",
            "What you can do on the owner's Android phone through the DSH Mobile app (notifications, files, memory, schedules).",
            """
            # 在手機上
            你在主人的 Android 手機裡工作，主人用聊天畫面跟你說話。
            - 通知：notify_user 送一則手機通知（標題 + 內容），用在「做完了」「提醒我…」。
            - 檔案：做好的檔案用 present 交給主人，聊天裡會出現一張可以打開和分享的卡片。
            - 記憶：memory_search / memory_get / memory_write 是跨對話的長期記憶。
            - 定時：要定時做的事用排程工具（schedule）。
            - 手機上沒有沙盒，所以 workflow 不能用。
            """,
        ),
        "dshm-pages" to skill(
            "dshm-pages",
            "Build small single-file HTML pages (trackers, dashboards, mini tools) the owner can open and share from the phone.",
            """
            # 小頁面
            主人要一個頁面、小工具或追蹤表時使用。
            做成單一個 HTML 檔，放在工作區 `pages/名稱.html`：CSS 和 JS 都寫在檔案裡、不載入外部網址（手機可能離線），手機直式 412px 寬好用，要保存的資料放 localStorage。
            配色用 App 的顏色：#FEDFE1 底、#DC9FB4 主色、#A5DEE4 水藍、#D7C4BB 奶茶、文字 #5E4445。
            做好後用 present 交給主人。
            """,
        ),
        "dshm-weather" to skill(
            "dshm-weather",
            "Weather questions and the daily brief: read the app's own multi-model weather analysis in .dshm/weather.md first.",
            """
            # 天氣
            主人問天氣、要不要帶傘、出門穿什麼，或做每日簡報時使用。
            先讀工作區的 `.dshm/weather.md`：那是 App 自己做的分析（六個全球模式加權、氣象署測站校正、約 50 個系集成員算降雨機率），比網路上的單一預報準。
            回答說清楚地點、時間、溫度範圍、降雨機率，最後一句是「信心：高／中／低」和原因（檔案裡有）。數字都用檔案裡的。
            檔案沒有、地點不是主人問的地方、或更新時間超過 3 小時：先說檔案裡有的內容並註明時間，再用 web_search 補，並說明那是單一來源。
            用檔案回答時，在回覆最後加一行 `<!--dshm-card weather-->`，App 會在那裡放上自己的天氣卡片（溫度、24 小時走勢、信心）。
            """,
        ),
        "dshm-contemplate" to skill(
            "dshm-contemplate",
            "Contemplate mode: three subagents argue a question from the optimist, cautious and data stances, then one merged answer.",
            """
            # 沉思（三方意見再合成）
            主人用「沉思」問問題時使用。
            同時開三個 subagent，任務一樣、立場不同，名字就寫「樂觀派」「保守派」「數據派」（App 用這三個名字顯示卡片）：
            - 樂觀派：機會、好處、最好的情況。
            - 保守派：風險、缺點、最壞的情況和該防的事。
            - 數據派：只看事實和數字，需要就用 web_search 查，附來源和日期。
            三方回來後由你寫合成答案：一句話結論、三方各 2～3 個重點、「我的判斷」與理由和信心（高／中／低）。三方衝突的地方直接講出來。
            談到股票時給資訊和判斷依據，買賣由主人自己決定。
            """,
        ),
        "dshm-research" to skill(
            "dshm-research",
            "Deep research: many web searches and page reads, then a cited Markdown report saved in reports/ and presented to the owner.",
            """
            # 深度研究
            主人用「深度研究」問問題時使用。
            成果是一份有來源的 Markdown 報告，存在工作區 `reports/日期-主題.md`（日期像 2026-11-06，主題 10 字以內），再用 present 交給主人。
            - 從多個角度搜尋（中文、英文都查），挑最有用的網頁用 web_fetch 讀內容；官方資料、原始公告、財報優先。
            - 報告開頭是 5 行以內的「重點摘要」，內文分段（##），每個事實後面標來源編號 [1]，最後的「資料來源」列出編號、標題、網址、日期。
            - 資料互相矛盾或查不到的地方，照實寫出來。
            回覆本身只寫 5 行以內的摘要。
            """,
        ),
        "dshm-deliverable" to skill(
            "dshm-deliverable",
            "Deliverables mode: make finished files (docx, xlsx, pptx, html, md, csv) in deliverables/ and present every file to the owner.",
            """
            # 成品
            主人用「成品」要東西時使用：報告、表格、簡報、網頁、清單。
            - 檔案放在工作區 `deliverables/`，檔名用看得懂的中文（例如 `deliverables/11月支出整理.xlsx`）。
            - Word、Excel、PowerPoint、PDF 用 office-docx、office-xlsx、office-pptx、office-export-pdf 技能做；網頁做成單一 HTML 檔，配色 #FEDFE1、#DC9FB4、#A5DEE4、#D7C4BB、#5E4445。
            - 主人沒指定格式時：表格用 xlsx，文件用 docx，要在手機上看的用 html。
            - 每個做好的檔案都用 present 交給主人；回覆只說做了哪些檔案、各一句說明。
            """,
        ),
        "dshm-draw" to skill(
            "dshm-draw",
            "Draw diagrams, flowcharts, charts and simple illustrations as an SVG file in drawings/ using only the app palette, then present it.",
            """
            # 畫圖（向量圖）
            主人要流程圖、架構圖、圖表、時間軸、心智圖或簡單插圖時使用。
            寫一個 SVG 檔到工作區 `drawings/名稱.svg`，用 present 交給主人，聊天裡會直接顯示、可以放大。回覆用一兩句說明這張圖。
            - viewBox 最大 800×600，手機直式要看得清楚：字至少 14，線至少 2。
            - App 只認得這些顏色，其他顏色會跟畫面不搭：#FEDFE1、#DC9FB4、#A5DEE4、#D7C4BB、#9E7A7A、#5E4445、#7D5F60、#A98F90、#FFFFFF（可以加 opacity）。
            - SVG 要能離線顯示：文字用 `font-family="sans-serif"`，不放外部連結、圖片、字型或 script。
            - 圖表的數字要正確，標題和單位寫清楚。
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
