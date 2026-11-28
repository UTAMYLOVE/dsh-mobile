package app.agency.core.plan

import app.agency.core.base.Days
import app.agency.core.day.Bedtime
import app.agency.core.day.DayState
import app.agency.core.day.DayTypes
import app.agency.core.day.StageDays
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.rules.TrainingRule
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.MealSlot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Short right-hand details the planner and the chips both recognise. */
object PlanDetail {
    /** A training item waiting for 「收工」 on a ride day. */
    const val AFTER_RIDE = "收工後"
    const val AFTER_OUT = "出門後"
    const val AFTER_CHECK_IN = "簽到後"
    const val NO_PHOTO = "缺照片"
    const val LATE_PHOTO = "補交"
}

/** Waiting for something else first: pending, never late, never chased, never offered as the next thing. */
val PlanItem.waiting: Boolean
    get() = detail == PlanDetail.AFTER_RIDE || detail == PlanDetail.AFTER_OUT || detail == PlanDetail.AFTER_CHECK_IN

/** Hour and minute as he reads them: 「8:31」. */
internal fun hm(t: LocalTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

/**
 * Today's things, in order, with the one next thing and the day's automatic changes. Pure: the same inputs always give the same
 * plan. An item exists only when its feature is open at the current stage and its rule values are set ([RulesCheck]); a closed
 * feature leaves no trace.
 */
object DayPlanner {
    @Suppress("LongParameterList")
    fun plan(
        day: LocalDate,
        now: Instant,
        rules: Rules,
        stageState: StageState,
        state: DayState,
        week: WeekSoFar,
        lessons: List<LessonSlot>,
        habits: List<HabitToday>,
        training: TrainingRule = rules.training,
        extra: List<PlanItem> = emptyList(),
    ): DayPlan = Planner(day, now, rules, stageState, state, week, lessons, habits, training, extra).build()

    private class Keyed(val item: PlanItem, val key: Int)

    @Suppress("LongParameterList", "LargeClass")
    private class Planner(
        val day: LocalDate,
        val now: Instant,
        val rules: Rules,
        val stageState: StageState,
        val state: DayState,
        val week: WeekSoFar,
        val lessons: List<LessonSlot>,
        val habits: List<HabitToday>,
        val training: TrainingRule,
        val extra: List<PlanItem>,
    ) {
        private val zone = state.zone
        private val stage = stageState.stage
        private val type = DayTypes.of(rules, day)
        private val times = DayTypes.times(rules, type)
        private val closed = Days.of(now, zone).isAfter(day)
        private val restDay = state.restDay
        private val items = ArrayList<Keyed>()
        private val adjustments = ArrayList<Adjustment>()

        private fun open(f: Feature) = f.openAt(stage) && RulesCheck.ready(rules, f)
        private fun time(s: String): LocalTime? = Days.parse(s)
        private fun min(t: LocalTime) = Days.minuteOfDay(t)
        private fun inst(t: LocalTime) = Days.at(day, t, zone)
        private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

        private fun status(done: Boolean, off: Boolean, at: LocalTime?, until: LocalTime?, waiting: Boolean = false): ItemStatus {
            if (off) return ItemStatus.OFF
            if (done) return ItemStatus.DONE
            if (closed) return ItemStatus.MISSED
            if (waiting) return ItemStatus.PENDING
            val ref = until ?: at ?: return ItemStatus.PENDING
            return if (now.isAfter(inst(ref))) ItemStatus.LATE else ItemStatus.PENDING
        }

        @Suppress("LongParameterList")
        private fun add(
            key: Int,
            id: String,
            feature: Feature,
            title: String,
            at: LocalTime?,
            until: LocalTime?,
            status: ItemStatus,
            tap: Tap,
            detail: String = "",
            counts: Boolean = true,
        ) {
            items += Keyed(PlanItem(id, feature, title, at, until, status, counts, tap, detail), key)
        }

        fun build(): DayPlan {
            val wake = time(times.wake)
            val wakeM = wake?.let(::min) ?: 0
            val leaveBy = time(times.leaveBy)
            val leaveM = leaveBy?.let(::min) ?: 780
            val bed = Bedtime.of(rules, stageState, day)
            val lessonKey = (bed?.let(::min) ?: 1330) - 31
            val rideDay = open(Feature.RIDE) && !restDay
            val rideBack = state.rideBackAt != null && !state.riding

            // Late sign-in on a free day pushes the first meal later by the delay, at most two hours.
            var firstShift = 0L
            if (type == DayType.FREE && open(Feature.CHECK_IN) && open(Feature.MEALS) && wake != null && state.checkInAt != null) {
                val delay = Duration.between(inst(wake), state.checkInAt).toMinutes()
                if (delay > 0) firstShift = minOf(delay, 120L)
            }

            if (open(Feature.CHECK_IN) && wake != null && bed != null) {
                val inAt = state.checkInAt?.atZone(zone)?.toLocalTime()
                add(
                    wakeM, "check_in", Feature.CHECK_IN, "簽到", wake, null,
                    status(state.checkIn != null, false, wake, null), Tap.Open(Screen.CHECK_IN),
                    if (inAt != null) hm(inAt) else "${hm(wake)} 前",
                )
                val outAt = state.checkOutAt?.atZone(zone)?.toLocalTime()
                val dueM = (min(bed) - 30).coerceAtLeast(0)
                add(
                    min(bed), "check_out", Feature.CHECK_IN, "簽退", Bedtime.fromMinute(dueM), bed,
                    status(state.checkOut != null, false, Bedtime.fromMinute(dueM), bed), Tap.Open(Screen.CHECK_OUT),
                    if (outAt != null) hm(outAt) else "${hm(bed)} 前",
                )
            }

            val waitCheckIn = open(Feature.CHECK_IN) && state.checkIn == null
            listOf(
                Triple(TaskId.MORNING_STRETCH, Feature.MORNING_STRETCH, training.morningStretchMinutes),
                Triple(TaskId.VOCAL_BASICS, Feature.VOCAL_BASICS, training.vocalBasicsMinutes),
            ).forEachIndexed { i, (task, feature, minutes) ->
                if (!open(feature)) return@forEachIndexed
                val done = task in state.tasks
                val waiting = waitCheckIn && !done
                add(
                    wakeM + 1 + i, "task:${task.name}", feature, task.title, null, null,
                    status(done, false, null, null, waiting), Tap.Open(Screen.TIMER, task.name),
                    if (waiting) PlanDetail.AFTER_CHECK_IN else "$minutes 分鐘",
                )
            }

            if (open(Feature.LESSONS)) {
                for (s in lessons) {
                    val title = when {
                        s.review -> "複習${s.subject}"
                        s.lessonId == null && s.proposed -> "${s.subject}：影片等你決定"
                        s.lessonId == null -> "${s.subject}：等小葵找影片"
                        s.title.isBlank() -> s.subject
                        else -> "${s.subject}：${s.title}"
                    }
                    val tap = when {
                        // A video is waiting for his yes or no: the lessons page decides it; searching again would pile up more.
                        s.lessonId == null && s.proposed -> Tap.Open(Screen.LESSONS)
                        s.lessonId == null -> Tap.Say("幫我找今天的${s.subject}影片")
                        s.review -> Tap.Open(Screen.QUIZ, s.lessonId.toString())
                        else -> Tap.Open(Screen.LESSONS)
                    }
                    add(
                        lessonKey, "lesson:${s.n}", Feature.LESSONS, title, null, null,
                        status(s.done, false, null, null), tap,
                        if (s.lessonId == null && !s.done) (if (s.proposed) "等你決定" else "找影片") else "",
                    )
                }
                for (h in habits) {
                    add(
                        lessonKey, "habit:${h.id}", Feature.LESSONS, h.name, null, null, status(h.done, false, null, null),
                        Tap.Say("我完成了「${h.name}」"),
                    )
                }
            }

            if (open(Feature.MEALS)) {
                val slots = listOf(
                    Triple(MealSlot.FIRST, "第一餐", time(times.firstMeal)?.plusMinutes(firstShift)),
                    Triple(MealSlot.DINNER, "晚餐", time(times.dinner)),
                    Triple(MealSlot.NIGHT, "宵夜", time(times.nightMeal)),
                )
                for ((slot, title, at) in slots) {
                    if (at == null) continue
                    val recs = state.mealsOf(slot)
                    val withPhoto = recs.filter { it.meal.photoId != null }
                    val done = withPhoto.isNotEmpty()
                    val logged = recs.isNotEmpty()
                    val kcal = recs.sumOf { it.kcal }
                    val detail = when {
                        done -> "$kcal 大卡" + if (withPhoto.any { it.meal.photoLate }) " · ${PlanDetail.LATE_PHOTO}" else ""
                        logged -> PlanDetail.NO_PHOTO
                        else -> "預算 ${state.budget(slot)} 大卡"
                    }
                    val st = if (logged && !done && !closed) ItemStatus.PENDING else status(done, false, at, null)
                    add(min(at), "meal:${slot.name}", Feature.MEALS, title, at, null, st, Tap.Open(Screen.CAMERA, slot.name), detail)
                }
            }

            if (open(Feature.WATER) && leaveBy != null) {
                val target = state.waterTargetMl(state.dayNumber)
                val rideWords = open(Feature.RIDE) && !restDay
                val goal = bottles(target, rules.water.bottleMl)
                add(
                    leaveM, "water", Feature.WATER, "喝水", null, leaveBy,
                    status(state.waterMl >= target, false, null, leaveBy), Tap.Direct("log_water", json { put("bottles", 1) }),
                    if (rideWords) "跑單前喝完 · $goal" else goal,
                )
            }

            if (open(Feature.SUNSCREEN) && leaveBy != null) {
                val done = TaskId.SUNSCREEN in state.tasks
                add(
                    leaveM, "task:SUNSCREEN", Feature.SUNSCREEN, TaskId.SUNSCREEN.title, null, leaveBy,
                    status(done, restDay, null, leaveBy), Tap.Direct("log_task", json { put("task", "SUNSCREEN") }), "${hm(leaveBy)} 前",
                )
            }

            if (open(Feature.RIDE) && leaveBy != null) {
                val out = state.rideOutAt != null
                val outAt = state.rideOutAt?.atZone(zone)?.toLocalTime()
                add(
                    leaveM, "ride:out", Feature.RIDE, "出門跑單", null, leaveBy,
                    status(out, restDay, null, leaveBy), Tap.Direct("ride_out", json { }),
                    if (outAt != null) hm(outAt) else "${hm(leaveBy)} 前",
                )
                val waiting = !out && !rideBack
                add(
                    leaveM + 1, "ride:back", Feature.RIDE, "收工", null, null,
                    status(rideBack, restDay, null, null, waiting), Tap.Direct("ride_back", json { }),
                    if (waiting) PlanDetail.AFTER_OUT else "", counts = false,
                )
            }

            addTraining(rideDay, rideBack, wakeM, leaveM)
            addOtherTasks(wakeM)
            if (open(Feature.GROOMING)) addGrooming(wakeM)
            if (open(Feature.WEIGHT) && rules.body.weighWeekday == day.dayOfWeek.value) {
                val kg = state.body?.kg
                add(
                    wakeM + 5, "body", Feature.WEIGHT, "量體重", null, null, status(kg != null, false, null, null),
                    Tap.Open(Screen.BODY), if (kg != null) "${kg} 公斤" else "",
                )
            }
            for (e in extra) if (e.feature.openAt(stage)) {
                items += Keyed(e, (e.at ?: e.until)?.let(::min) ?: (lessonKey + 1))
            }

            var sorted = items.sortedBy { it.key }.map { it.item }
            if (restDay) {
                val dinnerM = time(times.dinner)?.let(::min)
                if (dinnerM != null) {
                    sorted = sorted.map { i ->
                        val ref = (i.at ?: i.until)?.let(::min)
                        if (ref != null && ref > dinnerM) i.copy(chase = false) else i
                    }
                }
            }
            buildAdjustments(rideDay, firstShift)

            val counted = sorted.filter { it.counts && it.status != ItemStatus.OFF }
            return DayPlan(
                day = day,
                type = type,
                items = sorted,
                next = pickNext(sorted),
                adjustments = adjustments,
                riding = state.riding,
                restDay = restDay,
                unwell = state.unwell,
                done = counted.count { it.status == ItemStatus.DONE },
                total = counted.size,
            )
        }

        private fun addTraining(rideDay: Boolean, rideBack: Boolean, wakeM: Int, leaveM: Int) {
            val waitRide = rideDay && !rideBack
            val injured = state.injured
            val rounds = if (state.sick || state.firstDayBack) (training.strengthRounds - 1).coerceAtLeast(1) else training.strengthRounds
            val base = if (rideDay) leaveM + 2 else wakeM + 30
            val strength = "$rounds 輪：伏地挺身 ${training.pushUps} 下、深蹲 ${training.squats} 下、棒式 ${training.plankSeconds} 秒"
            val cardioToday = cardioScheduled()
            val list = listOf(
                Triple(TaskId.CORE, "${training.coreMinutes} 分鐘", false),
                Triple(TaskId.STRENGTH, strength, false),
                Triple(TaskId.CARDIO, "${training.cardioMinutes} 分鐘", state.sick),
                Triple(TaskId.STRETCH, "${training.stretchMinutes} 分鐘", false),
            )
            list.forEachIndexed { i, (task, text, sickOff) ->
                if (!open(task.feature)) return@forEachIndexed
                if (task == TaskId.CARDIO && !cardioToday && task !in state.tasks) return@forEachIndexed
                val done = task in state.tasks
                val off = injured || sickOff
                val waiting = waitRide && !done && !off
                add(
                    base + i, "task:${task.name}", task.feature, task.title, null, null,
                    status(done, off, null, null, waiting), Tap.Open(Screen.TIMER, task.name),
                    if (waiting) PlanDetail.AFTER_RIDE else text,
                )
            }
        }

        /** Cardio runs [TrainingRule.cardioPerWeek] times a calendar week: spread evenly, and caught up when days run short. */
        private fun cardioScheduled(): Boolean {
            val n = training.cardioPerWeek.coerceIn(0, 7)
            if (n == 0) return false
            val idx = day.dayOfWeek.value - 1
            val missing = n - week.cardioDone
            if (missing <= 0) return false
            val spread = (0 until n).map { it * 7 / n }
            return idx in spread || (7 - idx) <= missing
        }

        private fun expressionScheduled(): Boolean {
            val n = training.expressionPerWeek.coerceAtLeast(0)
            if (n == 0 || type != DayType.FREE) return false
            val missing = n - week.expressionDone
            if (missing <= 0) return false
            val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
            val free = (0..6).map { monday.plusDays(it.toLong()) }.filter { DayTypes.of(rules, it) == DayType.FREE }
            val chosen = (0 until minOf(n, free.size)).map { free[it * free.size / minOf(n, free.size)] }
            val remaining = free.count { !it.isBefore(day) }
            return day in chosen || remaining <= missing
        }

        private fun addOtherTasks(wakeM: Int) {
            val injured = state.injured
            if (open(Feature.POSTURE)) {
                val t = TaskId.POSTURE
                add(
                    wakeM + 20, "task:POSTURE", Feature.POSTURE, t.title, null, null, status(t in state.tasks, false, null, null),
                    Tap.Open(Screen.TIMER, t.name), "${training.postureMinutes} 分鐘",
                )
            }
            if (type != DayType.FREE) return
            if (open(Feature.WALK)) {
                val at = time(times.walk)
                val t = TaskId.WALK
                if (at != null) {
                    add(
                        min(at), "task:WALK", Feature.WALK, t.title, at, null, status(t in state.tasks, injured, at, null),
                        Tap.Open(Screen.TIMER, t.name), "${training.walkMinutes} 分鐘",
                    )
                }
            }
            val first = time(times.firstMeal)
            listOf(
                Triple(TaskId.DANCE, Feature.DANCE, training.danceMinutes),
                Triple(TaskId.VOCAL, Feature.VOCAL, training.vocalMinutes),
            ).forEachIndexed { i, (t, f, minutes) ->
                if (!open(f)) return@forEachIndexed
                add(
                    (first?.let(::min) ?: 700) - 6 + i, "task:${t.name}", f, t.title, null, first,
                    status(t in state.tasks, injured && t == TaskId.DANCE, null, first), Tap.Open(Screen.TIMER, t.name),
                    "${minutes} 分鐘",
                )
            }
            if (open(Feature.EXPRESSION)) {
                val t = TaskId.EXPRESSION
                if (t in state.tasks || expressionScheduled()) {
                    add(
                        (first?.let(::min) ?: 700) - 3, "task:EXPRESSION", Feature.EXPRESSION, t.title, null, null,
                        status(t in state.tasks, false, null, null), Tap.Open(Screen.TIMER, t.name), "${training.expressionMinutes} 分鐘",
                    )
                }
            }
        }

        private fun addGrooming(wakeM: Int) {
            val due = rules.grooming.filter { g ->
                val last = week.groomed[g.name]
                last == null || java.time.temporal.ChronoUnit.DAYS.between(last, day) >= g.everyDays
            }.map { it.name }
            val t = TaskId.GROOMING
            val done = t in state.tasks
            if (due.isEmpty() && !done) return
            add(
                wakeM + 40, "task:GROOMING", Feature.GROOMING, t.title, null, null, status(done, false, null, null),
                Tap.Open(Screen.TIMER, t.name), due.joinToString("、"),
            )
        }

        private fun bottles(ml: Int, bottleMl: Int): String {
            if (bottleMl <= 0) return ""
            val q = Math.round(ml.toDouble() / bottleMl * 4).toInt()
            val whole = q / 4
            return when (q % 4) {
                0 -> "$whole 壺"
                2 -> if (whole == 0) "半壺" else "$whole.5 壺"
                1 -> "$whole.25 壺"
                else -> "$whole.75 壺"
            }
        }

        private fun buildAdjustments(rideDay: Boolean, firstShift: Long) {
            val firstAt = time(times.firstMeal)?.plusMinutes(firstShift)
            if (firstShift > 0 && firstAt != null) {
                val delay = Duration.between(inst(time(times.wake)!!), state.checkInAt).toMinutes()
                adjustments += Adjustment("第一餐延後 $firstShift 分鐘，改到 ${hm(firstAt)}", "今天晚了 $delay 分鐘才簽到")
            }
            val first = state.mealsOf(MealSlot.FIRST)
            if (open(Feature.MEALS) && first.isNotEmpty() && first.sumOf { it.kcal } > rules.diet.firstMealKcal &&
                state.mealsOf(MealSlot.DINNER).isEmpty()
            ) {
                adjustments += Adjustment(
                    "晚餐預算縮減為 ${state.budget(MealSlot.DINNER)} 大卡",
                    "第一餐吃了 ${first.sumOf { it.kcal }} 大卡，超過預定的 ${rules.diet.firstMealKcal} 大卡",
                )
            }
            if (restDay) adjustments += Adjustment("今天休息日，不排跑單，晚上自由", "每週可以休息一天，訓練照常")
            if (state.injured) {
                adjustments += Adjustment("今天所有訓練暫停", "受傷中，康復之前由系統停掉訓練")
            } else if (state.sick) {
                // Name only what is open today: a closed feature never shows up, not even as "skipped".
                val what = listOfNotNull(
                    if (open(Feature.CARDIO)) "體能今天免做" else null,
                    if (open(Feature.STRENGTH)) "肌力少一輪" else null,
                )
                if (what.isNotEmpty()) adjustments += Adjustment(what.joinToString("，"), "生病中，由系統調降訓練量")
            } else if (state.firstDayBack && open(Feature.STRENGTH)) {
                adjustments += Adjustment("肌力今天少一輪", "剛康復的第一天，由系統調降訓練量")
            }
            if (state.sickDays >= 3) {
                adjustments += Adjustment("請去看醫生", "已經連續 ${state.sickDays} 天不舒服")
            }
            val sleep = state.sleepHours
            if (sleep != null && sleep < 6 && rideDay) {
                val h = Math.round(sleep * 10) / 10.0
                adjustments += Adjustment("今天少跑一點，早點收工，訓練照舊", "昨晚只睡了 $h 小時")
            }
            val raised = buildList {
                if (training.pushUps > rules.training.pushUps) add("伏地挺身 ${training.pushUps} 下")
                if (training.squats > rules.training.squats) add("深蹲 ${training.squats} 下")
                if (training.plankSeconds > rules.training.plankSeconds) add("棒式 ${training.plankSeconds} 秒")
            }
            if (raised.isNotEmpty()) {
                adjustments += Adjustment("訓練量提高：${raised.joinToString("、")}", "最近兩週都穩穩完成，所以往上加一點")
            }
            if (state.examWeek && open(Feature.LESSONS)) {
                adjustments += Adjustment("理論課減量", "這週是考試週")
            }
            if (stage >= 2 && StageDays.openedOn(stageState, stage) == day) {
                val fresh = Feature.values().filter { it.stage == stage }.joinToString("、") { it.label }
                adjustments += Adjustment("新開放：$fresh", "今天進入第 $stage 階段")
            }
        }

        private val LEAVE_ITEMS = setOf("ride:out", "task:SUNSCREEN")

        /** Sign-in first; then LATE (oldest), then what is due within 30 minutes, then an untimed one, then the next timed one. */
        private fun pickNext(sorted: List<PlanItem>): PlanItem? {
            val cand = sorted.filter { (it.status == ItemStatus.PENDING || it.status == ItemStatus.LATE) && !it.waiting }
            // The day starts with signing in: until he has, nothing else is the next thing (reminders stay quiet the same way).
            cand.firstOrNull { it.id == "check_in" }?.let { return it }
            val refInst = { i: PlanItem -> (i.at ?: i.until)?.let(::inst) ?: Instant.MAX }
            cand.filter { it.status == ItemStatus.LATE }.minByOrNull(refInst)?.let { return it }
            val pending = cand.filter { it.status == ItemStatus.PENDING }
            pending.filter { it.at != null && !inst(it.at).isAfter(now.plus(Duration.ofMinutes(30))) && !inst(it.at).isBefore(now) }
                .minByOrNull { inst(it.at!!) }?.let { return it }
            // Leaving for work (and the sunscreen before it) only has a deadline. It must not be "the next thing" all morning:
            // it becomes one in the last hour before that deadline, and otherwise waits its turn like a timed item.
            val leaveSoon = { i: PlanItem -> i.until != null && !inst(i.until).isAfter(now.plus(Duration.ofMinutes(60))) }
            pending.firstOrNull { it.at == null && it.id in LEAVE_ITEMS && leaveSoon(it) }?.let { return it }
            pending.firstOrNull { it.at == null && it.id !in LEAVE_ITEMS }?.let { return it }
            return pending.filter { it.at != null || it.id in LEAVE_ITEMS }.minByOrNull { inst((it.at ?: it.until)!!) }
        }
    }
}
