package app.agency.core.agent

import app.agency.core.agent.tools.IncomeFacts
import app.agency.core.agent.tools.Memos
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.ViolationCode
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonDay
import app.agency.core.plan.DayType
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.TaskId
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.rules.RulesText
import app.agency.core.stage.Feature
import app.agency.core.stage.Stages
import app.agency.core.store.Body
import app.agency.core.store.Bucket
import app.agency.core.store.CheckIn
import app.agency.core.store.CheckOut
import app.agency.core.store.DayClose
import app.agency.core.store.Drink
import app.agency.core.store.DrinkType
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.Money
import app.agency.core.store.Mood
import app.agency.core.store.Note
import app.agency.core.store.ProposalRec
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Role
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.Store
import app.agency.core.store.TaskDone
import app.agency.core.store.Violation
import app.agency.core.store.Water
import app.agency.core.week.Income
import app.agency.core.week.Streaks
import java.time.LocalDate
import java.time.LocalTime

/**
 * The context block of one turn (SPEC D "Context block"): plain compact Traditional Chinese, stable order, every number
 * computed by code and written the way the guard reads numbers (digits right before their unit, times as H:MM), so anything
 * she quotes from here passes. Entry ids appear as `#12` so she can fix or remove an entry.
 */
class Context(
    private val store: Store,
    private val clock: Clock,
    private val rules: Rules,
    /** Whether a web search is wired; without it she must know the two ways on (a key in settings, or a pasted link). */
    private val searchAvailable: Boolean = true,
) : ContextSource {
    private val m: Moment = Moment.of(store, clock, rules)
    private val text: String by lazy { build() }

    override fun block(): String = text

    override fun facts(): String = text + "\n" + RulesText.render(rules, m.training)

    override val unwellReducedByCode: Boolean get() = m.state.unwell || m.state.firstDayBack

    override val closedFeatureWords: List<String> get() = closedWords(m.stage.stage)

    override val neverBelowKg: Double get() = rules.body.neverBelowKg

    override val mayPlayScene: Boolean
        get() = store.messages(SCAN).none { it.role == Role.MANAGER && Days.of(it.at, clock.zone) == m.day }

    override val mayBeSweet: Boolean
        get() = sweetEarned(m) && store.doc(SWEET_DOC) != m.day.toString()

    private fun build(): String = buildString {
        val st = m.state
        val plan = m.plan
        val stage = m.stage.stage
        val zone = clock.zone
        val r = rules
        fun line(s: String) { if (s.isNotBlank()) append(s.trim()).append('\n') }

        val dayType = if (plan.type == DayType.CLASS) "上課日" else "假日"
        line("現在：${m.day}（${RulesText.weekday(m.day.dayOfWeek.value)}）${hm(m.now.atZone(zone).toLocalTime())}，$dayType")
        line("階段：${Stages.label(m.stage, m.day)}（加入第 ${st.dayNumber} 天）" + roundLine())
        val open = Stages.open(m.stage).toList()
        line("開放的功能：" + open.joinToString("、") { it.label })
        val fresh = if (stage >= 2 && app.agency.core.day.StageDays.openedOn(m.stage, stage) == m.day) Stages.opensAt(stage) else emptyList()
        if (fresh.isNotEmpty()) line("剛開放（今天起）：" + fresh.joinToString("、") { it.label })
        val closed = Feature.values().filter { !it.openAt(stage) }
        if (closed.isNotEmpty()) {
            line("還沒開放：" + closed.joinToString("、") { it.label } + "。不要叫他做、不要提醒、不要排進計畫；他自己回報了就記下，並說之後才開始。")
            val next = Stages.opensNext(m.stage)
            if (next.isNotEmpty()) line("下一階段會開放：" + next.joinToString("、") { it.label })
        }
        val missing = open.mapNotNull { f -> RulesCheck.missing(r, f).takeIf { it.isNotEmpty() }?.let { "${f.label}（${it.joinToString("、")}）" } }
        if (missing.isNotEmpty()) line("規章缺值（請他到 設定 → 公司規章 填，不要自己假設）：" + missing.joinToString("；"))

        line("今天的計畫（完成 ${plan.done}／${plan.total} 項，${pct(plan.done, plan.total)}%）：")
        plan.items.forEach { line("・" + itemLine(it)) }
        if (plan.items.isEmpty()) line("・（今天沒有排任何項目）")
        line("下一件事：" + (plan.next?.let { itemLine(it) } ?: "今天的事都做完了"))

        val today = store.entries(m.day)
        val records = today.mapNotNull { recordLine(it) }
        if (records.isNotEmpty()) {
            line("今天的紀錄：")
            records.forEach { line("・$it") }
        }
        if (Feature.MEALS.openAt(stage) && r.diet.kcal > 0) {
            val budgets = if (RulesCheck.ready(r, Feature.MEALS)) {
                "；下一餐預算：第一餐 ${st.budget(app.agency.core.store.MealSlot.FIRST)} 大卡、晚餐 ${st.budget(app.agency.core.store.MealSlot.DINNER)} 大卡、" +
                    "宵夜 ${st.budget(app.agency.core.store.MealSlot.NIGHT)} 大卡"
            } else ""
            val left = st.leftKcal
            line(
                "吃：今天 ${st.kcal} 大卡／目標 ${r.diet.kcal} 大卡，" + (if (left >= 0) "還剩 $left 大卡" else "超過 ${-left} 大卡") +
                    "；蛋白質 ${num(st.proteinG)} 克" + (if (r.diet.proteinG > 0) "／目標 ${r.diet.proteinG} 克，還差 ${num((r.diet.proteinG - st.proteinG).coerceAtLeast(0.0))} 克" else "") +
                    budgets,
            )
        }
        if (Feature.MEALS.openAt(stage)) {
            app.agency.core.day.FreeMeals.line(app.agency.core.day.FreeMeals.status(store, r, m.day))?.let { line(it) }
        }
        if (Feature.WATER.openAt(stage) && r.water.bottleMl > 0) {
            val target = st.waterTargetMl(st.dayNumber)
            line(
                "喝水：${st.waterMl} 毫升（${bottles(st.waterMl)} 壺）／目標 $target 毫升（${bottles(target)} 壺），" +
                    "還差 ${(target - st.waterMl).coerceAtLeast(0)} 毫升",
            )
        }
        if (Feature.RIDE.openAt(stage)) {
            val ride = when {
                st.riding -> "跑單中（${hm(st.rideOutAt!!.atZone(zone).toLocalTime())} 出門）"
                st.rideBackAt != null -> "已收工（${hm(st.rideBackAt.atZone(zone).toLocalTime())}）" + if (st.income > 0) "，今天收入 ${st.income} 元" else ""
                plan.restDay -> "今天休息日，不跑單"
                else -> "還沒出門"
            }
            line("跑單：$ride；" + IncomeFacts.line(Income.week(store, m.day, r)))
        }
        if (Feature.STRENGTH.openAt(stage) && m.training.strengthRounds > 0) {
            val t = m.training
            line("今天的訓練量（程式算的）：肌力 ${t.strengthRounds} 輪，每輪伏地挺身 ${t.pushUps} 下、深蹲 ${t.squats} 下、棒式 ${t.plankSeconds} 秒" +
                if (Feature.CORE.openAt(stage)) "；核心 ${t.coreMinutes} 分鐘" else "")
        }
        m.bedtime?.let { if (Feature.CHECK_IN.openAt(stage)) line("今天的睡覺（簽退）時間：${hm(it)}") }
        if (plan.adjustments.isNotEmpty()) line("今天改了什麼（程式自動調整）：" + plan.adjustments.joinToString("；") { "${it.what}（${it.why}）" })
        val flags = buildList {
            if (st.injured) add("受傷中：程式已暫停所有訓練")
            else if (st.sick) add("生病中（第 ${st.sickDays} 天）：程式已依規則減量（看「今天改了什麼」）")
            else if (st.firstDayBack) add("剛康復：程式已依規則減量（看「今天改了什麼」）")
            if (st.restDay) add("今天是休息日（只是不跑單，訓練照常）")
            if (st.examWeek) add("考試週：理論課減量")
            if (!st.unwell && !st.firstDayBack) add("訓練沒有減量：他累、痠、想睡都照原計畫")
            if (r.body.neverBelowKg > 0) add("體重絕不鼓勵低於 ${num(r.body.neverBelowKg)} 公斤")
        }
        if (flags.isNotEmpty()) line("狀態：" + flags.joinToString("；"))

        proposals().takeIf { it.isNotEmpty() }?.let { line("等他回覆的提案：" + it.joinToString("；")) }
        Memos.dueOn(store, m.day, zone).takeIf { it.isNotEmpty() }?.let { list ->
            line("今天到期的備忘：" + list.joinToString("；") { "#${it.id} ${it.text}（${hm(it.dueAt!!.atZone(zone).toLocalTime())}）" })
        }
        val openMemos = Memos.open(store, m.day).size
        if (openMemos > 0) line("還沒完成的備忘共 $openMemos 則（memo_list 可以看）")
        if (Feature.LESSONS.openAt(stage)) lessonLines().forEach { line(it) }
        if (!searchAvailable) {
            line("搜尋現在不能用（還沒有搜尋用的金鑰）：找影片、找地方都不行。請他到 設定 → 小葵的模型 加上搜尋用的金鑰；" +
                "在那之前，他可以直接把影片連結貼給你，你用 add_video 加進清單。")
        }

        val week = lastDays()
        if (week.isNotEmpty()) {
            line("最近七天：")
            week.forEach { line("・$it") }
        }
        talk().takeIf { it.isNotEmpty() }?.let { line("該主動談的事：" + it.joinToString("；")) }
        Memory.notes(store, clock).takeIf { it.isNotEmpty() }?.let { notes ->
            line("你自己的筆記（最近三天）：" + notes.joinToString("；") { e -> "${e.day.monthValue}/${e.day.dayOfMonth} " + (runCatching { e.read<Note>().text }.getOrNull() ?: "") })
        }
        line("規章數字（可以照著說）：" + RulesText.render(r, m.training).replace("\n", " "))
    }

    private fun roundLine(): String {
        val from = runCatching { LocalDate.parse(m.stage.startedOn) }.getOrNull() ?: return ""
        val closes = store.entries(from, m.day.minusDays(1), Kind.DAY_CLOSE).mapNotNull { runCatching { it.read<DayClose>() }.getOrNull() }
        val done = closes.sumOf { it.done } + m.plan.done
        val total = closes.sumOf { it.total } + m.plan.total
        val v = closes.sumOf { it.violations } + m.state.violations.size
        return "；這一輪到現在完成 $done／$total 項（${pct(done, total)}%，過關要 ${rules.level.passPct}%），違規 $v 次"
    }

    private fun itemLine(i: PlanItem): String = buildString {
        append(i.title)
        i.at?.let { append(" ").append(hm(it)) }
        i.until?.let { append(" ").append(hm(it)).append(" 前") }
        append("〔").append(app.agency.core.agent.tools.F.statusWord(i.status)).append("〕")
        if (i.detail.isNotBlank()) append(" ").append(i.detail)
        if (!i.counts) append("（不計分）")
        append(" ‹").append(i.id).append('›')
    }

    private fun recordLine(e: Entry): String? {
        val z = clock.zone
        val t = hm(e.at.atZone(z).toLocalTime())
        fun <T> r(f: () -> T): T? = runCatching { f() }.getOrNull()
        val what = when (e.kind) {
            Kind.CHECK_IN -> r { "簽到，狀態 ${e.read<CheckIn>().score} 分" }
            Kind.CHECK_OUT -> r { e.read<CheckOut>().let { "簽退" + (it.mood?.let { m -> "，心情 $m 分" } ?: "") } }
            Kind.MEAL -> r {
                val meal = e.read<Meal>()
                val kcal = meal.items.sumOf { it.kcal }
                val p = meal.items.sumOf { it.proteinG }
                app.agency.core.agent.tools.Meals.slotWord(meal.slot) + "：" + meal.items.joinToString("、") { "${it.name} ${it.kcal} 大卡" + (it.banned?.let { b -> "（禁止：$b）" } ?: "") } +
                    "，共 $kcal 大卡、蛋白質 ${num(p)} 克，" + when {
                        meal.photoId == null -> "缺照片"
                        meal.photoLate -> "照片補交"
                        else -> "有照片"
                    }
            }
            Kind.WATER -> r { "喝水 ${e.read<Water>().ml} 毫升" }
            Kind.RIDE -> r { e.read<Ride>().let { if (it.phase == RidePhase.OUT) "出門跑單" else "收工" + (it.income?.let { i -> "，收入 $i 元" } ?: "") } }
            Kind.TASK -> r {
                val d = e.read<TaskDone>()
                val title = TaskId.values().firstOrNull { it.name == d.task }?.title ?: "主題週小練習"
                title + (d.rounds?.let { " $it 輪" } ?: "") + (d.minutes?.let { " $it 分鐘" } ?: "") + (d.feel?.let { " 感覺${feel(it)}" } ?: "") +
                    (if (d.reduced) "（程式減量）" else "") + (if (d.note.isNotBlank()) "：${d.note}" else "")
            }
            Kind.STATUS -> r { "狀態：" + when (e.read<Status>().type) {
                StatusType.REST_DAY -> "休息日"; StatusType.SICK -> "生病"; StatusType.INJURED -> "受傷"
                StatusType.RECOVERED -> "康復"; StatusType.EXAM_WEEK -> "考試週"
            } }
            Kind.BODY -> r { e.read<Body>().let { b -> listOfNotNull(b.kg?.let { "體重 ${num(it)} 公斤" }, b.waistCm?.let { "腰圍 ${num(it)} 公分" }, b.photoId?.let { "身體照片" }).joinToString("、") } }
            Kind.MOOD -> r { e.read<Mood>().let { "心情 ${it.score} 分" + if (it.note.isNotBlank()) "：${it.note}" else "" } }
            Kind.MONEY -> r { e.read<Money>().let { "記帳 ${bucket(it.bucket)} ${it.amount} 元" + if (it.note.isNotBlank()) "：${it.note}" else "" } }
            Kind.DRINK -> r { if (e.read<Drink>().type == DrinkType.CAFFEINE) "咖啡因飲料" else "無熱量飲料" }
            Kind.VIOLATION -> r { e.read<Violation>().let { v -> "違規：" + (ViolationCode.values().firstOrNull { it.name == v.code }?.label ?: v.code) + if (v.note.isNotBlank()) "（${v.note}）" else "" } }
            else -> null
        } ?: return null
        return "#${e.id} $t $what"
    }

    private fun proposals(): List<String> =
        store.entries(m.day.minusDays(30), m.day, Kind.PROPOSAL).mapNotNull { e ->
            runCatching { e.read<ProposalRec>() }.getOrNull()?.takeIf { it.answer == Card.Answer.OPEN }?.let { "#${e.id} ${it.title}" }
        }

    private fun lessonLines(): List<String> {
        val slots = LessonDay.of(m.day, store, rules, m.state.examWeek, app.agency.core.stage.Today.levels(store))
        if (slots.isEmpty()) return emptyList()
        val book = LessonBook(store, clock, rules)
        val out = ArrayList<String>()
        out += "今天的課（完成 ${slots.count { it.done }}／${slots.size} 堂）：" + slots.joinToString("；") { s ->
            val state = s.lessonId?.let { id -> book.get(id)?.let { app.agency.core.agent.tools.LessonWords.state(it) } }
            "${s.n}. ${s.title}" + (s.lessonId?.let { "（課 #$it，${state ?: ""}）" } ?: "") + if (s.done) "〔完成〕" else ""
        }
        return out
    }

    private fun lastDays(): List<String> {
        val from = m.day.minusDays(7)
        val closes = store.entries(from, m.day.minusDays(1), Kind.DAY_CLOSE).mapNotNull { e ->
            runCatching { e.read<DayClose>() }.getOrNull()?.let { e.day to it }
        }
        val meals = store.entries(from, m.day.minusDays(1), Kind.MEAL).groupBy { it.day }
        return closes.map { (d, c) ->
            val kcal = meals[d].orEmpty().sumOf { e -> runCatching { e.read<Meal>().items.sumOf { it.kcal } }.getOrDefault(0) }
            "${d.monthValue}/${d.dayOfMonth}（${RulesText.weekday(d.dayOfWeek.value)}）完成 ${c.done}／${c.total} 項" +
                (if (Feature.MEALS.openAt(m.stage.stage)) "，$kcal 大卡" else "") + "，違規 ${c.violations} 次" + if (c.perfect) "，全部完成" else ""
        }
    }

    private fun talk(): List<String> {
        val out = ArrayList<String>()
        val y = m.day.minusDays(1)
        Streaks.missed(store, y).forEach { out += "「${it.title}」已經連續 ${it.days} 天沒做" }
        if (Streaks.lowMood(store, m.day) >= Streaks.TALK_AT) out += "他連續 ${Streaks.lowMood(store, m.day)} 天心情低落，主動問他怎麼了"
        if (m.stage.talk) out += "上一輪違規很多，要跟他談一談"
        val from = runCatching { LocalDate.parse(m.stage.startedOn) }.getOrNull()
        if (from != null) {
            val v = store.entries(from, m.day, Kind.VIOLATION).size
            if (v >= 3) out += "這一輪已經有 $v 次違規"
        }
        return out
    }

    private fun bottles(ml: Int): String {
        val b = rules.water.bottleMl
        if (b <= 0) return "0"
        return num(Math.round(ml * 100.0 / b) / 100.0)
    }

    companion object {
        private const val SCAN = 200
        /** Doc key: the logical day a sweet line was last allowed (so it happens at most once a day). */
        const val SWEET_DOC = "sweet_day"

        fun hm(t: LocalTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

        private fun pct(done: Int, total: Int) = if (total <= 0) 100 else done * 100 / total

        private fun num(v: Double): String {
            val r = Math.round(v * 100) / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }

        private fun feel(f: app.agency.core.store.Feel) = when (f) {
            app.agency.core.store.Feel.EASY -> "輕鬆"
            app.agency.core.store.Feel.OK -> "剛好"
            app.agency.core.store.Feel.HARD -> "吃力"
        }

        private fun bucket(b: Bucket) = app.agency.core.agent.tools.MoneyMath.word(b)

        /** A counted big item done today (the whole training block, or every meal), and no violation today. */
        fun sweetEarned(m: Moment): Boolean {
            if (m.state.violations.isNotEmpty()) return false
            val live = m.plan.items.filter { it.counts && it.status != ItemStatus.OFF }
            val training = live.filter { it.id in TRAINING }
            val meals = live.filter { it.id.startsWith("meal:") }
            return (training.isNotEmpty() && training.all { it.status == ItemStatus.DONE }) ||
                (meals.isNotEmpty() && meals.all { it.status == ItemStatus.DONE })
        }

        private val TRAINING = setOf("task:CORE", "task:STRENGTH", "task:STRETCH", "task:CARDIO")

        /** Natural words for each feature besides its label, so a closed one is caught however she names it. */
        private val SYNONYMS: Map<Feature, List<String>> = mapOf(
            Feature.CHECK_IN to listOf("簽到", "簽退"),
            Feature.MEALS to listOf("三餐", "餐點照片", "拍餐點", "記錄飲食", "拍照記錄"),
            Feature.WATER to listOf("喝水", "水壺"),
            Feature.CORE to listOf("核心訓練", "核心"),
            Feature.STRENGTH to listOf("肌力", "伏地挺身", "深蹲", "棒式"),
            Feature.STRETCH to listOf("收操"),
            Feature.RIDE to listOf("跑單", "外送"),
            Feature.WEIGHT to listOf("量體重", "秤體重"),
            Feature.MORNING_STRETCH to listOf("起床伸展"),
            Feature.VOCAL_BASICS to listOf("聲樂基本功", "發聲練習"),
            Feature.CARDIO to listOf("體能", "有氧"),
            Feature.POSTURE to listOf("靠牆站", "站姿練習"),
            Feature.SUNSCREEN to listOf("防曬"),
            Feature.MOOD to listOf("心情分數"),
            Feature.WALK to listOf("快走"),
            Feature.SINGING to listOf("唱歌分析", "錄音分析"),
            Feature.DANCE to listOf("練舞", "舞蹈練習"),
            Feature.VOCAL to listOf("聲樂練習", "練唱"),
            Feature.EXPRESSION to listOf("表情鏡頭", "表情練習"),
            Feature.WEEKLY_EVAL to listOf("每週評鑑", "週評"),
            Feature.MONTHLY_EVAL to listOf("月評", "月評鑑"),
            Feature.SPOT_CHECK to listOf("突擊檢查"),
            Feature.THEME_WEEK to listOf("主題週"),
            Feature.EARLY_BED to listOf("提早睡", "睡覺時間往前"),
            Feature.REAL_TASK to listOf("真人任務"),
            Feature.GROOMING to listOf("儀容", "修眉", "剪指甲"),
            Feature.BODY_PHOTO to listOf("全身對比照", "全身照"),
        )

        /** Words naming the features closed at [stage], minus any that are part of an open feature's own words. */
        fun closedWords(stage: Int): List<String> {
            val open = Feature.values().filter { it.openAt(stage) }
            val openWords = open.flatMap { listOf(it.label) + SYNONYMS[it].orEmpty() }
            return Feature.values().filter { !it.openAt(stage) }
                .flatMap { listOf(it.label) + SYNONYMS[it].orEmpty() }
                .filter { w -> openWords.none { it.contains(w) || w.contains(it) } }
                .distinct()
        }
    }
}
