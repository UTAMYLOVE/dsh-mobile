package app.agency.core.plan

import app.agency.core.base.Days
import app.agency.core.day.DayState
import java.time.Duration
import java.time.Instant

/**
 * Quick replies that fit the moment, in his voice, so most of the day is one tap instead of typing. They come from the plan, so a
 * feature that is not open never produces one. Most urgent first, at most [MAX].
 */
object Chips {
    const val MAX = 4

    fun of(plan: DayPlan, state: DayState, now: Instant): List<String> {
        val zone = state.zone
        fun inst(t: java.time.LocalTime) = Days.at(plan.day, t, zone)
        fun item(id: String) = plan.items.firstOrNull { it.id == id }
        fun actionable(i: PlanItem?) = i != null && (i.status == ItemStatus.PENDING || i.status == ItemStatus.LATE) && !i.waiting
        val out = ArrayList<String>()

        val checkOut = item("check_out")
        if (actionable(checkOut) && checkOut!!.at?.let { !now.isBefore(inst(it)) } == true) out += "我要睡了"

        if (state.riding && item("ride:back")?.status != ItemStatus.OFF && item("ride:back") != null) out += "收工了"

        val rideOut = item("ride:out")
        if (actionable(rideOut) && rideOut!!.until?.let { !now.isBefore(inst(it).minus(Duration.ofMinutes(60))) } == true) {
            out += "我出門跑單了"
        }

        val meal = plan.items.firstOrNull { i ->
            i.id.startsWith("meal:") && i.status != ItemStatus.OFF && i.status != ItemStatus.DONE && i.status != ItemStatus.MISSED &&
                i.at?.let { !now.isBefore(inst(it).minus(Duration.ofMinutes(30))) } == true
        }
        if (meal != null) out += "我吃完了"

        val training = plan.items.any { i ->
            i.id.startsWith("task:") && i.feature in TRAINING && actionable(i)
        }
        if (training && !state.riding) out += "開始訓練"

        val checkIn = item("check_in")
        if (actionable(checkIn) && checkIn!!.at?.let { !now.isBefore(inst(it)) } == true) out += "早安"

        if (actionable(item("water"))) out += "喝完一壺了"

        if (plan.items.any { it.id.startsWith("lesson:") && actionable(it) }) out += "今天上什麼課"
        return out.take(MAX)
    }

    private val TRAINING = setOf(
        app.agency.core.stage.Feature.CORE, app.agency.core.stage.Feature.STRENGTH,
        app.agency.core.stage.Feature.CARDIO, app.agency.core.stage.Feature.STRETCH,
    )
}
