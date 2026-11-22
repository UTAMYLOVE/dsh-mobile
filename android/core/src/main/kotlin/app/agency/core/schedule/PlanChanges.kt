package app.agency.core.schedule

import app.agency.core.model.PlanSectionKey
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** One field of a plan section the manager may propose to change (arch 1.4 tool 7): times only, never an amount. */
enum class PlanPath(val key: String, val section: PlanSectionKey, val zh: String, val date: Boolean = false) {
    CLASS_WAKE("class_wake", PlanSectionKey.SCHEDULE, "上課日起床"),
    NO_CLASS_WAKE("no_class_wake", PlanSectionKey.SCHEDULE, "沒課日起床"),
    CLASS_SLEEP_LATEST("class_sleep_latest", PlanSectionKey.SCHEDULE, "上課日最晚睡"),
    NO_CLASS_SLEEP_LATEST("no_class_sleep_latest", PlanSectionKey.SCHEDULE, "沒課日最晚睡"),
    RIDE_OUT("ride_out", PlanSectionKey.SCHEDULE, "出門跑單"),
    STRENGTH_LATEST_CLASS("strength_latest_class", PlanSectionKey.SCHEDULE, "上課日肌力最晚開始"),
    STRENGTH_LATEST_NO_CLASS("strength_latest_no_class", PlanSectionKey.SCHEDULE, "沒課日肌力最晚開始"),
    CLASS_LUNCH("class_lunch", PlanSectionKey.MEALS, "上課日午餐"),
    NO_CLASS_FIRST_MEAL("no_class_first_meal", PlanSectionKey.MEALS, "沒課日第一餐"),
    DINNER("dinner", PlanSectionKey.MEALS, "晚餐"),
    CORE("core", PlanSectionKey.TRAINING, "核心七分鐘"),
    CORE_FRIDAY("core_friday", PlanSectionKey.TRAINING, "星期五核心"),
    WALK_NO_CLASS("walk_no_class", PlanSectionKey.TRAINING, "沒課日快走"),
    WATER_START("start_day", PlanSectionKey.WATER, "開始喝水計畫那天", date = true),
    ;

    companion object {
        fun of(key: String): PlanPath? = entries.firstOrNull { it.key == key }
        val KEYS: List<String> = entries.map { it.key }
    }
}

/** One change of a proposal as he reads it on the card: [label] [before] → [after]. */
@Serializable
data class PlanOp(val path: String, val label: String, val before: String, val after: String)

/** The diff of a PLAN_CHANGE proposal (its `diff` column). */
@Serializable
data class PlanChange(val section: String, val ops: List<PlanOp>)

/**
 * The checks and the write of a plan change (arch T12 `ProposalApplier`), pure. A change may only move times (and the water
 * plan's start day): what he must do, how much, and the 21:00 on-run portion stay as they are (owner: 「訓練與規章是死規定」;
 * the 21:00 portion is a safety rule). The result must keep the day in order (wake → first meal → dinner → 21:00, core and
 * the ride before 21:00), and a latest bedtime may only move earlier (02:30 and 04:00 are the latest the owner allows).
 */
object PlanChanges {
    const val MAX_OPS = 4
    private val HHMM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private const val MEAL_GAP_MIN = 120

    /** The value of [path] in [specs] as he reads it. */
    fun value(specs: PlanSpecs, path: PlanPath): String = when (path) {
        PlanPath.CLASS_WAKE -> specs.schedule.classWake
        PlanPath.NO_CLASS_WAKE -> specs.schedule.noClassWake
        PlanPath.CLASS_SLEEP_LATEST -> specs.schedule.classSleepLatest
        PlanPath.NO_CLASS_SLEEP_LATEST -> specs.schedule.noClassSleepLatest
        PlanPath.RIDE_OUT -> specs.schedule.rideOut
        PlanPath.STRENGTH_LATEST_CLASS -> specs.schedule.strengthLatestClass
        PlanPath.STRENGTH_LATEST_NO_CLASS -> specs.schedule.strengthLatestNoClass
        PlanPath.CLASS_LUNCH -> specs.meals.classLunch
        PlanPath.NO_CLASS_FIRST_MEAL -> specs.meals.noClassFirstMeal
        PlanPath.DINNER -> specs.meals.dinner
        PlanPath.CORE -> specs.training.core
        PlanPath.CORE_FRIDAY -> specs.training.coreFriday
        PlanPath.WALK_NO_CLASS -> specs.training.walkNoClass
        PlanPath.WATER_START -> specs.water.startDay ?: "還沒開始"
    }

    private fun with(specs: PlanSpecs, path: PlanPath, v: String): PlanSpecs = when (path) {
        PlanPath.CLASS_WAKE -> specs.copy(schedule = specs.schedule.copy(classWake = v))
        PlanPath.NO_CLASS_WAKE -> specs.copy(schedule = specs.schedule.copy(noClassWake = v))
        PlanPath.CLASS_SLEEP_LATEST -> specs.copy(schedule = specs.schedule.copy(classSleepLatest = v))
        PlanPath.NO_CLASS_SLEEP_LATEST -> specs.copy(schedule = specs.schedule.copy(noClassSleepLatest = v))
        PlanPath.RIDE_OUT -> specs.copy(schedule = specs.schedule.copy(rideOut = v))
        PlanPath.STRENGTH_LATEST_CLASS -> specs.copy(schedule = specs.schedule.copy(strengthLatestClass = v))
        PlanPath.STRENGTH_LATEST_NO_CLASS -> specs.copy(schedule = specs.schedule.copy(strengthLatestNoClass = v))
        PlanPath.CLASS_LUNCH -> specs.copy(meals = specs.meals.copy(classLunch = v))
        PlanPath.NO_CLASS_FIRST_MEAL -> specs.copy(meals = specs.meals.copy(noClassFirstMeal = v))
        PlanPath.DINNER -> specs.copy(meals = specs.meals.copy(dinner = v))
        PlanPath.CORE -> specs.copy(training = specs.training.copy(core = v))
        PlanPath.CORE_FRIDAY -> specs.copy(training = specs.training.copy(coreFriday = v))
        PlanPath.WALK_NO_CLASS -> specs.copy(training = specs.training.copy(walkNoClass = v))
        PlanPath.WATER_START -> specs.copy(water = specs.water.copy(startDay = v))
    }

    /** A check that failed: [field] and [reason] go back to the model (BAD_ARGS) or decide that a proposal lapsed. */
    data class Problem(val field: String, val reason: String)

    sealed interface Checked {
        data class Ok(val specs: PlanSpecs, val change: PlanChange) : Checked
        data class Bad(val problem: Problem) : Checked
    }

    /** Checks [ops] (path key → new value) on [section] against the current [specs]; [today] bounds the water start day. */
    fun check(specs: PlanSpecs, section: PlanSectionKey, ops: List<Pair<String, String>>, today: LocalDate): Checked {
        fun bad(field: String, reason: String) = Checked.Bad(Problem(field, reason))
        if (ops.isEmpty()) return bad("ops", "至少要改一項")
        if (ops.size > MAX_OPS) return bad("ops", "一次最多改 $MAX_OPS 項")
        var next = specs
        val out = mutableListOf<PlanOp>()
        val seen = mutableSetOf<PlanPath>()
        ops.forEachIndexed { i, (key, raw) ->
            val path = PlanPath.of(key) ?: return bad("ops[$i].path", "沒有這一項")
            if (path.section != section) return bad("ops[$i].path", "${path.zh}不在這一段")
            if (!seen.add(path)) return bad("ops[$i].path", "同一項只能改一次")
            val v = raw.trim()
            if (path.date) {
                val d = runCatching { LocalDate.parse(v) }.getOrNull() ?: return bad("ops[$i].value", "日期要寫成 YYYY-MM-DD")
                if (d.isBefore(today)) return bad("ops[$i].value", "不能是已經過去的日子")
            } else if (!HHMM.matches(v)) {
                return bad("ops[$i].value", "時間要寫成 HH:mm（例如 08:20）")
            }
            val before = value(specs, path)
            if (before == v) return bad("ops[$i].value", "跟現在一樣，不用改")
            next = with(next, path, v)
            out += PlanOp(path.key, path.zh, before, v)
        }
        order(specs, next)?.let { return Checked.Bad(it) }
        return Checked.Ok(next, PlanChange(section.name, out))
    }

    /** Null when [next] keeps the day in order, else what is wrong. */
    private fun order(now: PlanSpecs, next: PlanSpecs): Problem? {
        fun m(v: String) = DayClock.minute(v)
        val s = next.schedule
        val meal = next.meals
        val t = next.training
        val ride = m(meal.rideMeal)
        if (m(s.classSleepLatest) > m(now.schedule.classSleepLatest)) return Problem("value", "上課日最晚 ${now.schedule.classSleepLatest} 要睡，只能提早")
        if (m(s.noClassSleepLatest) > m(now.schedule.noClassSleepLatest)) return Problem("value", "沒課日最晚 ${now.schedule.noClassSleepLatest} 要睡，只能提早")
        if (m(s.classWake) + 60 > m(meal.classLunch)) return Problem("value", "上課日起床到午餐至少要一小時")
        if (m(s.noClassWake) + 60 > m(meal.noClassFirstMeal)) return Problem("value", "沒課日起床到第一餐至少要一小時")
        if (m(meal.classLunch) + MEAL_GAP_MIN > m(meal.dinner)) return Problem("value", "午餐到晚餐至少要隔兩小時")
        if (m(meal.noClassFirstMeal) + MEAL_GAP_MIN > m(meal.dinner)) return Problem("value", "第一餐到晚餐至少要隔兩小時")
        if (m(meal.dinner) + 60 > ride) return Problem("value", "晚餐要在 ${meal.rideMeal} 前一小時以上（${meal.rideMeal} 那份不能動）")
        if (m(s.rideOut) !in m(meal.dinner)..ride) return Problem("value", "出門跑單要在晚餐和 ${meal.rideMeal} 之間")
        if (m(t.core) >= m(s.rideOut) || m(t.coreFriday) >= m(s.rideOut)) return Problem("value", "核心要在出門跑單前做完")
        if (m(t.walkNoClass) <= m(s.noClassWake) || m(t.walkNoClass) + t.walkMin > m(t.core)) return Problem("value", "沒課日快走要在起床後、核心前")
        if (m(s.strengthLatestClass) + 30 > m(s.classSleepLatest)) return Problem("value", "上課日肌力最晚開始要比最晚睡早半小時以上")
        if (m(s.strengthLatestNoClass) + 30 > m(s.noClassSleepLatest)) return Problem("value", "沒課日肌力最晚開始要比最晚睡早半小時以上")
        if (m(s.strengthLatestClass) <= ride || m(s.strengthLatestNoClass) <= ride) return Problem("value", "肌力在收工回家後做")
        return null
    }
}
