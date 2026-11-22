package app.agency.core.brain.handlers

import app.agency.core.engine.ConditionPolicy
import app.agency.core.engine.IllKind
import app.agency.core.engine.Signal
import app.agency.core.model.ConditionLog
import app.agency.core.store.AgencyStore

/**
 * The morning 狀態打分 (T19): 1–5 with how he slept, his energy and whether he is ill or hurt (the picker, or the number
 * typed after sign-in). One row a day (a second answer corrects it). The line is code's ([ConditionPolicy]); the signals go
 * to the nerve: the score (gentle on a low one, the plan stays), and 生病/受傷 as [Signal.Unwell] (L2's proposal).
 */
class ConditionDesk(private val store: AgencyStore, private val newId: () -> String) {
    data class Done(val line: String, val signals: List<Signal>)

    suspend fun record(day: String, at: Long, score: Int, sleep: Int? = null, energy: Int? = null, ill: IllKind? = null): Done {
        if (score !in ConditionPolicy.SCORES) return Done("分數要是一到五。", emptyList())
        val again = store.conditions.get(day) != null
        store.conditions.put(ConditionLog(day, at, score, sleep?.takeIf { it in ConditionPolicy.SCORES }, energy?.takeIf { it in ConditionPolicy.SCORES }, ill != null))
        val signals = buildList {
            add(Signal.ConditionScored("condition:${newId()}", at, score, ill))
            ill?.let { add(Signal.Unwell(newId(), at, it, it.zh)) }
        }
        return Done((if (again) "改好了。" else "") + ConditionPolicy.line(score, ill), signals)
    }
}
