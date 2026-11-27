package app.agency.core.brain.handlers

import app.agency.core.model.DayOverride
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.PlanStore
import app.agency.core.store.AgencyStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 「今天休息」 (owner 2026-11-26): today becomes a rest day, which only means no delivery: classes, the core, the rounds, the
 * stretch and the 理論課 stay (the training in the no-run block); times of the day's kind; 1700 unchanged; the evening after the
 * training is free time. At most [app.agency.core.schedule.ScheduleSpec.restDaysPerWeek] a Mon–Sun week: a second request
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
        val plan = plans.plan(today)
        val at = plan.slot(app.agency.core.schedule.SlotKey.CORE)?.time ?: plans.specs().training.noRideTrainingAt
        return "好，今天換成休息日：只是不跑單。有課照上，核心、肌力、伸展、理論課照做（訓練 $at 開始）。時間照${plan.type.zh}走，熱量照樣算，吃飯照常回報。" +
            "訓練做完，晚上是自由時間：唱歌、運動、出去玩、見朋友都可以，我不排也不催。"
    }
}
