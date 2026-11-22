package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.Signal
import app.agency.core.engine.WaterPlan
import app.agency.core.model.WaterLog
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock

/**
 * 喝水 (T17): 「喝完一壺」「喝了半壺」 (buttons or words) or the clerk's `water_half`, in half bottles of his 1 L bottle; only
 * plain water counts (「喝了豆漿」 is a meal: [plain]). Code answers with today's count against the plan's target and, before the
 * run, what is left to drink first; while riding only small sips. A `water_logged` signal for the day state.
 */
class WaterDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    suspend fun record(halves: Int, riding: Boolean): MealOutcome {
        if (halves !in 1..MAX_HALVES) return MealOutcome(null, listOf(UNCLEAR))
        val profile = snapshot.profile()
        val day = snapshot.today(profile)
        val now = clock.millis()
        val id = newId()
        store.water.insert(WaterLog(id, day.toString(), now, halves))
        val done = store.water.onDay(day.toString()).sumOf { it.halfBottles }
        val target = target(day, profile.joinDate)
        val lines = mutableListOf("記下：${drank(halves)}。今天 ${WaterPlan.bottles(done)}，目標 ${WaterPlan.bottles(target)}。")
        val plan = snapshot.plan(day)
        lines += when {
            done >= target -> "今天的水達標了。"
            riding -> "路上小口喝就好，不要一次喝太多。"
            plan.slot(app.agency.core.schedule.SlotKey.RIDE_OUT) != null ->
                "出門跑單前還要喝 ${WaterPlan.bottles(target - done)}，分幾次喝，不要一次灌。"
            else -> "還差 ${WaterPlan.bottles(target - done)}，分幾次喝完，睡前不要猛喝。"
        }
        return MealOutcome(null, lines, listOf(Signal.WaterLogged("water:$id", now, halves)))
    }

    /** Today's target (half bottles). */
    suspend fun target(day: java.time.LocalDate, joinDate: String?): Int = WaterPlan.target(day, snapshot.plans.specs().water, joinDate)

    companion object {
        const val MAX_HALVES = 4
        const val UNCLEAR = "說清楚喝了多少：「喝完一壺」或「喝了半壺」。"

        private val DRINKS = Regex("豆漿|奶|茶|咖啡|果汁|可樂|汽水|飲料|湯|酒|氣泡|檸檬|蜂蜜|運動|能量|優酪|養樂多|拿鐵")
        private val WATER = Regex("水|壺")

        /** 「喝了半壺」, 「喝了 1 壺」. */
        fun drank(halves: Int): String = if (halves == 1) "喝了半壺" else "喝了 ${WaterPlan.bottles(halves)}"

        /** Plain water only: his words name water or the bottle, and no other drink. */
        fun plain(text: String): Boolean = WATER.containsMatchIn(text) && !DRINKS.containsMatchIn(text)

        /** Half bottles in his words: 半壺 1, 一壺 2, 一壺半 3, 兩壺 4; null when no amount of the bottle is said. */
        fun halvesIn(text: String): Int? = when {
            Regex("(?:一|1)壺半").containsMatchIn(text) -> 3
            Regex("(?:兩|二|2)壺").containsMatchIn(text) -> 4
            Regex("(?:一|1)?整?壺").containsMatchIn(text) && !text.contains("半壺") -> 2
            text.contains("半壺") -> 1
            else -> null
        }
    }
}
