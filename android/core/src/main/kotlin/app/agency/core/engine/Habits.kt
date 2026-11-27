package app.agency.core.engine

import app.agency.core.model.LessonCategory
import app.agency.core.schedule.Habit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 學了就用 (owner 2026-11-23): a passed lesson of a subject that daily life can use becomes one small daily habit (1–5 minutes,
 * on an existing time of the day), offered by the manager and added only on his yes; one at a time — the next is offered only
 * after the last one has been steady for two weeks ([STEADY_DAYS] of [WINDOW_DAYS] days done).
 */
object Habits {
    data class Offer(val text: String, val at: String)

    private val BY_SUBJECT = mapOf(
        LessonCategory.SKIN to Offer("洗澡後保養：洗臉、化妝水、乳液", "00:30"),
        LessonCategory.OUTFIT to Offer("出門前穿搭檢查：全身照一次鏡子", "18:00"),
        LessonCategory.LANGUAGE to Offer("用英文說一句今天發生的事", "00:30"),
        LessonCategory.POSTURE to Offer("走路時抬頭、肩膀放下，檢查一次", "20:00"),
        LessonCategory.COOKING to Offer("晚餐前看一次今天的蛋白質還差多少", "17:30"),
        LessonCategory.SOCIAL to Offer("主動跟一個人打招呼或道謝", "21:00"),
        LessonCategory.ETIQUETTE to Offer("今天好好說一次「謝謝」和「辛苦了」", "21:00"),
        LessonCategory.MONEY to Offer("睡前記下今天的花費", "00:30"),
        LessonCategory.MIND to Offer("睡前寫一句今天的心情", "00:30"),
    )

    const val WINDOW_DAYS = 14
    const val STEADY_DAYS = 12

    fun offer(category: LessonCategory): Offer? = BY_SUBJECT[category]

    /**
     * Whether a habit of [category] may be offered on [today]: the subject has one, it is not a habit yet, and the newest habit
     * (if any) was added at least [WINDOW_DAYS] days ago and done on at least [STEADY_DAYS] of the last [WINDOW_DAYS] days
     * ([doneDays]: the days its slot was done).
     */
    fun mayOffer(category: LessonCategory, habits: List<Habit>, today: LocalDate, doneDays: Set<LocalDate>): Boolean {
        if (offer(category) == null || habits.any { it.category == category.name }) return false
        val last = habits.maxByOrNull { it.addedOn } ?: return true
        val added = LocalDate.parse(last.addedOn)
        if (ChronoUnit.DAYS.between(added, today) < WINDOW_DAYS) return false
        return (1..WINDOW_DAYS).count { today.minusDays(it.toLong()) in doneDays } >= STEADY_DAYS
    }
}
