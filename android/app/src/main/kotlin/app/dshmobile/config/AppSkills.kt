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
    )

    private fun skill(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: ${description.replace("\n", " ")}\n---\n\n" + body.trimIndent() + "\n"

    /**
     * Writes every app skill under [dshHome]`/skills` and removes `dshm-*` folders the app no longer ships. [bundled] are whole
     * skill folders from the APK (assets/dsh-skills: the superpowers port), relative path to bytes; they are written the same
     * way, file by file, and a folder whose files changed is rewritten. Never throws.
     */
    fun install(dshHome: File, bundled: Map<String, Map<String, ByteArray>> = emptyMap()) {
        runCatching {
            val root = File(dshHome, "skills").apply { mkdirs() }
            for ((name, files) in bundled) {
                val dir = File(root, name)
                val current = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it }
                val same = current.keys == files.keys && files.all { (rel, bytes) -> current.getValue(rel).readBytes().contentEquals(bytes) }
                if (same) continue
                val tmp = File(root, ".$name.tmp").apply { deleteRecursively() }
                for ((rel, bytes) in files) File(tmp, rel).apply { parentFile?.mkdirs(); writeBytes(bytes); if (rel.startsWith("scripts/")) setExecutable(true) }
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) { tmp.copyRecursively(dir, overwrite = true); tmp.deleteRecursively() }
            }
            for ((name, text) in all) {
                val dir = File(root, name).apply { mkdirs() }
                val file = File(dir, "SKILL.md")
                if (!file.isFile || file.readText() != text) {
                    val tmp = File(dir, "SKILL.md.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name !in all && it.name !in bundled }?.forEach { it.deleteRecursively() }
        }
    }
}
