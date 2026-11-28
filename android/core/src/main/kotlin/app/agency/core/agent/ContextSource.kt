package app.agency.core.agent

/**
 * What the loop needs to know about this moment, rebuilt for every turn by the code that owns the facts (day state, plan,
 * stage, week). The agent never computes any of it: it only shows [block] to 小葵 and hands [facts] and the flags to [Guard].
 *
 * The agent asks for a fresh one twice per turn: before the model call (what she reads) and after the tools ran (what the
 * guard checks against, so a status a tool just set, such as an injury, counts at once).
 */
interface ContextSource {
    /** The context message of this turn: plain compact text, Traditional Chinese, every number computed by code. */
    fun block(): String

    /**
     * The same facts for the guard's number check. Usually [block]; it may add numbers she may say but that are not worth a
     * line in the block (rule values such as the daily target, the meal cap, training loads).
     */
    fun facts(): String = block()

    /**
     * What [Judge] needs to know, short: now, today's food and water totals and budgets, today's records, training load,
     * today's automatic changes, the unwell / reduced flags and the rule numbers. Usually a few lines of [block].
     */
    fun judgeFacts(): String = block()

    /** Code reduced today's training (sick, injured, first day back): only then may she speak of a lighter load. */
    val unwellReducedByCode: Boolean

    /** Words naming features that are not open yet (e.g. their labels): she must not tell him to do any of them. */
    val closedFeatureWords: List<String>

    /** She never encourages a weight below this (0 = not set). */
    val neverBelowKg: Double

    /** A short company-life scene is allowed this turn. */
    val mayPlayScene: Boolean

    /** A little affection is allowed this turn. */
    val mayBeSweet: Boolean
}
