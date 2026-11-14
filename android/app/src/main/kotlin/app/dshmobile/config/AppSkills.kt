package app.dshmobile.config

import java.io.File
import java.io.InputStream

/**
 * Skills the app ships to dsh (stage 2 batch 2 「讓 dsh 進化」), through dsh's official skill root `$DSH_HOME/skills/<name>/SKILL.md`
 * (dsh-skill-filesystem). Only folders named `dshm-*` are the app's: they are rewritten on every start and removed when the app
 * no longer ships them; anything else in that folder is the owner's and never touched. A skill may also ship files next to its
 * SKILL.md ([files], from the app's assets); inside a `dshm-*` folder every other file is removed.
 */
object AppSkills {
    const val PREFIX = "dshm-"

    /** Where a shipped file is in the app's assets: `skills/<skill>/<file>`. */
    const val ASSET_DIR = "skills"

    /**
     * Files shipped next to a skill's SKILL.md, by skill (plain file names). The bookkeeping skill's `life.mjs` does every sum, so
     * the model never does arithmetic; the skill calls it as `node "$DSH_HOME/skills/dshm-bookkeeping/life.mjs"`.
     */
    val files: Map<String, List<String>> = mapOf("dshm-bookkeeping" to listOf("life.mjs"))

    fun assetPath(skill: String, file: String): String = "$ASSET_DIR/$skill/$file"

    /**
     * How the bookkeeping skill runs its script. `$DSH_HOME` stays a variable: the guard plugin asks the owner before a command
     * that names a path outside the workspace, and dsh's home is one, so a spelled-out path would ask on every entry.
     */
    private const val LIFE = "node \"\$DSH_HOME/skills/dshm-bookkeeping/life.mjs\""

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
            "Novel mode: write a long story chapter by chapter, keeping a story bible (setting, characters, outline, progress) in files so names, looks, abilities, timeline and open threads stay consistent.",
            """
            # 小說模式
            長篇最常壞在「寫到後面忘了前面」：角色眼睛顏色變了、伏筆沒收、時間線亂掉。所以這本書的記憶放在檔案裡，不靠對話記憶。

            ## 每本書一個資料夾：`小說/<書名>/`
            - `設定.md`：世界觀、文風、規則，以及寫作偏好（每章字數、第幾人稱、時態、每章結尾要不要留鉤子、寫完要不要自動檢查）。沒寫的用預設：2000–3000 字、照開頭的寫法、要留鉤子、要檢查。
            - `角色.md`：每個角色一節：名字和稱呼、外貌、個性和說話方式、能力、和其他人的關係、目前狀態。
            - `大綱.md`：每章一兩行。主人沒給就先擬一份，請他看過再寫。
            - `進度.md`：每章一行摘要（第 N 章：發生了什麼），加上「未收的伏筆」和「角色目前在哪、知道什麼」。
            - `章節/第N章.md`：每章的完整正文。
            第一次用：資料夾不存在就建，把主人給的設定、角色、書名填進去（對話開頭的設定卡也算）。

            ## 寫一章的步驟
            1. 先讀 `設定.md`、`大綱.md` 這一章那行、`進度.md`（最近三章摘要和未收的伏筆），以及這章會出場角色在 `角色.md` 的那節。
            2. 寫這一章。回覆的第一行是章名（例如「第三章　霧裡的燈」），接著直接是正文，不加前言或解說：書架會把這則回覆當成一章。
            3. 寫完把正文存成 `章節/第N章.md`，在 `進度.md` 加這章一行摘要，更新伏筆（新埋的、收掉的）和角色狀態；有新角色或新設定就補進 `角色.md`、`設定.md`。
            4. 檢查（`設定.md` 沒關掉的話）：對照角色的外貌、稱呼、能力，時間和地點的順序，還有誰知道什麼。發現矛盾就先改正文再交出去，不要另外解釋。

            ## 主人可能會說
            - 「接著寫」「寫第 N 章」：照上面的步驟寫下一章。
            - 「這章重寫」「改成⋯⋯」：整章重寫，覆蓋 `章節/第N章.md`，`進度.md` 那行跟著改。
            - 「擴寫」「潤色」：保留劇情，只改寫法。
            - 「列大綱」「改大綱」：只動 `大綱.md`，給他看。
            - 「檢查一致性」：讀全部章節，列出矛盾（第幾章、哪一句、和什麼衝突）和建議改法，不要直接改。
            - 「改設定」（例如「每章 4000 字」「改第一人稱」）：改 `設定.md`，之後照新的寫。
            - 拆書：主人給一段別人的文章，分析它的結構、節奏、文風，存成 `參考/<名稱>.md`，之後主人說要學就照著寫。原文不要整段抄進正文。
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
            "Life records: expenses and income, food and calories, home workouts, body weight and Uber driver weekly statements, kept as CSV files in 生活/; every total, budget, split and calorie sum comes from the life.mjs script, never from mental arithmetic.",
            """
            # 生活紀錄（記帳、吃了什麼、運動、體重）
            使用者隨口說的花費、收入、吃的東西、運動和體重，都記在工作區的 `生活/` 資料夾（一般的 CSV，他可以自己打開、匯出）。他想知道今天、這個月的狀況時，給他看結果。

            所有的數字都由工具算：加總、剩多少、比例、分桶、熱量、和上個月比。自己心算的數字容易錯，不寫進回覆。
            ```
            $LIFE <指令>
            ```
            照這樣寫 `${'$'}DSH_HOME`，不要換成展開後的完整路徑（完整路徑每次都會跳出權限詢問）。工具印出的句子可以直接用在回覆裡；加 --json 會輸出 JSON。

            ## 記下來
            一句話常常有好幾筆：「早餐蛋餅 45、午餐雞腿便當 120、晚上珍奶 60」是三筆帳，吃的東西也同時記熱量。全部放進同一次 add（JSON 陣列）：
            ```
            $LIFE add '[{"表":"帳","金額":120,"分類":"午餐","內容":"雞腿便當"},{"表":"熱量","餐別":"午餐","內容":"雞腿便當","熱量":[700,850],"蛋白質":35}]'
            ```

            - 帳：金額（正數）、分類、內容；類型是 支出（沒寫就是）、收入 或 轉帳；可以加 帳戶、桶、日期（今天、昨天、11/14）、時間、備註。分類從設定裡的清單挑最接近的。
            - 熱量：餐別、內容、熱量 [下限, 上限]，可以加 蛋白質、脂肪、碳水（公克）和 份數。熱量給誠實的範圍：一個便當 700–850 比 783 誠實，不確定就放寬。我的食物庫有的東西可以不給熱量，工具會用使用者自己的數字。
            - 健身：動作、組數、次數或秒數（15 或 "60秒"）。
            - 體重：體重（公斤），可以加體脂（%）。
            金額、日期或吃了什麼聽不清楚，先問一句再記。內容裡有單引號時，改用 <<'EOF' 把 JSON 從 stdin 傳進去。

            記好之後，回覆照工具印的確認行，一筆一行，最後一句告訴他打錯了說「刪掉」就好。工具印出的記帳卡片區塊放在回覆最後，App 會把它變成卡片（使用者看不到區塊本身），像這樣：

            <!--dshm-card ledger
            項目: 雞腿便當
            金額: -120
            類別: 午餐
            本月支出: -5,480
            -->

            工具沒有記、而是說：
            - 同一天已經有一模一樣的：問使用者是不是重複。他說真的又買了一次，才加 --force 再記。
            - 舊格式的檔案（先前的記帳技能寫的）：問他要不要升級，舊資料會備份；同意了才執行 migrate --yes，再記一次。
            - 某個欄位看不懂：照工具說的改好再試。
            工具印的「提示」也轉告使用者：某樣東西記過好幾次，問他要不要存進「我的食物庫」（food learn 名稱），之後就用他自己的數字；分類不在清單裡，問他要不要加進去。

            ## 刪掉、改
            - 「刪掉」「剛剛那筆不對」：undo 編號（編號在確認行裡；找不到就用 recent 看最近幾筆）。
            - 「改成 150」「那是昨天的」：edit 編號 '{"金額":150}'、edit 編號 '{"日期":"昨天"}'。
            舊紀錄只有使用者明說才動。

            ## 看結果
            - 今天花多少、還能吃多少：today（或 today 昨天）
            - 這個月、月結、跟上個月比：month（或 month 2026-10）
            - 某樣東西花了多少：find 咖啡 --month 2026-11
            - 收入怎麼分、桶還剩多少：buckets（或 buckets --income 30000）
            回覆挑重點，不用整段貼；月結那一句可以直接用。

            ## 每個人都不一樣，什麼都能改
            settings 看全部設定；set 改，可以一次改好幾個：
            - 預算：set 每月預算 15000 分類預算.手搖飲 1500
            - 分類、帳戶、餐別：set 支出分類 +寵物、set 支出分類 -宵夜、set 帳戶 +郵局、set 預設帳戶 郵局
            - 桶（收入照比例分，例如生活費 92%、娛樂費 8%）：set 桶 '[{"名稱":"生活費","比例":92},{"名稱":"娛樂費","比例":8}]'；哪個分類花哪個桶：set 分類對應桶.手搖飲 娛樂費
            - 熱量目標：填了身高、體重、年齡、性別、活動量（久坐、輕度、中度、高度）、目標（減重、維持、增肌）會自動算，例如 set 身體.身高 165 身體.體重 60 身體.年齡 28 身體.性別 女；也可以直接 set 熱量目標.熱量 1800
            - 功能開關：set 功能.熱量 false（關掉的就不記也不顯示）；外送工具預設關著，傳 Uber 結算單時照樣能算
            - 每天提醒：set 提醒.開啟 true 提醒.時間 21:30，再用排程工具建立每天這個時間的提醒；關掉時把排程也刪掉
            設定檔是 `生活/設定.json`，使用者也可以自己改。

            ## 飲食建議的分寸
            建議溫和、做得到。不建議斷食、每天低於 1200 大卡或低於基礎代謝、催吐、瀉藥、一週掉超過 1 公斤這類做法；使用者想吃很少時，說明風險，建議先問醫師或營養師。懷孕、生病、未成年或提到飲食困擾時，只幫忙記錄，不給減重建議。

            ## Uber 外送週結算
            使用者傳 Uber 的週結算 PDF 時，用 read 讀出全部文字，存成 `生活/外送/結算-2026-11-16.txt`（日期用那一週的最後一天），再執行 uber --file 生活/外送/結算-2026-11-16.txt。
            工具會給期初餘額、收入（車費、其他收入、小費）、收的現金、期末餘額（負的就是要轉給 Uber 的錢），並核對加總；對不上的地方照實告訴使用者，不要自己猜數字。
            外送法的每單保底只是估算，回覆時要說是估算；最低時薪、每單 45 元這些數字可以改（set 外送.最低時薪 196、set 外送.每單保底 45）。工具最後給的記帳指令，問過使用者再執行。

            ## 隱私
            這些是使用者的私人資料：只放在工作區，不寫進記憶，不傳到網路上。
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

    /**
     * The description is a double-quoted YAML scalar: written plain, one with ": " in it (「Deep research: many ...」) is not
     * valid YAML, and dsh ignores the whole skill file (six of the thirteen skills until 2026-11-15, among them every compose
     * mode's; DSH on the phone: 「dshm-research 這個技能還是不存在」).
     */
    private fun skill(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: ${DshPatch.q(description.replace("\n", " "))}\n---\n\n" + body.trimIndent() + "\n"

    /** The frontmatter's description as dsh will read it (the text inside the quotes); null when it is not a quoted scalar. */
    internal fun descriptionOf(skillText: String): String? =
        Regex("""^---\nname: [a-z0-9-]+\ndescription: "((?:[^"\\]|\\.)*)"\n---\n""").find(skillText)?.groupValues?.get(1)

    /**
     * Writes every app skill under [dshHome]`/skills` with the files it ships ([files], opened through [asset] by [assetPath]),
     * removes every other file inside a `dshm-*` folder, and removes `dshm-*` folders the app no longer ships. A shipped file whose
     * asset cannot be read keeps the copy from an earlier start. Only changed files are written (a temporary file, then a rename).
     * Never throws.
     */
    fun install(dshHome: File, asset: (String) -> InputStream?) {
        runCatching {
            val root = File(dshHome, "skills").apply { mkdirs() }
            for ((name, text) in all) runCatching {
                val dir = File(root, name).apply { mkdirs() }
                writeIfChanged(File(dir, "SKILL.md"), text.toByteArray(Charsets.UTF_8))
                val shipped = files[name].orEmpty()
                for (file in shipped) {
                    val bytes = runCatching { asset(assetPath(name, file))?.use { it.readBytes() } }.getOrNull() ?: continue
                    writeIfChanged(File(dir, file), bytes)
                }
                val keep = shipped.toSet() + "SKILL.md"
                dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
            }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name !in all }?.forEach { it.deleteRecursively() }
        }
    }

    private fun writeIfChanged(file: File, bytes: ByteArray) {
        if (file.isFile && file.readBytes().contentEquals(bytes)) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
