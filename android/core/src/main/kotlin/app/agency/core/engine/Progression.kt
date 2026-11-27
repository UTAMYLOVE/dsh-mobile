package app.agency.core.engine

import app.agency.core.schedule.TrainingSpec
import java.time.LocalDate

/**
 * 訓練升級 (owner 2026-11-22/26, agency-links §3c), pure and AUTOMATIC (no question): when the training is truly stable — the
 * last [WINDOW_DAYS] days had at least [MIN_SESSIONS] strength sessions, every one of them done in full with [EASY_EXTRA] or
 * more left on the last round, none owed and missed, no illness or injury, he said 「輕鬆」 at least [MIN_EASY_SAID] times,
 * and protein reached the target on at least [PROTEIN_DAYS] of the last 7 days — one item goes up one step, in turn on three
 * ladders (strength, core, cardio). After a step nothing more for [HOLD_DAYS] days; a step that he cannot finish stays (never
 * back down): two sessions in a row not in full after a step only say so.
 */
object Progression {
    const val EASY_EXTRA = 2
    const val WINDOW_DAYS = 14
    const val MIN_SESSIONS = 6
    const val MIN_EASY_SAID = 2
    const val PROTEIN_DAYS = 5
    const val HOLD_DAYS = 14

    /** Strength: + backpack row, + glute bridge, 4th round, push-ups 18 → 20, plank 75 → 90, the harder versions. */
    const val MAX_STEP = 9
    const val CORE_MAX = 3
    const val CARDIO_MAX = 3

    /** The three ladders; one item a step, in this order in turn. */
    enum class Ladder(val zh: String) { STRENGTH("肌力"), CORE("核心"), CARDIO("體能") }

    /** What strength step [n] adds, as he reads it. */
    fun stepZh(n: Int): String = when (n) {
        1 -> "背包划船 12 下"
        2 -> "臀橋 15 下"
        3 -> "第四輪"
        4 -> "伏地挺身 18 下"
        5 -> "伏地挺身 20 下"
        6 -> "棒式 75 秒"
        7 -> "棒式 90 秒"
        8 -> "伏地挺身換成腳墊高伏地挺身"
        9 -> "慢速深蹲換成分腿蹲"
        else -> "原本的三個動作"
    }

    fun coreZh(n: Int): String = when (n) {
        1 -> "每個動作 75 秒（約 9 分鐘）"
        2 -> "每個動作 86 秒（約 10 分鐘）"
        3 -> "換進階動作（船式、完整捲腹、懸空抬腿）"
        else -> "七分鐘"
    }

    fun cardioZh(n: Int): String = when (n) {
        1 -> "每分鐘動 50 秒"
        2 -> "每分鐘動 55 秒"
        3 -> "加到 12 分鐘"
        else -> "10 分鐘、每分鐘動 45 秒"
    }

    fun next(step: Int): Int? = (step + 1).takeIf { it in 1..MAX_STEP }

    /** Rounds at [spec]'s step: the plan's rounds, one more from step 3. */
    fun rounds(spec: TrainingSpec): Int = spec.strengthRounds + if (spec.progressionStep >= 3) 1 else 0

    fun easy(extra: Int?): Boolean = extra != null && extra >= EASY_EXTRA

    /** The plan's line for the rounds at [spec]'s step. */
    fun note(spec: TrainingSpec): String =
        "${rounds(spec)} 輪：" + Routine.moves(spec.progressionStep).joinToString("、") { m -> m.name + " " + (m.reps?.let { "$it 下" } ?: "${m.seconds} 秒") }

    /** One day's strength session: done in full, with the extra on the last round ([easy]), at [step]. */
    data class Session(val easy: Boolean, val step: Int, val full: Boolean = true)

    /** What his days give the rule. */
    data class Stability(
        val sessions: Map<LocalDate, Session>,
        val missedStrength: Set<LocalDate>,
        val illDays: Set<LocalDate>,
        val easySaid: List<LocalDate>,
        val proteinByDay: Map<LocalDate, Double>,
        val proteinG: Int,
    )

    /** Whether [today] is truly stable after the last step ([lastStep]: the day it was taken, or null). */
    fun stable(today: LocalDate, s: Stability, lastStep: LocalDate?): Boolean {
        if (lastStep != null && today.isBefore(lastStep.plusDays(HOLD_DAYS.toLong()))) return false
        val from = today.minusDays(WINDOW_DAYS - 1L)
        fun inWindow(d: LocalDate) = !d.isBefore(from) && !d.isAfter(today)
        val window = s.sessions.filterKeys(::inWindow)
        // Two whole weeks: his sessions go back at least to the window's first day.
        if (s.sessions.keys.minOrNull()?.isAfter(from) != false) return false
        if (window.size < MIN_SESSIONS || window.values.any { !it.easy }) return false
        if (s.missedStrength.any(::inWindow) || s.illDays.any(::inWindow)) return false
        if (s.easySaid.count(::inWindow) < MIN_EASY_SAID) return false
        val protein = (1..7).count { back -> (s.proteinByDay[today.minusDays(back.toLong())] ?: 0.0) >= s.proteinG }
        return protein >= PROTEIN_DAYS
    }

    /** The ladder that goes up next: in turn (strength, core, cardio), skipping one at its top or not open ([cardioOpen]). */
    fun nextLadder(spec: TrainingSpec, cardioOpen: Boolean): Ladder? {
        val order = Ladder.entries
        val start = (spec.progressionStep + spec.coreStep + spec.cardioStep) % order.size
        return (0 until order.size).map { order[(start + it) % order.size] }.firstOrNull { l ->
            when (l) {
                Ladder.STRENGTH -> spec.progressionStep < MAX_STEP
                Ladder.CORE -> spec.coreStep < CORE_MAX
                Ladder.CARDIO -> cardioOpen && spec.cardioStep < CARDIO_MAX
            }
        }
    }

    /** [spec] one step up on [ladder], taken on [day]. */
    fun up(spec: TrainingSpec, ladder: Ladder, day: LocalDate): TrainingSpec = when (ladder) {
        Ladder.STRENGTH -> spec.copy(progressionStep = spec.progressionStep + 1)
        Ladder.CORE -> spec.copy(coreStep = spec.coreStep + 1)
        Ladder.CARDIO -> spec.copy(cardioStep = spec.cardioStep + 1)
    }.copy(lastStepDay = day.toString())

    /** What the step to [spec]'s new level on [ladder] adds. */
    fun change(ladder: Ladder, spec: TrainingSpec): String = when (ladder) {
        Ladder.STRENGTH -> if (spec.progressionStep == 3) "肌力加到四輪（每輪的動作照現在）。" else "肌力：${stepZh(spec.progressionStep)}。"
        Ladder.CORE -> "核心：${coreZh(spec.coreStep)}。"
        Ladder.CARDIO -> "體能：${cardioZh(spec.cardioStep)}。"
    }

    /** The old proposal's text (a strength step [next] proposed before the rule was automatic). */
    fun change(next: Int): String = when (next) {
        3 -> "肌力加到四輪（每輪的動作照現在）。"
        else -> "肌力每輪加一個動作：${stepZh(next)}。"
    }
}
