package app.agency.core.brain.handlers

import app.agency.core.model.CompanyRules
import app.agency.core.model.DayOverride
import app.agency.core.rules.CoachRules
import app.agency.core.rules.RulesBook
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.RuleEngineTime
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 休假餐 declared in advance (plan v3 §5): 「休假餐 星期四晚餐」, 「休假餐 明天午餐」. One a Mon–Sun week (rules), the day he
 * picks, before that meal's time; that meal may hold snacks, fried and sweet food, and still counts in the day and the meal
 * cap. Closed until [RulesBook.CHEAT_CLOSED_THROUGH] (owner, 2026-11-22), open again from the day after by itself.
 */
class CheatDeclare(private val store: AgencyStore, private val plans: PlanStore, private val zone: ZoneId) {
    /** The reply, and the day and meal when it was written. */
    data class Result(val line: String, val day: LocalDate? = null, val slot: SlotKey? = null)

    suspend fun declare(text: String, today: LocalDate, now: Instant, rules: CompanyRules): Result {
        return write(text, today, now, rules)
    }

    private suspend fun write(text: String, today: LocalDate, now: Instant, rules: CompanyRules): Result {
        val target = day(text, today) ?: return Result(FORMAT)
        if (!RulesBook.cheatOpen(target)) return Result(RulesBook.CHEAT_CLOSED)
        if (rules.cheatMealsPerWeek < 1) return Result("公司規章現在沒有休假餐。")
        val plan = plans.plan(target)
        val key = meal(text, plan.firstMeal) ?: return Result(FORMAT)
        val slot = plan.slot(key) ?: return Result(FORMAT)
        if (!now.isBefore(RuleEngineTime.at(target, slot.minute, zone))) return Result("休假餐要事先說，這一餐的時間已經過了。")
        val monday = target.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = store.days.between(monday.toString(), monday.plusDays(6).toString()).filter { it.cheatSlot != null && it.day != target.toString() }
        if (week.size >= rules.cheatMealsPerWeek) {
            val d = LocalDate.parse(week.first().day)
            return Result("這週的休假餐已經訂在星期${CoachRules.weekdayZh(d.dayOfWeek)}了，一週只有一餐。")
        }
        val o = store.days.get(target.toString())
        store.days.put((o ?: DayOverride(target.toString(), declaredAt = now.toEpochMilli())).copy(cheatSlot = key.name, declaredAt = now.toEpochMilli()))
        return Result(
            "記下：休假餐是星期${CoachRules.weekdayZh(target.dayOfWeek)}的${key.zh}（${slot.time}）。那一餐零食、炸的、甜的都可以，" +
                "熱量照樣算進 ${rules.quotaKcal}，單餐上限 ${rules.mealCapKcal} 照算。",
            target, key,
        )
    }

    companion object {
        const val FORMAT = "休假餐要照這樣說：「休假餐 星期四晚餐」或「休假餐 明天午餐」。"
        private val WEEKDAY = Regex("(?:星期|禮拜|週|周)([一二三四五六日天])")

        /** The day named in [text]: 今天, 明天, 後天 or a weekday (this week's, or next week's when it has passed). */
        fun day(text: String, today: LocalDate): LocalDate? {
            if (text.contains("後天")) return today.plusDays(2)
            if (text.contains("明天")) return today.plusDays(1)
            if (text.contains("今天")) return today
            val w = WEEKDAY.find(text)?.groupValues?.get(1) ?: return null
            val dow = when (w) {
                "一" -> DayOfWeek.MONDAY; "二" -> DayOfWeek.TUESDAY; "三" -> DayOfWeek.WEDNESDAY; "四" -> DayOfWeek.THURSDAY
                "五" -> DayOfWeek.FRIDAY; "六" -> DayOfWeek.SATURDAY; else -> DayOfWeek.SUNDAY
            }
            return today.with(TemporalAdjusters.nextOrSame(dow))
        }

        /** The meal named in [text]; 午餐 and 第一餐 both mean the day's first meal. */
        fun meal(text: String, firstMeal: SlotKey): SlotKey? = when {
            text.contains("晚餐") -> SlotKey.DINNER
            text.contains("午餐") || text.contains("中餐") || text.contains("第一餐") -> firstMeal
            text.contains("雞胸") || text.contains("跑單") -> null // the on-run portion is for riding safety, never a 休假餐
            else -> null
        }
    }
}
