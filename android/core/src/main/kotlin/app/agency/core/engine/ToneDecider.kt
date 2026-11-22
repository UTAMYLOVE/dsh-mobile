package app.agency.core.engine

/** How the reply sounds; decided by code, never by the model (persona cards say how each one sounds). */
enum class Tone { COLD, GENTLE, SWEET }

/**
 * What the tone may depend on. There is deliberately no input for unanswered messages, time since his last reply or how often
 * he talks: she never turns cold because he was away (no attachment mechanics).
 *
 * [ownerText] is his message of this turn (null for a nudge); [intakeKind] the Intake kind code settled on; [redFlag] the
 * red flags of §1.5 (dizziness, unwell, two or more reported days under the floor); [breachToday], [openGapDue] and
 * [doneSinceLastSweet] come from the rule engine (T11) once it exists.
 */
data class ToneInputs(
    val ownerText: String? = null,
    val intakeKind: String? = null,
    val redFlag: Boolean = false,
    val breachToday: Boolean = false,
    val openGapDue: Boolean = false,
    val doneSinceLastSweet: Int = 0,
)

/**
 * GENTLE whenever he talks about feelings, being unwell or a real difficulty, or a red flag is on: unconditional, whatever
 * else happened today. SWEET (a little 撒嬌) only as a reward: no breach today, no gap due so far, and at least one item done
 * since the last SWEET. Otherwise COLD, her normal voice (never a punishment).
 */
object ToneDecider {
    val GENTLE_KINDS = setOf("unwell", "mood")

    /** Feelings, illness and real difficulty, in the words he uses. */
    val GENTLE_WORDS = listOf(
        "頭暈", "暈", "不舒服", "生病", "發燒", "感冒", "頭痛", "頭很痛", "肚子痛", "胃痛", "拉肚子", "想吐", "吐了", "受傷", "扭到",
        "喉嚨痛", "咳嗽", "失眠", "睡不著", "好累", "很累", "累死", "心情", "難過", "傷心", "壓力", "焦慮", "想哭", "哭了", "崩潰",
        "撐不下去", "好煩", "很煩", "低落", "沮喪", "憂鬱", "害怕", "孤單", "被罵", "吵架", "分手", "出事", "車禍",
    )

    fun wantsGentle(ownerText: String?): Boolean = ownerText != null && GENTLE_WORDS.any { ownerText.contains(it) }

    fun decide(i: ToneInputs): Tone = when {
        i.redFlag || i.intakeKind in GENTLE_KINDS || wantsGentle(i.ownerText) -> Tone.GENTLE
        !i.breachToday && !i.openGapDue && i.doneSinceLastSweet >= 1 -> Tone.SWEET
        else -> Tone.COLD
    }
}
