package app.agency.core.brain.handlers

import app.agency.core.model.DayOverride
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.PlanStore
import app.agency.core.store.AgencyStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 「今天休息」 (plan v3 §4): today becomes a rest day (no delivery, no strength, no core; stretch allowed; times of the day's
 * kind; 1700 unchanged). At most [app.agency.core.schedule.ScheduleSpec.restDaysPerWeek] a Mon–Sun week: a second request
 * changes nothing and the manager asks why (code).
 */
class RestDay(private val store: AgencyStore, private val plans: PlanStore) {
    suspend fun declare(today: LocalDate, at: Long): String {
        val o = store.days.get(today.toString())
        if (o?.restDay == true) return "今天已經是休息日了。"
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val taken = store.days.between(monday.toString(), monday.plusDays(6).toString()).filter { it.restDay }
        val spec = plans.specs().schedule
        if (taken.size >= spec.restDaysPerWeek) {
            val d = LocalDate.parse(taken.first().day)
            return "這週已經休過一天了（星期${CoachRules.weekdayZh(d.dayOfWeek)}）。為什麼還要休？先說原因。"
        }
        store.days.put((o ?: DayOverride(today.toString(), declaredAt = at)).copy(restDay = true, declaredAt = at))
        val type = plans.plan(today).type
        return "好，今天換成休息日：不跑單、不做肌力和核心，伸展可以做。時間照${type.zh}走，熱量照樣算，吃飯照常回報。" +
            "去運動、出去玩、見朋友都可以，回來說一聲做了什麼。"
    }
}
