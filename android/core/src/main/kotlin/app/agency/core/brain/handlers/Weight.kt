package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.IdolCard
import app.agency.core.engine.Signal
import app.agency.core.model.Baseline
import app.agency.core.model.WeightLog
import app.agency.core.rules.CoachRules
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A weight he typed (「今天體重 79.5」; the scale comes later): only a number he wrote himself (Intake keeps those only),
 * recorded with the waist when given, answered by code (身體質量指數 and its zone when his height is known, the next goal only;
 * nothing below 52 kg is ever named), and a `weight_logged` signal for L21 and L23.
 */
class WeightDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    suspend fun record(kg: Double, waistCm: Double?): MealOutcome {
        val profile = snapshot.profile()
        val rules = snapshot.rules()
        val now = clock.millis()
        val day = snapshot.today(profile).toString()
        val id = newId()
        store.weights.insert(WeightLog(id, day, now, kg, waistCm))
        val sb = StringBuilder("記下：${CoachRules.kg(kg)} 公斤")
        waistCm?.let { sb.append("（腰圍 ${CoachRules.kg(it)} 公分）") }
        sb.append("。")
        val height = profile.heightCm ?: store.baselines.get(Baseline.JOIN)?.heightCm
        if (height != null) sb.append("身體質量指數 ${CoachRules.bmi(kg, height)}，${CoachRules.zone(kg, height).zh}。")
        val goal = IdolCard.nextGoal(rules, kg)
        if (goal != null) {
            sb.append("下一個目標 ${CoachRules.kg(goal)} 公斤，還差 ${BigDecimal(kg - goal).setScale(1, RoundingMode.HALF_UP).toPlainString()} 公斤。")
        } else {
            sb.append("階段目標都到了。")
        }
        return MealOutcome(null, listOf(sb.toString()), listOf(Signal.WeightLogged("weight:$id", now, kg)))
    }
}
