package app.agency.core.agent

import app.agency.core.agent.tools.CheckInTool
import app.agency.core.agent.tools.LogMoodTool
import app.agency.core.agent.tools.LogTaskTool
import app.agency.core.agent.tools.MemoRow
import app.agency.core.agent.tools.Memos
import app.agency.core.agent.tools.MoneyMath
import app.agency.core.agent.tools.MoneyView
import app.agency.core.agent.tools.RulesDoc
import app.agency.core.agent.tools.Tasks
import app.agency.core.agent.tools.Tools
import app.agency.core.agent.tools.AttachPhotoTool
import app.agency.core.agent.tools.LessonWords
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.base.J
import app.agency.core.extras.BodyPhotos
import app.agency.core.extras.EvalResult
import app.agency.core.extras.EvalSuggester
import app.agency.core.extras.Evaluations
import app.agency.core.extras.Extras
import app.agency.core.extras.MonthlyInput
import app.agency.core.extras.SongBook
import app.agency.core.extras.SongResult
import app.agency.core.extras.SpotChecks
import app.agency.core.extras.SpotStatus
import app.agency.core.lessons.Habits
import app.agency.core.lessons.Lesson
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonDay
import app.agency.core.lessons.LessonResult
import app.agency.core.lessons.LessonState
import app.agency.core.lessons.Quiz
import app.agency.core.lessons.QuizResult
import app.agency.core.lessons.Quizzer
import app.agency.core.lessons.Refusal
import app.agency.core.llm.LlmClient
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.remind.Decision
import app.agency.core.remind.ReminderLog
import app.agency.core.remind.Reminders
import app.agency.core.rules.ImportResult
import app.agency.core.rules.RuleGroup
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.rules.RulesEdit
import app.agency.core.rules.RulesImport
import app.agency.core.rules.RulesText
import app.agency.core.stage.Feature
import app.agency.core.stage.Rollover
import app.agency.core.stage.RolloverResult
import app.agency.core.stage.Stages
import app.agency.core.stage.Today as TodayPlan
import app.agency.core.store.Body
import app.agency.core.store.DayClose
import app.agency.core.store.Doc
import app.agency.core.store.Feel
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealSlot
import app.agency.core.store.Memo
import app.agency.core.store.Message
import app.agency.core.store.Photocard
import app.agency.core.store.ProposalRec
import app.agency.core.store.Role
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.update
import app.agency.core.store.writeDoc
import app.agency.core.plan.Chips
import app.agency.core.week.Streaks
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The model clients and services [Company] uses. [keyReady] says whether a usable key is saved (for `ready`). */
class Providers(
    val chat: LlmClient,
    val small: LlmClient,
    val search: SearchPort? = null,
    val keyReady: () -> Boolean = { true },
)

/**
 * The company: the one object the app talks to. It owns no rules of its own; each function reads the current [Rules] (doc
 * `rules`, blank when absent), builds what it needs from the packages that own each rule, and writes through them. Taps,
 * card answers and screens leave a NOTE line so 小葵 sees what happened on her next turn, without a model call.
 */
class Company(
    private val store: Store,
    private val clock: Clock,
    private val providers: Providers,
    private val loadPhoto: (String) -> ByteArray? = { null },
) : CompanyApi, CompanyAdmin {

    // ---------------------------------------------------------------- reading

    override fun rules(): Rules = store.readDoc<Rules>(Doc.RULES) ?: Rules()

    private fun moment(): Moment = Moment.of(store, clock, rules())

    private fun ctx(photoIds: List<String> = emptyList(), gallery: List<Boolean> = emptyList()): ToolCtx {
        val day = Days.today(clock)
        return ToolCtx(store, clock, rules(), TodayPlan.stage(store, day), photoIds, Ports(providers.search), gallery)
    }

    private fun note(text: String, cards: List<Card> = emptyList()): Message = store.addMessage(clock.now(), Role.NOTE, text, cards)

    override fun today(): Today {
        val rules = rules()
        val m = Moment.of(store, clock, rules)
        return Today(
            stageLabel = Stages.label(m.stage, m.day),
            stickers = stickers(m),
            plan = m.plan,
            chips = Chips.of(m.plan, m.state, m.now),
            ready = rules.callName.isNotBlank() && providers.keyReady(),
            moodOpen = Feature.MOOD.openAt(m.stage.stage) && RulesCheck.ready(rules, Feature.MOOD),
        )
    }

    private fun stickers(m: Moment): List<Sticker> {
        val stage = m.stage.stage
        val r = m.rules
        if (stage <= 1) {
            val slots = m.plan.items.filter { it.id.startsWith("lesson:") }
            val passed = passedTotal(m.day)
            val streak = Streaks.perfect(store, m.day.minusDays(1)) + if (m.plan.total > 0 && m.plan.done == m.plan.total) 1 else 0
            return listOf(
                Sticker("lessons", "今天的課", "${slots.count { it.status == ItemStatus.DONE }}／${slots.size} 堂"),
                Sticker("passed", "已通過", "$passed 堂"),
                Sticker("streak", "連續", "$streak 天"),
            )
        }
        val out = ArrayList<Sticker>()
        if (Feature.MEALS.openAt(stage) && r.diet.kcal > 0) out += Sticker("eaten", "今天吃了", "${m.state.kcal}／${r.diet.kcal} 大卡")
        if (Feature.WATER.openAt(stage) && r.water.bottleMl > 0) {
            val target = m.state.waterTargetMl(m.state.dayNumber)
            out += Sticker("water", "喝水", "${bottles(m.state.waterMl, r)}／${bottles(target, r)} 壺")
        }
        out += Sticker("done", "今天完成", "${m.plan.done}／${m.plan.total} 項")
        return out
    }

    private fun bottles(ml: Int, r: Rules): String {
        val v = Math.round(ml * 100.0 / r.water.bottleMl) / 100.0
        return if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }

    private fun passedTotal(day: LocalDate): Int =
        store.entries(day.minusDays(400), day, Kind.LESSON).count { e ->
            runCatching { e.read<Lesson>().passedOn != null }.getOrDefault(false)
        }

    override fun messages(limit: Int, beforeId: Long?): List<Message> = store.messages(limit, beforeId)

    override fun item(id: String): PlanItem? = moment().item(id)

    // ---------------------------------------------------------------- conversation

    private fun agent(photoIds: List<String>, gallery: List<Boolean>, seen: MutableList<Context>): Agent {
        val rules = rules()
        return Agent(
            llm = providers.chat,
            small = providers.small,
            store = store,
            clock = clock,
            tools = { Tools.all() },
            toolCtx = { ids -> ctx(ids.ifEmpty { photoIds }, gallery) },
            context = { Context(store, clock, rules(), providers.search != null).also { seen += it } },
            system = { system(rules) },
            loadPhoto = loadPhoto,
        )
    }

    private fun system(rules: Rules): String {
        val training = TodayPlan.levels(store).let { app.agency.core.week.Progression.effective(rules, it) }
        return Persona.system(rules, RulesDoc.handbook(store), RulesText.render(rules, training), Memory.lines(store))
    }

    override suspend fun say(text: String, attachments: List<Attachment>): SayResult {
        rollover()
        val photos = attachments.filterIsInstance<Attachment.Photo>()
        val seen = ArrayList<Context>()
        val r = agent(photos.map { it.photoId }, photos.map { it.fromGallery }, seen).reply(text, attachments)
        afterReply(r, seen)
        return r
    }

    override suspend fun resend(messageId: Long): SayResult {
        val seen = ArrayList<Context>()
        val r = agent(emptyList(), emptyList(), seen).resend(messageId)
        afterReply(r, seen)
        return r
    }

    /** A sweet line is allowed once a day: the first reply that was allowed one uses the day up. */
    private fun afterReply(r: SayResult, seen: List<Context>) {
        if (r is SayResult.Ok && seen.firstOrNull()?.mayBeSweet == true) store.putDoc(Context.SWEET_DOC, Days.today(clock).toString())
    }

    override fun direct(tool: String, args: JsonObject): String {
        rollover()
        val t = Tools.direct(tool) ?: return "沒有這個動作。"
        val out = runBlocking { t.run(args, ctx()) }
        val line = Tools.noteLine(t, out)
        if (out.ok) note(line, out.cards)
        return line
    }

    override suspend fun answerCard(messageId: Long, cardIndex: Int, answer: String): SayResult {
        rollover()
        val msg = store.messages(1, beforeId = messageId + 1).firstOrNull { it.id == messageId }
            ?: return SayResult.NotSent("找不到這則訊息。")
        val card = msg.cards.getOrNull(cardIndex) ?: return SayResult.NotSent("找不到這張卡片。")
        val a = answer.trim()
        val (updated, line) = when (card) {
            is Card.Score -> {
                if (card.picked != null) return SayResult.NotSent("已經選過了。")
                val score = a.toIntOrNull()?.takeIf { it in 1..5 } ?: return SayResult.NotSent("請選 1 到 5。")
                val out = when (card.ask) {
                    "mood" -> LogMoodTool().run(buildJsonObject { put("score", score); put("note", "") }, ctx())
                    else -> {
                        if (moment().state.checkIn != null) return SayResult.NotSent("今天已經簽到過了。")
                        CheckInTool().record(ctx(), score)
                    }
                }
                if (!out.ok) return SayResult.NotSent(app.agency.core.agent.tools.F.forHim(out.facts["error"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()))
                card.copy(picked = score) to (if (card.ask == "mood") "✓ 心情 $score 分" else "✓ 已簽到，狀態 $score 分")
            }
            is Card.Proposal -> {
                if (card.answer != Card.Answer.OPEN) return SayResult.NotSent("這個提案已經回覆過了。")
                val yes = a.lowercase() in YES
                if (!yes && a.lowercase() !in NO) return SayResult.NotSent("請選好或不要。")
                val res = answerProposal(card.entryId, yes) ?: return SayResult.NotSent("這個提案現在不能接受了。")
                card.copy(answer = if (yes) Card.Answer.YES else Card.Answer.NO) to res
            }
            is Card.Videos -> {
                val (verb, idText) = a.split(':', '：').let { it[0].trim().lowercase() to it.getOrNull(1)?.trim() }
                val id = idText?.toLongOrNull() ?: return SayResult.NotSent("要說是哪一支影片。")
                val v = card.items.firstOrNull { it.lessonId == id } ?: return SayResult.NotSent("這張卡片上沒有這支影片。")
                val book = LessonBook(store, clock, rules())
                when (verb) {
                    "approve", "yes", "加入" -> {
                        if (book.approve(id) is LessonResult.Refused) return SayResult.NotSent("這支影片現在不能加入。")
                        card.copy(approved = card.approved + id) to "✓ 加入清單：${v.title}"
                    }
                    "decline", "no", "不要" -> {
                        if (book.decline(id) is LessonResult.Refused) return SayResult.NotSent("這支影片已經決定過了。")
                        card.copy(declined = card.declined + id) to "✓ 不要這支：${v.title}"
                    }
                    else -> return SayResult.NotSent("請選加入或不要。")
                }
            }
            else -> return SayResult.NotSent("這張卡片不用回答。")
        }
        store.updateMessage(msg.copy(cards = msg.cards.toMutableList().also { it[cardIndex] = updated }))
        note(line)
        return SayResult.Ok
    }

    /** Applies a yes / no to a stored proposal; returns the NOTE line, or null when it cannot be applied. */
    private fun answerProposal(entryId: Long, yes: Boolean): String? = store.tx {
        val e = store.entry(entryId)?.takeIf { it.kind == Kind.PROPOSAL } ?: return@tx null
        val p = runCatching { e.read<ProposalRec>() }.getOrNull() ?: return@tx null
        if (p.answer != Card.Answer.OPEN) return@tx null
        if (!yes) {
            store.update(entryId, p.copy(answer = Card.Answer.NO))
            return@tx "✗ 不要：${p.title}"
        }
        val line = when (p.kind) {
            "habit" -> {
                // A plan change from tomorrow on: today's plan stays as he knew it this morning.
                Habits(store, clock).add(p.title, Days.today(clock).plusDays(1)) ?: return@tx null
                "✓ 新習慣：${p.title}（明天起每天一次）"
            }
            "note" -> {
                Memory.remember(store, "約定：${p.title}" + if (p.body.isNotBlank()) "（${p.body}）" else "")
                "✓ 說好了：${p.title}"
            }
            app.agency.core.week.BodyTrend.KCAL_UP -> {
                val r = rules()
                val step = p.value ?: app.agency.core.week.BodyTrend.KCAL_STEP
                store.writeDoc(Doc.RULES, r.copy(diet = r.diet.copy(kcal = r.diet.kcal + step)))
                "✓ 每日熱量改成 ${r.diet.kcal + step} 大卡"
            }
            else -> return@tx null
        }
        store.update(entryId, p.copy(answer = Card.Answer.YES))
        line
    }

    // ---------------------------------------------------------------- housekeeping

    override fun rollover() {
        rolloverResult()
    }

    /** [rollover] with what it did (for tests and the app's alarm receiver). */
    fun rolloverResult(): RolloverResult {
        val rules = rules()
        if (Stages.get(store) == null) {
            if (rules.callName.isBlank()) return RolloverResult()
            Stages.join(store, Days.today(clock))
        }
        return Rollover.run(store, clock, rules) { d ->
            val end = Days.at(d.plusDays(1), LocalTime.of(Days.START_HOUR, 0), clock.zone).minusSeconds(1)
            val stage = TodayPlan.stage(store, d)
            val state = TodayPlan.state(store, d, rules, stage, clock.zone)
            Moment.extras(store, rules, stage, state, d, end)
        }
    }

    override fun reminders(log: ReminderLog): Decision {
        val m = moment()
        val checkInLive = Feature.CHECK_IN.openAt(m.stage.stage) && RulesCheck.ready(m.rules, Feature.CHECK_IN)
        val memos = Memos.forReminders(store, m.day)
        val extra = runCatching { Extras.notices(m.now, store, m.rules, m.stage, m.state) }.getOrDefault(emptyList())
        // The spot check announces itself through [extra]; its plan item must not ring a second time.
        val plan = (if (extra.isEmpty()) m.plan else moment().plan).let { p -> p.copy(items = p.items.filter { it.id != "spot" }) }
        val d = Reminders.decide(
            now = m.now,
            zone = clock.zone,
            plan = plan,
            checkedIn = !checkInLive || m.state.checkIn != null,
            checkedOut = checkInLive && m.state.checkOut != null,
            bedtime = m.bedtime,
            memos = memos,
            lessonAway = LessonBook(store, clock, m.rules).away(),
            log = log,
            extra = extra,
            anchors = anchors(m, checkInLive),
        )
        // A memo is announced once ever, not once a day.
        for (n in d.notices) {
            val id = n.key.removePrefix("memo:").takeIf { n.key.startsWith("memo:") }?.toLongOrNull() ?: continue
            val e = store.entry(id) ?: continue
            runCatching { e.read<Memo>() }.getOrNull()?.let { store.update(id, it.copy(notified = true)) }
        }
        return d
    }

    /** Where today's untimed items start being reminded (see [app.agency.core.remind.Anchors]). */
    private fun anchors(m: Moment, checkInLive: Boolean): app.agency.core.remind.Anchors {
        val zone = clock.zone
        val times = app.agency.core.day.DayTypes.times(m.rules, m.plan.type)
        fun at(s: String) = Days.parse(s)?.let { Days.at(m.day, it, zone) }
        val sign = if (checkInLive) m.state.checkInAt else at(times.wake) ?: Days.at(m.day, LocalTime.of(9, 0), zone)
        return app.agency.core.remind.Anchors(
            checkInAt = sign,
            rideBackAt = m.state.rideBackAt?.takeIf { !m.state.riding },
            rideDay = m.plan.items.any { it.id == "ride:out" && it.status != ItemStatus.OFF },
            classEndAt = if (m.plan.type == app.agency.core.plan.DayType.CLASS) at(times.classEnd) else null,
            quietFrom = if (m.plan.restDay) at(times.dinner) else null,
        )
    }

    // ---------------------------------------------------------------- onboarding and settings

    override suspend fun importRules(text: String): ImportResult = RulesImport(providers.chat).extract(text)

    override fun saveRules(rules: Rules, handbook: String, rulesText: String) {
        store.tx {
            store.writeDoc(Doc.RULES, rules)
            store.putDoc(Doc.HANDBOOK, J.encodeToString(handbook.take(RulesImport.HANDBOOK_MAX)))
            store.putDoc(Doc.RULES_TEXT, J.encodeToString(rulesText))
        }
    }

    override fun ruleGroups(): List<RuleGroup> = RulesEdit.groups(rules())

    // ---------------------------------------------------------------- lessons

    private fun book() = LessonBook(store, clock, rules())

    private fun row(id: Long, l: Lesson) = LessonRow(
        id, l.subject, l.title, l.url, l.state, LessonWords.state(l), l.minutes, l.score, l.total, l.notesOnly, l.reviewOn,
    )

    override fun lessons(): LessonsView {
        val m = moment()
        val all = store.entries(m.day.minusDays(400), m.day, Kind.LESSON).mapNotNull { e ->
            runCatching { e.read<Lesson>() }.getOrNull()?.let { e.id to it }
        }
        val due = book().reviewDue(m.day).map { (id, l) -> row(id, l) }
        val slots = LessonDay.of(m.day, store, m.rules, m.state.examWeek, TodayPlan.levels(store))
        return LessonsView(
            today = slots,
            byState = all.filter { it.second.state != LessonState.WISH }.groupBy({ it.second.state }, { row(it.first, it.second) }),
            reviews = due,
            passedTotal = passedTotal(m.day),
        )
    }

    override fun approveLesson(id: Long): LessonResult {
        val r = book().approve(id)
        if (r is LessonResult.Ok) note("✓ 加入清單：${r.lesson.title}")
        return r
    }

    override fun declineLesson(id: Long): LessonResult {
        val r = book().decline(id)
        if (r is LessonResult.Ok) note("✓ 不要這支：${r.lesson.title}")
        return r
    }

    override fun openLesson(id: Long): String? {
        val b = book()
        val l = b.get(id) ?: return null
        return if (b.opened(id) is LessonResult.Ok) l.url else null
    }

    override fun backFromLesson(id: Long, watched: Boolean): LessonResult {
        val b = book()
        val back = b.returned(id)
        if (!watched || back is LessonResult.Refused) return back
        val l = b.get(id) ?: return LessonResult.Refused(Refusal.NOT_FOUND)
        if (l.state != LessonState.APPROVED) return LessonResult.Ok(id, l)
        val r = b.watched(id)
        if (r is LessonResult.Ok) note("✓ 看完了：${l.subject}「${l.title}」")
        return r
    }

    override suspend fun quizFor(id: Long): Quiz? {
        val b = book()
        val l = b.get(id) ?: return null
        if (l.state != LessonState.WATCHED || l.notesOnly) return null
        l.quiz?.let { return it }
        val quiz = runCatching { Quizzer(providers.chat).make(l, l.level, rules().study) }.getOrNull()
        if (quiz != null && b.setQuiz(id, quiz) is LessonResult.Ok) return quiz
        b.notesOnly(id)
        note("〔${l.subject}「${l.title}」這次出不了合格的題目，改成直接寫三行筆記〕")
        return null
    }

    override fun answerQuiz(id: Long, answers: List<Int>): QuizResult {
        val b = book()
        val l = b.get(id)
        val r = b.answer(id, answers)
        if (r.refusal == null && l != null) {
            note(if (r.passed) "✓ 測驗通過：${l.subject}「${l.title}」${r.total} 題對 ${r.right} 題，接著寫筆記"
                else "✗ 測驗沒過：${l.subject}「${l.title}」${r.total} 題對 ${r.right} 題，換一份題目再試")
        }
        return r
    }

    override fun writeNote(id: Long, text: String): LessonResult {
        val r = book().note(id, text)
        if (r is LessonResult.Ok) note("✓ 筆記完成：${r.lesson.subject}「${r.lesson.title}」")
        return r
    }

    override suspend fun reviewQuizFor(id: Long): Quiz? {
        val b = book()
        val l = b.get(id) ?: return null
        if (l.state != LessonState.NOTED && l.state != LessonState.REVIEW_DUE) return null
        l.reviewQuiz?.let { return it }
        val fresh = runCatching { Quizzer(providers.chat).make(l, l.level, rules().study) }.getOrNull()
        if (fresh != null && b.setReviewQuiz(id, fresh) is LessonResult.Ok) return fresh
        return l.quiz
    }

    override fun answerReview(id: Long, answers: List<Int>): QuizResult {
        val b = book()
        val l = b.get(id)
        val r = b.answerReview(id, answers)
        if (r.refusal == null && l != null) {
            note(if (r.passed) "✓ 複習通過：${l.subject}「${l.title}」" else "✗ 複習沒過：${l.subject}「${l.title}」，再看一次再試")
        }
        return r
    }

    // ---------------------------------------------------------------- screens

    override fun timerDone(task: String, rounds: Int?, minutes: Int?, feel: Feel?, awaySeconds: Int): String {
        rollover()
        val name = Tasks.parse(task) ?: return "沒有這一項。"
        val tool = Tools.named("log_task")!!
        // Grooming is due per item: the record names what was done, so each item waits its own interval again.
        val note = if (name == app.agency.core.plan.TaskId.GROOMING.name) item("task:GROOMING")?.detail.orEmpty() else ""
        val out = LogTaskTool.record(ctx(), name, rounds, minutes, feel, note, awaySeconds)
        val line = Tools.noteLine(tool, out)
        if (out.ok) note(line)
        return line
    }

    override fun mealPhoto(slot: MealSlot, photoId: String, fromGallery: Boolean): String? {
        val day = Days.today(clock)
        val e = store.entries(day, Kind.MEAL).lastOrNull { e ->
            runCatching { e.read<Meal>() }.getOrNull()?.let { it.slot == slot && it.photoId == null } == true
        } ?: return null
        val meal = e.read<Meal>()
        AttachPhotoTool.attach(ctx(), e.id, meal, photoId, fromGallery)
        val line = "✓ 補上照片：${app.agency.core.agent.tools.Meals.slotWord(slot)}" + if (fromGallery) "（補交）" else ""
        note(line, listOf(Card.Photo(photoId, "meal")))
        return line
    }

    override fun bodyPhoto(photoId: String): String {
        BodyPhotos(store, clock).record(photoId)
        val line = "✓ 已存全身對比照"
        note(line, listOf(Card.Photo(photoId, "body")))
        return line
    }

    override fun spotAnswer(photoId: String): String? {
        val rec = SpotChecks(store, clock, rules()).answer(photoId) ?: return null
        val line = if (rec.status == SpotStatus.DONE) "✓ 突擊檢查：${rec.ask.zh}照片準時交了" else "✗ 突擊檢查：${rec.ask.zh}照片超過時間了"
        note(line, listOf(Card.Photo(photoId, "spot")))
        return line
    }

    override fun memos(): List<MemoRow> = Memos.open(store, Days.today(clock))

    override fun memoDone(id: Long): Boolean = Memos.setDone(store, id).also { if (it) note("✓ 備忘完成 #$id") }

    override fun memoAdd(text: String, dueAt: String?): MemoRow? {
        rollover()
        val t = text.trim()
        if (t.isEmpty() || !Feature.MEMO.openAt(moment().stage.stage)) return null
        val due = dueAt?.takeIf { it.isNotBlank() }?.let { Memos.parseDue(it, clock) }
        val e = store.add(Days.today(clock), clock.now(), Kind.MEMO, Memo(t.take(300), due?.toString()))
        note("✓ 記了備忘 #${e.id}：${t.take(40)}")
        return MemoRow(e.id, t.take(300), due, false)
    }

    override fun memoRemove(id: Long): Boolean = Memos.remove(store, id)

    override fun memoryLines(): List<String> = Memory.lines(store)

    override fun forget(line: String): List<String> = Memory.forget(store, line)

    override fun traineeCard(): CardView {
        val m = moment()
        val st = m.stage
        val cards = store.entries(m.day.minusDays(2000), m.day, Kind.CARD).mapNotNull { runCatching { it.read<Photocard>() }.getOrNull() }
        val perfect = store.entries(m.day.minusDays(2000), m.day, Kind.DAY_CLOSE).mapNotNull { e ->
            runCatching { e.read<DayClose>() }.getOrNull()?.takeIf { it.perfect }?.let { e.day }
        }
        return CardView(
            stageName = m.rules.stageName, callName = m.rules.callName, memberColor = m.rules.memberColor, level = st.level,
            stage = st.stage, stageLabel = Stages.label(st, m.day), dayNumber = m.state.dayNumber, photocards = cards,
            perfectDays = perfect,
        )
    }

    override fun moneyView(): MoneyView {
        val r = rules()
        return MoneyMath.of(store, Days.today(clock), r.money.livingPct, r.money.funPct)
    }

    override fun songs(): List<SongRow> {
        val day = Days.today(clock)
        return store.entries(day.minusDays(2000), day, Kind.SONG).mapNotNull { e ->
            runCatching { e.read<app.agency.core.extras.SongRec>() }.getOrNull()?.let { SongRow(e.id, e.day, it) }
        }
    }

    override fun recordSong(pcm: ShortArray, rate: Int, title: String, referenceId: String?, vaultId: String?): SongResult {
        val r = SongBook(store, clock).record(pcm, rate, title, referenceId, vaultId)
        note("✓ 錄好了：${r.rec.title.ifBlank { "一首歌" }}（#${r.entryId}）", listOf(r.card))
        return r
    }

    private fun evals() = Evaluations(store, clock, rules(), EvalSuggester(providers.small))

    override fun evalView(): EvalView {
        val day = Days.today(clock)
        val st = TodayPlan.stage(store, day)
        val e = evals()
        val month = YearMonth.from(day)
        return EvalView(
            weeklyOpen = Feature.WEEKLY_EVAL.openAt(st.stage), weeklyDue = e.weeklyDue(day, st), weekly = e.weekly(day),
            monthlyOpen = Feature.MONTHLY_EVAL.openAt(st.stage), monthlyDue = e.monthlyDue(day, st), monthly = e.monthly(month),
            rotatingItem = e.rotatingItem(month), personalTask = e.peekPersonalTask(month),
        )
    }

    override suspend fun submitWeeklyEval(songEntryId: Long, danceRecordingId: String, selfReport: String): EvalResult {
        val st = TodayPlan.stage(store, Days.today(clock))
        val r = evals().submitWeekly(songEntryId, danceRecordingId, selfReport, st)
        if (r is EvalResult.Ok) note("✓ 每週評鑑交了", listOf(r.card))
        return r
    }

    override suspend fun submitMonthlyEval(input: MonthlyInput): EvalResult {
        val st = TodayPlan.stage(store, Days.today(clock))
        val r = evals().submitMonthly(input, st)
        if (r is EvalResult.Ok) {
            if (input.weightKg > 0 || input.waistCm > 0) {
                store.add(Days.today(clock), clock.now(), Kind.BODY, Body(input.weightKg.takeIf { it > 0 }, input.waistCm.takeIf { it > 0 }))
            }
            note("✓ 月評交了", listOf(r.card))
        }
        return r
    }

    companion object {
        private val YES = setOf("yes", "y", "好", "好啊", "同意", "ok", "是")
        private val NO = setOf("no", "n", "不要", "不", "不同意", "否")
    }
}
