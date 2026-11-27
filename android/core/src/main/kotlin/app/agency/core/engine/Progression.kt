package app.agency.core.engine

import app.agency.core.schedule.TrainingSpec
import java.time.LocalDate

/**
 * The strength progression rule (plan v3 §7, agency-links L15), pure. When the strength rounds were easy for a whole week —
 * every session of the last [WEEK_DAYS] days at the current step had 2 or more reps left on the last round ([EASY_EXTRA]),
 * no day that owed the rounds went without them, at least [MIN_SESSIONS] sessions — and protein reached the day's target the
 * day before, the body teacher proposes the next step, one at a time, in a fixed order: + backpack row 12, + glute bridge
 * 15, + a 4th round. Only from the current step, never while a proposal waits, and always a proposal (his yes applies it).
 */
object Progression {
    const val EASY_EXTRA = 2
    const val WEEK_DAYS = 7
    const val MIN_SESSIONS = 5
    const val MAX_STEP = 3

    /** What step [n] adds, as he reads it. */
    fun stepZh(n: Int): String = when (n) {
        1 -> "背包划船 12 下"
        2 -> "臀橋 15 下"
        3 -> "第四輪"
        else -> "原本的三個動作"
    }

    fun next(step: Int): Int? = (step + 1).takeIf { it in 1..MAX_STEP }

    /** Rounds at [spec]'s step: the plan's rounds, one more from step 3. */
    fun rounds(spec: TrainingSpec): Int = spec.strengthRounds + if (spec.progressionStep >= MAX_STEP) 1 else 0

    fun easy(extra: Int?): Boolean = extra != null && extra >= EASY_EXTRA

    /** The plan's line for the rounds at [spec]'s step. */
    fun note(spec: TrainingSpec): String =
        "${rounds(spec)} 輪：" + Routine.moves(spec.progressionStep).joinToString("、") { m -> m.name + " " + (m.reps?.let { "$it 下" } ?: "${m.seconds} 秒") }

    /** One day's strength session: whether it was easy, at which step. */
    data class Session(val easy: Boolean, val step: Int)

    /**
     * Whether the week up to [today] earns the next step from [step]: each of the [WEEK_DAYS] days had an easy session at
     * [step], or owed none ([noStrength]: a day he was ill or hurt; a rest day owes the rounds too); at least [MIN_SESSIONS] sessions.
     */
    fun weekEasy(today: LocalDate, step: Int, sessions: Map<LocalDate, Session>, noStrength: Set<LocalDate>): Boolean {
        var easy = 0
        for (back in 0 until WEEK_DAYS) {
            val d = today.minusDays(back.toLong())
            val s = sessions[d]
            when {
                s != null && s.step == step && s.easy -> easy++
                s == null && d in noStrength -> Unit
                else -> return false
            }
        }
        return easy >= MIN_SESSIONS
    }

    /** The proposal's text ([next] the step it adds). */
    fun change(next: Int): String = when (next) {
        3 -> "肌力加到四輪（每輪的動作照現在）。"
        else -> "肌力每輪加一個動作：${stepZh(next)}。"
    }
}
