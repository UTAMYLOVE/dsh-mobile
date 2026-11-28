package app.agency.core.sim

import app.agency.core.agent.Attachment
import app.agency.core.agent.Card
import app.agency.core.agent.Company
import app.agency.core.agent.Providers
import app.agency.core.agent.SayResult
import app.agency.core.agent.Today
import app.agency.core.agent.tools.Tools
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.LessonResult
import app.agency.core.lessons.LessonState
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.Screen
import app.agency.core.plan.Tap
import app.agency.core.plan.waiting
import app.agency.core.remind.Notice
import app.agency.core.remind.ReminderLog
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.stage.Feature
import app.agency.core.stage.Stages
import app.agency.core.store.Feel
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.Role
import app.agency.core.store.Store
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thrown with the trace so a failing simulation says where it went wrong. */
class SimFailure(message: String) : AssertionError(message)

/**
 * The fourteen-day harness: a store, a moving clock, the company with a [SimModel], a scripted owner, and the invariant checks
 * run at every step ([check]). Every action and every notice goes into [trace]; a failure prints the last part of it.
 */
class Sim(val rules: Rules = Fixtures.rules(), val store: Store = MemStore()) {
    val clock = TestClock().also { it.set(TestClock.MONDAY, "06:00") }
    val model = SimModel()
    val search = SimSearch()
    val company = Company(store, clock, Providers(model, model, search, keyReady = { true }))
    val trace = StringBuilder()
    var log = ReminderLog()
    val notices = ArrayList<Pair<java.time.Instant, Notice>>()
    private var photo = 0
    var dayN = 1
    val day: LocalDate get() = TestClock.MONDAY.plusDays((dayN - 1).toLong())

    fun t(line: String) {
        trace.append("[d$dayN ${Days.minuteOfDay(clock.now(), clock.zone).let { hm(clock.now()) }}] ").append(line).append('\n')
    }

    fun hm(at: java.time.Instant): String = at.atZone(clock.zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    fun fail(why: String): Nothing = throw SimFailure("$why\n---- trace (last 120 lines) ----\n" + trace.lines().takeLast(120).joinToString("\n"))

    fun require(ok: Boolean, why: () -> String) { if (!ok) fail(why()) }

    fun at(time: String) {
        val target = Days.at(day, LocalTime.parse(time), clock.zone)
        require(!target.isBefore(clock.now())) { "clock would go back to $time" }
        clock.instant = target
    }

    fun stage(): Int = Stages.get(store)?.stage ?: 1

    fun today(): Today = company.today()

    fun plan() = today().plan

    fun item(id: String): PlanItem? = plan().items.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- talking and tapping

    fun say(text: String, photo: Boolean = false, gallery: Boolean = false): SayResult {
        model.called.clear()
        val atts = if (photo) listOf(Attachment.Photo("photo${++this.photo}", byteArrayOf(1, 2, 3), gallery)) else emptyList()
        val before = model.problems.size
        val r = runBlocking { company.say(text, atts) }
        t("他：$text → ${model.called} → 小葵：${store.messages(1).lastOrNull()?.takeIf { it.role == Role.MANAGER }?.text ?: r}")
        require(r is SayResult.Ok) { "say($text) was not answered: $r" }
        require(model.problems.size == before) { "model problems: ${model.problems.drop(before)}" }
        check("after say $text")
        return r
    }

    fun direct(tool: String, args: JsonObject): String {
        val line = company.direct(tool, args)
        t("點 $tool $args → $line")
        require(!line.startsWith("沒記到") && !line.startsWith("沒有這個")) { "direct $tool failed: $line" }
        check("after direct $tool")
        return line
    }

    /** Answers the newest card of [type] in the conversation. */
    inline fun <reified C : Card> answer(answer: String): C {
        val msg = store.messages(80).lastOrNull { m -> m.cards.any { it is C } } ?: fail("no ${C::class.simpleName} card to answer")
        val idx = msg.cards.indexOfLast { it is C }
        val r = runBlocking { company.answerCard(msg.id, idx, answer) }
        t("卡片 ${C::class.simpleName} ← $answer → $r")
        require(r is SayResult.Ok) { "answerCard ${C::class.simpleName} $answer: $r" }
        check("after card")
        return msg.cards[idx] as C
    }

    fun photoId(): String = "photo${++photo}"

    // ---------------------------------------------------------------- doing a plan item through its own tap

    /** Carries out [id] the way its tap says, until it is DONE. [chat] picks words over buttons where both work. */
    fun doItem(id: String, chat: Boolean) {
        repeat(8) {
            val i = item(id) ?: fail("item $id vanished")
            if (i.status == ItemStatus.DONE) return
            require(i.status != ItemStatus.OFF && i.status != ItemStatus.MISSED) { "item $id is ${i.status}, cannot be done" }
            act(i, chat)
        }
        fail("item $id is still ${item(id)?.status} after eight tries (dead end)")
    }

    private fun act(i: PlanItem, chat: Boolean) {
        when (val tap = i.tap) {
            is Tap.Say -> {
                require(model.handles(tap.text)) { "plan item ${i.id} says 「${tap.text}」, which the manager cannot act on" }
                say(tap.text)
                // A timer button in her answer is what he taps next.
                store.messages(1).lastOrNull()?.cards?.filterIsInstance<Card.Open>()?.firstOrNull { it.screen == "TIMER" }
                    ?.let { timer(it.arg, null) }
                if (tap.text.contains("真人任務")) say("這週的真人任務做完了")
                if (tap.text.startsWith("幫我找")) {
                    val v = answer<Card.Videos>("approve:" + lastVideos().items.first().lessonId)
                    if (v.items.size > 1) answer<Card.Videos>("decline:" + v.items.last().lessonId)
                }
            }
            is Tap.Direct -> {
                require(Tools.direct(tap.tool) != null) { "plan item ${i.id} taps ${tap.tool}, which is not a direct tool" }
                if (chat) say(chatFor(i)) else direct(tap.tool, tap.args)
            }
            is Tap.Open -> open(i, tap, chat)
        }
    }

    fun lastVideos(): Card.Videos =
        store.messages(80).lastOrNull { m -> m.cards.any { it is Card.Videos } }?.cards?.filterIsInstance<Card.Videos>()?.last()
            ?: fail("no video card")

    private fun chatFor(i: PlanItem): String = when (i.id) {
        "water" -> "喝完一壺了"
        "ride:out" -> "我出門跑單了"
        "ride:back" -> "收工了，今天賺了 1000 元"
        "task:SUNSCREEN" -> "我擦好防曬了"
        else -> fail("no words for ${i.id}")
    }

    private fun open(i: PlanItem, tap: Tap.Open, chat: Boolean) {
        when (tap.screen) {
            Screen.CHECK_IN -> if (chat) {
                say("早安")
                if (item("check_in")?.status != ItemStatus.DONE) answer<Card.Score>("4")
            } else direct("check_in", buildJsonObject { put("score", 3) })
            Screen.CHECK_OUT -> if (chat) say("我要睡了") else direct("check_out", buildJsonObject { put("good", "都做完了"); put("improve", "早點睡"); put("mood", null as Int?) })
            Screen.TIMER -> {
                val rounds = Regex("""(\d+) 輪""").find(i.detail)?.groupValues?.get(1)?.toInt()
                if (chat && i.feature in TRAINING) {
                    say("開始訓練")
                    val open = store.messages(5).flatMap { it.cards }.filterIsInstance<Card.Open>().lastOrNull()
                    require(open != null && open.screen == "TIMER") { "開始訓練 gave no timer button" }
                    timer(open!!.arg, rounds)
                } else timer(tap.arg, rounds)
            }
            Screen.CAMERA -> if (tap.arg == "spot") {
                val line = company.spotAnswer(photoId())
                t("突擊檢查照片 → $line")
                require(line != null) { "spot check photo had nothing to answer" }
                check("after spot photo")
            } else {
                val slot = MealSlot.valueOf(tap.arg)
                val logged = store.entries(day, Kind.MEAL).any { e -> e.read<Meal>().let { it.slot == slot && it.photoId == null } }
                if (logged) {
                    val line = company.mealPhoto(slot, photoId(), fromGallery = true)
                    t("相簿補照片 $slot → $line")
                    require(line != null) { "mealPhoto found no meal" }
                    check("after meal photo")
                } else {
                    // In words the photo comes with the message; by taps he logs first and adds the photo from the gallery.
                    say(mealWords(slot), photo = chat)
                }
            }
            Screen.BODY -> if (tap.arg == "photo") {
                t("全身照 → " + company.bodyPhoto(photoId()))
                check("after body photo")
            } else direct("log_body", buildJsonObject { put("kg", 79.5); put("waist_cm", null as Double?) })
            Screen.EVAL -> evaluation(tap.arg)
            Screen.LESSONS -> lesson(i)
            Screen.QUIZ -> review(tap.arg.toLong())
            else -> fail("plan item ${i.id} opens ${tap.screen}, which the simulation has no page for")
        }
    }

    var foods: Map<MealSlot, String> = emptyMap()

    private val TRAINING = setOf(Feature.CORE, Feature.STRENGTH, Feature.STRETCH, Feature.CARDIO)

    /** Three seconds of a steady 220 Hz tone: a take the analysis can read. */
    private fun tone(): ShortArray = ShortArray(16_000 * 3) { i -> (Math.sin(2 * Math.PI * 220 * i / 16_000.0) * 8000).toInt().toShort() }

    private fun evaluation(arg: String) {
        val song = company.recordSong(tone(), 16_000, "評鑑曲")
        val r = if (arg == "monthly") {
            val body = photoId()
            company.bodyPhoto(body)
            runBlocking {
                company.submitMonthlyEval(app.agency.core.extras.MonthlyInput(song.entryId, "dance-${photoId()}", "rot-${photoId()}", body, 78.0, 80.0, 20, 60))
            }
        } else {
            runBlocking { company.submitWeeklyEval(song.entryId, "dance-${photoId()}", "盡力了") }
        }
        t("評鑑 $arg → ${r::class.simpleName} ${(r as? app.agency.core.extras.EvalResult.Refused)?.reason ?: ""}")
        require(r is app.agency.core.extras.EvalResult.Ok) { "evaluation $arg refused: $r" }
        check("after evaluation")
    }

    fun mealWords(slot: MealSlot): String = when (slot) {
        MealSlot.FIRST -> "第一餐吃了" + (foods[slot] ?: "測試雞腿便當")
        MealSlot.DINNER -> "晚餐吃了" + (foods[slot] ?: "測試雞腿便當")
        MealSlot.NIGHT -> "宵夜吃了" + (foods[slot] ?: "測試豆漿、測試雞胸")
        MealSlot.EXTRA -> "點心吃了測試豆漿"
    }

    fun timer(task: String, rounds: Int?) {
        val line = company.timerDone(task, rounds, 10, Feel.OK, 0)
        t("計時完成 $task $rounds → $line")
        require(line.startsWith("✓")) { "timer $task: $line" }
        check("after timer")
    }

    private fun lesson(i: PlanItem) {
        val n = i.id.removePrefix("lesson:").toInt()
        val slot = company.lessons().today.firstOrNull { it.n == n } ?: fail("no lesson slot $n")
        if (slot.lessonId == null) {
            require(slot.proposed) { "lesson slot $n has no lesson but opens the lessons page" }
            val row = company.lessons().byState[LessonState.PROPOSED].orEmpty().firstOrNull { it.subject == slot.subject }
                ?: fail("lessons page shows no video to decide for ${slot.subject}")
            val r = company.approveLesson(row.id)
            t("課程頁加入 ${row.title} → ${r::class.simpleName}")
            require(r is LessonResult.Ok) { "approve ${row.id}: $r" }
            return
        }
        val id = slot.lessonId!!
        val row = company.lessons().byState.values.flatten().first { it.id == id }
        when (row.state) {
            LessonState.APPROVED -> {
                require(company.openLesson(id) != null) { "lesson $id cannot be opened" }
                clock.plus(Duration.ofMinutes(12))
                val r = company.backFromLesson(id)
                t("看完 ${row.title} → ${r::class.simpleName}")
                require(r is LessonResult.Ok) { "back from $id: $r" }
            }
            LessonState.WATCHED -> {
                val quiz = runBlocking { company.quizFor(id) }
                if (quiz == null) {
                    note(id)
                } else {
                    val res = company.answerQuiz(id, quiz.questions.map { it.answer })
                    t("測驗 ${row.title} → ${res.right}/${res.total}")
                    require(res.passed) { "quiz of $id not passed: $res" }
                }
            }
            LessonState.PASSED -> note(id)
            else -> fail("lesson $id in slot $n is ${row.state}")
        }
        check("after lesson step")
    }

    private fun note(id: Long) {
        val r = company.writeNote(id, "今天學到三個重點，回去還要再練一次。")
        t("筆記 $id → ${r::class.simpleName}")
        require(r is LessonResult.Ok) { "note $id: $r" }
    }

    private fun review(id: Long) {
        val quiz = runBlocking { company.reviewQuizFor(id) } ?: fail("no review quiz for $id")
        val r = company.answerReview(id, quiz.questions.map { it.answer })
        t("複習 $id → ${r.right}/${r.total}")
        require(r.passed) { "review $id: $r" }
        check("after review")
    }

    // ---------------------------------------------------------------- invariants

    private val chipFeature = mapOf(
        "早安" to Feature.CHECK_IN, "我要睡了" to Feature.CHECK_IN, "收工了" to Feature.RIDE, "我出門跑單了" to Feature.RIDE,
        "我吃完了" to Feature.MEALS, "開始訓練" to Feature.CORE, "喝完一壺了" to Feature.WATER, "今天上什麼課" to Feature.LESSONS,
    )

    /** The features whose items must be in today's plan when open and set up. */
    private fun dueToday(f: Feature, d: LocalDate): Boolean = when (f) {
        Feature.CHECK_IN, Feature.MEALS, Feature.WATER, Feature.CORE, Feature.STRENGTH, Feature.STRETCH, Feature.RIDE,
        Feature.LESSONS -> true
        Feature.WEIGHT -> d.dayOfWeek.value == rules.body.weighWeekday
        Feature.MORNING_STRETCH, Feature.VOCAL_BASICS, Feature.POSTURE, Feature.SUNSCREEN, Feature.THEME_WEEK, Feature.REAL_TASK -> true
        Feature.WALK, Feature.DANCE, Feature.VOCAL -> d.dayOfWeek.value !in rules.classWeekdays
        Feature.WEEKLY_EVAL, Feature.BODY_PHOTO -> d.dayOfWeek == java.time.DayOfWeek.SUNDAY
        Feature.MONTHLY_EVAL -> d.dayOfWeek == java.time.DayOfWeek.SUNDAY && d.plusDays(7).month != d.month
        else -> false
    }

    private val doable = setOf(
        Screen.CHECK_IN, Screen.CHECK_OUT, Screen.TIMER, Screen.CAMERA, Screen.BODY, Screen.LESSONS, Screen.QUIZ, Screen.EVAL,
    )

    /** Invariants 1–4 at this moment (6 is checked by the test each morning, 5 by [say]). */
    fun check(where: String) {
        val today = today()
        val plan = today.plan
        val st = stage()
        // 1. nothing of a closed feature
        plan.items.forEach { require(it.feature.openAt(st)) { "$where: plan item ${it.id} of closed ${it.feature}" } }
        today.chips.forEach { c ->
            val f = chipFeature[c] ?: fail("$where: unknown chip 「$c」")
            require(f.openAt(st)) { "$where: chip 「$c」 of closed $f" }
            require(model.handles(c)) { "$where: chip 「$c」 is not something the manager can act on" }
        }
        val stickerKeys = today.stickers.map { it.key }.toSet()
        if (st <= 1) require(stickerKeys == setOf("lessons", "passed", "streak")) { "$where: stage-1 stickers $stickerKeys" }
        else require("done" in stickerKeys && stickerKeys.all { it in setOf("eaten", "water", "done") }) { "$where: stickers $stickerKeys" }
        plan.adjustments.forEach { a ->
            Feature.values().filter { !it.openAt(st) }.forEach { f ->
                require(!a.what.contains(f.label)) { "$where: adjustment 「${a.what}」 names closed ${f.label}" }
            }
        }
        // 2. every open, set-up feature due today has an item, and its tap can be carried out
        Feature.values().filter { it.openAt(st) && RulesCheck.ready(rules, it) && dueToday(it, plan.day) }.forEach { f ->
            require(plan.items.any { it.feature == f }) { "$where: ${f.label} is open and due but has no plan item" }
        }
        plan.items.forEach { i ->
            when (val tap = i.tap) {
                is Tap.Say -> require(model.handles(tap.text)) { "$where: ${i.id} says 「${tap.text}」 that nobody handles" }
                is Tap.Direct -> require(Tools.direct(tap.tool) != null) { "$where: ${i.id} taps unknown ${tap.tool}" }
                is Tap.Open -> require(tap.screen in doable) { "$where: ${i.id} opens ${tap.screen} with no way to finish it" }
            }
        }
        // 3. next is actionable, and null only when nothing actionable is left
        val next = plan.next
        val actionable = plan.items.filter { (it.status == ItemStatus.PENDING || it.status == ItemStatus.LATE) && !it.waiting }
        if (next == null) require(actionable.isEmpty()) { "$where: next is null but ${actionable.map { it.id }} can be done" }
        else {
            require(next.status == ItemStatus.PENDING || next.status == ItemStatus.LATE) { "$where: next ${next.id} is ${next.status}" }
            require(!next.waiting) { "$where: next ${next.id} is waiting (${next.detail})" }
            require(next.feature.openAt(st)) { "$where: next ${next.id} is closed" }
        }
        // 4. reminders
        val before = log
        val d = company.reminders(log)
        log = d.log
        require(d.nextWakeAt.isAfter(clock.now())) { "$where: next wake ${d.nextWakeAt} is not in the future" }
        val state = app.agency.core.stage.Today.state(store, plan.day, rules, Stages.get(store) ?: return, clock.zone)
        val checkInLive = Feature.CHECK_IN.openAt(st) && RulesCheck.ready(rules, Feature.CHECK_IN)
        for (n in d.notices) {
            notices += clock.now() to n
            t("通知 ${n.key} ${if (n.loud) "響" else "靜"}：${n.text}")
            val itemOf = plan.items.firstOrNull { it.id == n.key }
            if (itemOf != null) require(itemOf.feature.openAt(st)) { "$where: notice for closed ${n.key}" }
            if (checkInLive && state.checkIn == null && itemOf != null) {
                require(n.key == "check_in") { "$where: notice ${n.key} before check-in" }
            }
            if (checkInLive && state.checkOut != null) fail("$where: notice ${n.key} after check-out")
            val chase = before.day == plan.day.toString() && before.times(n.key).isNotEmpty()
            if (chase && plan.riding) require(!n.loud) { "$where: chase of ${n.key} is loud while riding" }
        }
    }

    /** Steps the clock to [time] in [stepMinutes] steps, checking at every step. */
    fun walkTo(time: String, stepMinutes: Long = 10) {
        val target = Days.at(day, LocalTime.parse(time), clock.zone)
        while (clock.now().plus(Duration.ofMinutes(stepMinutes)).isBefore(target)) {
            clock.plus(Duration.ofMinutes(stepMinutes))
            check("walking to $time")
        }
        clock.instant = target
        check("at $time")
    }
}
