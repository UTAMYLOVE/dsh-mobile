package app.dshmobile.coach

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 公司規章 (agency spec): fixed in code. Neither the owner nor any role can change them; [CoachSettings.profile] always carries
 * these values whatever an older version saved.
 */
object AgencyRules {
    const val QUOTA_KCAL = 1700
    const val PROTEIN_G = 140
    /** 休假餐: the one weekly cheat meal (fried food allowed), fixed on Friday. */
    val CHEAT_DAY: DayOfWeek = DayOfWeek.FRIDAY
    const val ZERO_CAL_PER_WEEK = 3
    /** 一次不要吃到撐: a single meal stays at this or less. */
    const val MEAL_MAX_KCAL = 700
    const val PLANNED_ROUNDS = 3

    private val ZERO_CAL_WORDS = listOf("零卡", "零熱量", "0卡", "0 卡", "零糖", "零度", "zero", "Zero", "ZERO")

    fun isZeroCal(name: String, flagged: Boolean): Boolean = flagged || ZERO_CAL_WORDS.any { name.contains(it) }

    /** The Monday that starts [day]'s company week. */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    /** Zero-calorie drinks already reported in [day]'s week (Monday to Sunday coach days), portions counted. */
    fun zeroCalThisWeek(meals: List<Meal>, day: LocalDate): Int {
        val start = weekStart(day)
        val end = start.plusDays(6)
        return meals.filter { m -> runCatching { LocalDate.parse(m.day) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
            .flatMap { it.items }.filter { it.zeroCal }.sumOf { kotlin.math.ceil(it.portion - 1e-9).toInt() }
    }

    fun breachLine(b: Breach, zeroCalCount: Int = 0): String = when (b) {
        Breach.FRIED -> "炸物，今天不是休假餐，記「沒照計畫」。"
        Breach.OVER700 -> "單餐超過 $MEAL_MAX_KCAL 大卡，違反公司規章。下一餐照計畫。"
        Breach.ZEROCAL -> "零卡飲料這週第 $zeroCalCount 瓶，超過 $ZERO_CAL_PER_WEEK 瓶，違反公司規章。"
    }

    /** 今日違規: every rule broken by today's meals (the warning slips themselves come in stage 2). */
    fun violationsOn(meals: List<Meal>, day: String): Int = meals.filter { it.day == day }.sumOf { it.breaches.size }

    /** 入社第 N 天: the join day is day 1. */
    fun dayNumber(join: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(join, today) + 1

    const val TRAINEE_NO = "001"
    const val LEVEL = "練習生 C"
}

/** One slot of the 經紀人's daily schedule. [minute] is minutes after midnight (null for 回家 slots with no fixed time). */
data class Slot(val key: String, val label: String, val minute: Int?, val meal: Boolean, val done: Boolean = false)

/** The daily schedule from his routine (agency spec, expansion E4). */
object AgencySchedule {
    const val LUNCH = "lunch"
    const val DINNER = "dinner"
    const val CHICKEN = "chicken"
    const val TRAINING = "training"
    const val NIGHT = "night"

    fun slots(day: LocalDate, t: SlotTimes): List<Slot> {
        val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
        return buildList {
            if (!weekend) add(Slot(LUNCH, "午餐", t.lunch, meal = true))
            add(Slot(DINNER, "晚餐", t.dinner, meal = true))
            add(Slot(CHICKEN, "跑單補一包雞胸", t.chicken, meal = true))
            add(Slot(TRAINING, "回家：肌力三輪", null, meal = false))
            add(Slot(NIGHT, "宵夜", t.night, meal = true))
        }
    }

    /**
     * Today's slots with their ticks: each meal ticks the meal slot whose time is nearest its local time (a time after midnight
     * but before the coach day's start counts as the same evening, +24 h); a training report of the planned rounds (or with no
     * number) ticks 肌力三輪.
     */
    fun ticked(day: LocalDate, t: SlotTimes, meals: List<Meal>, trainings: List<TrainingEntry>, zone: ZoneId, dayStartHour: Int): List<Slot> {
        val slots = slots(day, t)
        val mealSlots = slots.filter { it.meal && it.minute != null }
        val hit = mutableSetOf<String>()
        meals.filter { it.day == day.toString() }.forEach { m ->
            val local = Instant.ofEpochMilli(m.at).atZone(zone)
            var minute = local.hour * 60 + local.minute
            if (local.hour < dayStartHour) minute += 24 * 60
            mealSlots.minByOrNull { kotlin.math.abs(it.minute!! - minute) }?.let { hit += it.key }
        }
        if (trainings.any { it.day == day.toString() && (it.rounds == null || it.rounds >= AgencyRules.PLANNED_ROUNDS) }) hit += TRAINING
        return slots.map { it.copy(done = it.key in hit) }
    }

    fun clock(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)
}

/** The schedule's times (settings; defaults from his routine). */
data class SlotTimes(val lunch: Int = 12 * 60, val dinner: Int = 17 * 60 + 30, val chicken: Int = 21 * 60, val night: Int = 23 * 60 + 30)
