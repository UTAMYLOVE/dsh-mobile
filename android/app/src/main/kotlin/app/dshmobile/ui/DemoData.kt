package app.dshmobile.ui

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import app.agency.core.agent.Attachment
import app.agency.core.agent.Card
import app.agency.core.agent.CompanyApi
import app.agency.core.agent.SayResult
import app.agency.core.agent.Sticker
import app.agency.core.agent.Today
import app.agency.core.plan.Adjustment
import app.agency.core.plan.DayPlan
import app.agency.core.plan.DayType
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.Screen
import app.agency.core.plan.Tap
import app.agency.core.stage.Feature
import app.agency.core.store.Message
import app.agency.core.store.Role
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Invented data for previews, tests and the screenshots. Nothing here is a real person's number: a generic lunch box, a
 * trainee called 阿星. The same classes stand in for the real implementations when the application provides none.
 */

/**
 * A [CompanyApi] that exercises every card and every plan status. [calls] records what the UI did, for tests.
 * [allCards] adds one message per card kind; [failedMessage] adds one message that was not sent.
 */
class DemoCompany(
    var ready: Boolean = true,
    allCards: Boolean = false,
    failedMessage: Boolean = false,
    /** Makes the next [say] fail (once). */
    var failNext: Boolean = false,
    private val pageSize: Int = 60,
) : CompanyApi {
    val calls = CopyOnWriteArrayList<String>()
    private val store = CopyOnWriteArrayList<Message>()
    private var nextId = 1L
    private val base = Instant.parse("2026-11-28T04:30:00Z")

    init {
        add(Role.MANAGER, "早，簽到好了。今天精神幾分？", listOf(Card.Score("check_in", picked = 4)))
        add(Role.OWNER, "午餐吃了烤雞腿便當")
        add(
            Role.MANAGER, "",
            listOf(Card.MealCard(11, "午餐　記好了", 650, 40.0, 650, 2100, 40.0, 110, estimated = false, needsPhoto = false)),
        )
        add(Role.MANAGER, "晚餐照常吃，不用少。")
        if (allCards) {
            add(Role.NOTE, "✓ 已記：喝水半壺")
            add(Role.MANAGER, "今天精神再給一次分數。", listOf(Card.Score("mood")))
            add(
                Role.MANAGER, "估的數字我抓嚴一點。",
                listOf(
                    Card.MealCard(
                        12, "路上那一餐　記好了", 520, 22.5, 1090, 2100, 62.5, 110, estimated = true, needsPhoto = true,
                        warnings = listOf("這一餐比單餐上限多了 40 大卡"),
                    ),
                ),
            )
            add(
                Role.MANAGER, "從明天起，早餐想加一顆蛋，好嗎？",
                listOf(Card.Proposal(21, "早餐加一顆蛋", "蛋白質多 6 克，熱量多 70 大卡。")),
            )
            add(
                Role.MANAGER, "這幾支影片你看看。",
                listOf(
                    Card.Videos(
                        listOf(
                            Card.Video(31, "聲樂", "換氣的基本功", "https://example.org/a", 12),
                            Card.Video(32, "舞蹈", "律動入門", "https://example.org/b", null),
                        ),
                    ),
                ),
            )
            add(Role.MANAGER, "附近的幾家。", listOf(Card.Places(listOf(Card.Place("小樹便當", "https://example.org/p", "雞胸便當，有標熱量")))))
            add(Role.MANAGER, "要開始就按這個。", listOf(Card.Open("TIMER", "CORE", "開始核心訓練")))
            add(Role.MANAGER, "", listOf(Card.Summary("今天到現在", listOf("吃了 650 大卡", "喝水半壺", "完成 3／8"))))
            add(Role.MANAGER, "", listOf(Card.Ceremony("stage", "第 2 階段開始了", "簽到簽退、三餐、喝水、核心和肌力都開了。")))
            add(Role.OWNER, "", listOf(Card.Photo("demo-1", "晚餐")))
            add(Role.OWNER, "", listOf(Card.File("菜單", 1280)))
        }
        if (failedMessage) {
            add(Role.OWNER, "我吃完晚餐了", meta = buildJsonObject { put("failed", true) })
        }
    }

    private fun add(role: Role, text: String, cards: List<Card> = emptyList(), meta: JsonObject = JsonObject(emptyMap())): Message {
        val m = Message(nextId, base.plusSeconds(nextId * 60), role, text, cards, meta)
        nextId++
        store.add(m)
        return m
    }

    private fun item(
        id: String, feature: Feature, title: String, status: ItemStatus, tap: Tap, at: String? = null, until: String? = null,
        detail: String = "", counts: Boolean = true,
    ) = PlanItem(id, feature, title, at?.let(LocalTime::parse), until?.let(LocalTime::parse), status, counts, tap, detail)

    private val dinner = item(
        "meal:DINNER", Feature.MEALS, "晚餐，拍張照", ItemStatus.PENDING, Tap.Open(Screen.CAMERA, "DINNER"), at = "18:20",
        detail = "18:20",
    )

    override fun today(): Today {
        val items = listOf(
            item("check_in", Feature.CHECK_IN, "簽到", ItemStatus.DONE, Tap.Open(Screen.CHECK_IN), "06:40", detail = "7:05"),
            item("lesson:1", Feature.LESSONS, "理論課一支", ItemStatus.DONE, Tap.Open(Screen.LESSONS), detail = "考過了"),
            item("meal:FIRST", Feature.MEALS, "午餐", ItemStatus.DONE, Tap.Open(Screen.CAMERA, "FIRST"), "11:40", detail = "650 大卡"),
            dinner,
            item(
                "water", Feature.WATER, "喝水一壺", ItemStatus.LATE,
                Tap.Direct("log_water", buildJsonObject { put("bottles", 1) }), until = "19:50", detail = "半壺",
            ),
            item("ride:out", Feature.RIDE, "出門跑單", ItemStatus.PENDING, Tap.Direct("ride_out", JsonObject(emptyMap())), until = "19:50", detail = "19:50 前"),
            item("task:CORE", Feature.CORE, "收工後訓練", ItemStatus.PENDING, Tap.Open(Screen.TIMER, "CORE"), detail = "收工後"),
            item("task:SUNSCREEN", Feature.SUNSCREEN, "擦防曬", ItemStatus.OFF, Tap.Say("我擦好防曬了"), detail = "今天休息", counts = false),
            item("task:CARDIO", Feature.CARDIO, "體能", ItemStatus.MISSED, Tap.Say("我做完體能了"), detail = "昨天"),
            item("check_out", Feature.CHECK_IN, "簽退", ItemStatus.PENDING, Tap.Open(Screen.CHECK_OUT), "23:30"),
        )
        val plan = DayPlan(
            day = LocalDate.of(2026, 11, 28), type = DayType.CLASS, items = items, next = dinner,
            adjustments = listOf(Adjustment("第一餐往後挪了 30 分鐘", "昨天比較晚睡")),
            riding = false, restDay = false, unwell = false, done = 3, total = 8,
        )
        return Today(
            stageLabel = "第 2 階段 · 第 3 天",
            stickers = listOf(
                Sticker("eaten", "今天吃了", "650 大卡"),
                Sticker("water", "喝水", "半壺"),
                Sticker("done", "今天完成", "3／8"),
            ),
            plan = plan,
            chips = listOf("吃完了", "喝完一壺", "我出門了"),
            ready = ready,
        )
    }

    override fun messages(limit: Int, beforeId: Long?): List<Message> {
        val all = store.toList().filter { beforeId == null || it.id < beforeId }
        return all.takeLast(minOf(limit, pageSize))
    }

    override suspend fun say(text: String, attachments: List<Attachment>): SayResult {
        calls += "say:$text:${attachments.size}"
        if (failNext) {
            failNext = false
            add(Role.OWNER, text, meta = buildJsonObject { put("failed", true) })
            return SayResult.NotSent("網路不通")
        }
        add(Role.OWNER, text)
        add(Role.MANAGER, "收到，$text。")
        return SayResult.Ok
    }

    override suspend fun resend(messageId: Long): SayResult {
        calls += "resend:$messageId"
        val i = store.indexOfFirst { it.id == messageId }
        if (i >= 0) {
            store[i] = store[i].copy(meta = JsonObject(emptyMap()))
            add(Role.MANAGER, "收到。")
        }
        return SayResult.Ok
    }

    override fun direct(tool: String, args: JsonObject): String {
        calls += "direct:$tool:$args"
        val line = "✓ 已記：$tool"
        add(Role.NOTE, line)
        return line
    }

    override fun item(id: String): PlanItem? = today().plan.items.firstOrNull { it.id == id }

    override suspend fun answerCard(messageId: Long, cardIndex: Int, answer: String): SayResult {
        calls += "card:$messageId:$cardIndex:$answer"
        val i = store.indexOfFirst { it.id == messageId }
        if (i >= 0) {
            val m = store[i]
            val card = m.cards.getOrNull(cardIndex)
            val newCard = when (card) {
                is Card.Score -> card.copy(picked = answer.toIntOrNull())
                is Card.Proposal -> card.copy(answer = if (answer == "yes") Card.Answer.YES else Card.Answer.NO)
                is Card.Videos -> card
                else -> card
            }
            if (newCard != null) {
                store[i] = m.copy(cards = m.cards.toMutableList().also { it[cardIndex] = newCard })
            }
        }
        return SayResult.Ok
    }

    override fun rollover() {
        calls += "rollover"
    }
}

class DemoMedia : MediaUi {
    private fun photo(id: String) = Attachment.Photo(id, ByteArray(0), fromGallery = false)
    override suspend fun takePhoto(purpose: String): Attachment.Photo = photo("demo-cam")
    override suspend fun pickPhotos(): List<Attachment.Photo> = listOf(photo("demo-g1"), photo("demo-g2"))
    override suspend fun pickFile(): Attachment.File = Attachment.File("菜單", "雞腿便當 650 大卡")
    override suspend fun fromUris(uris: List<Uri>): List<Attachment> = uris.map { photo(it.toString()) }
    override suspend fun thumbnail(photoId: String): ImageBitmap? = null
}

class DemoLessons : LessonsUi {
    val rows = mutableListOf(
        LessonRow(1, "聲樂", "換氣的基本功", 12, LessonState.PROPOSED),
        LessonRow(2, "舞蹈", "律動入門", 9, LessonState.APPROVED),
        LessonRow(3, "表情與鏡頭", "對著鏡頭笑", 7, LessonState.WATCHED),
        LessonRow(4, "禮儀", "第一次見面怎麼說", null, LessonState.PASSED),
        LessonRow(5, "姿勢管理", "站姿的三個重點", 10, LessonState.NOTED),
        LessonRow(6, "聲樂", "上週那支的複習", 12, LessonState.REVIEW_DUE),
    )
    val log = CopyOnWriteArrayList<String>()
    override fun lessons(): List<LessonRow> = rows.toList()
    override fun approve(lessonId: Long, yes: Boolean) {
        log += "approve:$lessonId:$yes"
        val i = rows.indexOfFirst { it.id == lessonId }
        if (i < 0) return
        if (yes) {
            val r = rows[i]
            rows[i] = LessonRow(r.id, r.subject, r.title, r.minutes, LessonState.APPROVED)
        } else {
            rows.removeAt(i)
        }
    }

    override fun watch(lessonId: Long) {
        log += "watch:$lessonId"
    }

    override suspend fun quiz(lessonId: Long): QuizView = QuizView(
        lessonId, rows.firstOrNull { it.id == lessonId }?.title ?: "",
        listOf(
            QuizQuestion("換氣最好用哪裡？", listOf("肩膀", "肚子", "喉嚨", "嘴巴")),
            QuizQuestion("練習前先做什麼？", listOf("伸展", "吃飯", "睡覺", "跑步")),
            QuizQuestion("每次練習大約多久？", listOf("十分鐘", "三小時", "一分鐘", "整天")),
        ),
    )

    override fun grade(lessonId: Long, answers: List<Int>): QuizResult {
        val key = listOf(1, 0, 0)
        val wrong = key.indices.filter { answers.getOrNull(it) != key[it] }
        log += "grade:$lessonId:${answers.joinToString(",")}"
        return QuizResult(key.size - wrong.size, key.size, key.size - wrong.size >= 2, wrong)
    }

    override fun saveNote(lessonId: Long, note: String): Boolean {
        log += "note:$lessonId"
        return note.length >= 10
    }
}

class DemoTimer : TimerUi {
    val finished = CopyOnWriteArrayList<String>()
    override fun plan(task: String): TimerPlan = TimerPlan(
        task, "核心訓練", rounds = 2,
        moves = listOf(TimerMove("棒式", 30), TimerMove("側棒式", 20), TimerMove("仰臥抬腿", 20)), restSeconds = 15,
    )

    override fun finish(task: String, rounds: Int, minutes: Int, feel: String, awaySeconds: Int) {
        finished += "$task:$rounds:$minutes:$feel:$awaySeconds"
    }
}

class DemoMemo : MemoUi {
    val rows = mutableListOf(
        MemoRow(1, "買洗髮精", null, false),
        MemoRow(2, "週五交報告", "週五 18:00", false),
        MemoRow(3, "打電話給房東", null, true),
    )

    override fun list(): List<MemoRow> = rows.toList()
    override fun setDone(id: Long, done: Boolean) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].let { MemoRow(it.id, it.text, it.due, done) }
    }

    override fun remove(id: Long) {
        rows.removeAll { it.id == id }
    }

    override fun add(text: String, dueAt: String?) {
        rows += MemoRow((rows.maxOfOrNull { it.id } ?: 0) + 1, text, dueAt, false)
    }
}

class DemoBody : BodyUi {
    val log = CopyOnWriteArrayList<String>()
    override fun last(): List<String> = listOf("上次體重 71.3 公斤", "腰圍 79 公分")
    override fun timeline(): List<BodyShotRow> = listOf(
        BodyShotRow("10/05", "demo-b1", "71.9 公斤"), BodyShotRow("11/02", "demo-b2", "71.3 公斤"), BodyShotRow("11/23", "demo-b3", "70.8 公斤"),
    )
    override suspend fun log(kg: Double?, waistCm: Double?, photo: Attachment.Photo?): String {
        log += "body:$kg:$waistCm:${photo != null}"
        return "✓ 已記體重"
    }
}

class DemoMoney : MoneyUi {
    val log = CopyOnWriteArrayList<String>()
    override fun summary(): List<String> = listOf("這個月生活 3150 元", "玩樂 640 元", "收入 9600 元")
    override fun add(amount: Int, bucket: String, note: String): String {
        log += "money:$amount:$bucket:$note"
        return "✓ 已記 $amount 元"
    }
}

class DemoTrainee : TraineeUi {
    override fun card() = TraineeCard(
        stageName = "阿星", colorHex = "#88AAFF", level = "C", stageLabel = "第 2 階段 · 第 3 天", daysSinceJoin = 17,
        photocards = listOf(
            Photocard("連續七天滿分", "11/21", null), Photocard("第一支理論課", "11/12", null),
            Photocard("入社紀念", "11/11", null),
        ),
        diary = (1..17).map { DiaryDay("$it", it % 3 != 0) },
    )
}

class DemoSinging(private val key: Boolean = true) : SingingUi {
    val log = CopyOnWriteArrayList<String>()
    private val refs = mutableListOf<SongRefRow>()
    private var strict = true
    private val modeWord get() = if (strict) "嚴格" else "一般"
    override suspend fun start() = true
    override suspend fun stop() = SongResult(
        82, "中音到高音", 9, "適中", "音準穩，長句尾巴有點虛。",
        shift = "整首比原調高 2 個半音，可以把調升 2 個半音再唱", chart = null, compare = listOf("比對（$modeWord）：音準 80%"),
    )

    override fun history(): List<SongResult> = emptyList()
    override fun references(): List<SongRefRow> = refs.toList()
    override suspend fun importReference(): RefImport {
        refs += SongRefRow("ref${refs.size + 1}", "示範歌", "旋律檔")
        return RefImport("示範歌", null)
    }

    override fun chooseReference(id: String?) { log += "ref:$id" }
    override fun setStrict(strict: Boolean) { this.strict = strict; log += "strict:$strict" }
    override fun lyricsAvailable() = key
    override suspend fun checkLyrics(lyrics: String, consent: Boolean): LyricsOutcome {
        log += "lyrics:$consent"
        return if (!consent) LyricsOutcome(emptyList(), "沒有同意，錄音沒有送出去。") else LyricsOutcome(listOf("咬字：歌詞有 90% 聽得出來"), null)
    }
}

class DemoPhotos(private val spotOpen: Boolean = true, private val mealRecorded: Boolean = false) : PhotoUi {
    val log = CopyOnWriteArrayList<String>()
    override fun spot(photoId: String): String? = if (spotOpen) "✓ 突擊檢查：晚餐照片準時交了".also { log += "spot:$photoId" } else null
    override fun mealPhoto(slot: String, photoId: String, fromGallery: Boolean): String? =
        if (mealRecorded) "✓ 補上照片：$slot".also { log += "meal:$slot:$photoId:$fromGallery" } else null
    override fun bodyPhoto(photoId: String): String? = "✓ 已存全身對比照".also { log += "body:$photoId" }
}

class DemoMeals(private val table: List<FoodRow> = listOf(
    FoodRow("雞腿便當", 650, 40.0), FoodRow("地瓜", 130, 2.0), FoodRow("無糖豆漿", 90, 8.0),
)) : MealUi {
    val log = CopyOnWriteArrayList<String>()
    override fun foods(): List<FoodRow> = table
    override fun log(slot: String, picks: List<FoodRow>, photo: Attachment.Photo?): String? {
        log += "meal:$slot:${picks.joinToString(",") { it.name }}:${photo != null}"
        return "✓ 已記：$slot"
    }
}

class DemoPractice : PracticeUi {
    val log = CopyOnWriteArrayList<String>()
    private var done = false
    override fun realTask() = RealTaskView("這週跟一位你已經認識的朋友一起吃一頓飯", done)
    override fun realTaskDone(): String { done = true; log += "real:done"; return "✓ 真人任務完成" }
}

class DemoEval : EvalUi {
    val confirmed = CopyOnWriteArrayList<String>()
    override fun view(monthly: Boolean) = EvalView(
        if (monthly) "本月評鑑" else "本週評鑑",
        listOf(
            EvalItem("song", "唱歌", true, 4, "音準比上週穩"),
            EvalItem("dance", "舞蹈", false, null, ""),
            EvalItem("expr", "表情", true, 3, "笑得有點僵"),
        ),
        confirmed = false,
    )

    override suspend fun record(monthly: Boolean, key: String) = true
    override fun confirm(monthly: Boolean, scores: Map<String, Int>) {
        confirmed += scores.toString()
    }
}

class DemoOnboarding : OnboardingUi {
    val log = CopyOnWriteArrayList<String>()
    override suspend fun saveKeys(claude: String, deepseek: String): Boolean {
        log += "keys"
        return claude.isNotBlank()
    }

    override suspend fun readDocument(text: String) = ImportOutcome(
        listOf(
            SummaryGroup("作息", listOf("起床 06:40", "睡覺 01:00")),
            SummaryGroup("飲食", listOf("每天 2100 大卡", "蛋白質 110 克")),
        ),
        missing = listOf("跑單收入"), failure = null,
    )

    override suspend fun confirm(stageName: String, memberColorHex: String, callName: String) {
        log += "confirm:$stageName:$memberColorHex"
    }

    override fun permissions() = PermissionState(notifications = false, exactAlarms = true)
    override fun requestNotifications() {
        log += "notify"
    }

    override fun openAlarmSettings() {
        log += "alarm"
    }

    override fun openBatteryGuide() {
        log += "battery"
    }

    override fun finish() {
        log += "finish"
    }
}

class DemoSettings : SettingsUi {
    val saved = CopyOnWriteArrayList<Map<String, String>>()
    private val memoryLines = mutableListOf("喜歡吃烤地瓜", "週三晚上有課")
    override fun rulesGroups() = listOf(
        RuleGroup("作息", listOf(RuleField("wake", "起床時間", "06:40", "例如 06:40"), RuleField("bedtime", "簽退時間", "01:00"))),
        RuleGroup("飲食", listOf(RuleField("kcal", "每天熱量", "2100"), RuleField("protein", "每天蛋白質", "110"))),
    )

    override fun saveRules(changes: Map<String, String>): String? {
        saved += changes
        return null
    }

    override fun model() = ModelChoice(useDeepSeekForChat = false, tier = "balanced", claudeKeySet = true, deepSeekKeySet = false)
    override fun setModel(useDeepSeekForChat: Boolean, tier: String) {}
    override suspend fun saveKey(which: String, key: String) = key.isNotBlank()
    override fun spend() = SpendView("4 元", "86 元", 300)
    override fun setCap(yuan: Int?) {}
    override fun memory(): List<String> = memoryLines.toList()
    override fun forget(line: String) {
        memoryLines.remove(line)
    }

    override fun testNotification() {}
    override fun openBatteryGuide() {}
    override fun update() = UpdateView("1.0.0", "1.0.1")
    override fun openUpdate() {}
    override fun licenses() = listOf(LicenseView("字體 · 霞鶩文楷", "本字體以開放字型授權使用。"))
}

class DemoBackup : BackupUi {
    override suspend fun export(passphrase: String) = "備份好了，檔案在下載資料夾。"
    override suspend fun preview(passphrase: String): String? = if (passphrase.isBlank()) null else "共 120 筆紀錄、40 張照片"
    override suspend fun apply() = "已經還原。"
}

class DemoOldData : OldDataUi {
    override fun available() = true
    override fun messages() = listOf(
        OldMessage("你", "今天吃了便當", "10/02 11:52"),
        OldMessage("小葵", "好，記下了。", "10/02 11:53"),
    )

    override fun delete() = true
}

/** In-memory panel state. */
class MemoryPanelPrefs : PanelPrefs {
    private val map = HashMap<String, Boolean>()
    override fun get(name: String, default: Boolean) = map[name] ?: default
    override fun set(name: String, open: Boolean) {
        map[name] = open
    }
}

/** The whole demo bundle. */
fun demoGraph(company: DemoCompany = DemoCompany(), panels: PanelPrefs? = null) = UiGraph(
    company = company, media = DemoMedia(), lessons = DemoLessons(), timer = DemoTimer(), memo = DemoMemo(),
    body = DemoBody(), money = DemoMoney(), trainee = DemoTrainee(), singing = DemoSinging(), eval = DemoEval(),
    onboarding = DemoOnboarding(), settings = DemoSettings(), backup = DemoBackup(), oldData = DemoOldData(), panels = panels,
    photos = DemoPhotos(), meals = DemoMeals(), practice = DemoPractice(),
)
