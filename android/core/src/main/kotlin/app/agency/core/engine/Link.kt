package app.agency.core.engine

import app.agency.core.model.CompanyRules
import app.agency.core.model.RoleKey
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.Reduction
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a notification asks for attention. */
enum class NotifyKind { GAP, REMINDER, WARNING }

/**
 * What a link may do (agency-links §1.3). There is deliberately no effect that carries company rules: no link can change them.
 * - [AdjustToday]: changes today's arrangement only (a slot's time, the 豆漿 swap, a flag), within [AdjustBounds], with the
 *   「因為…所以…」 line, and he can cancel it for the day.
 * - [Proposal]: needs his yes (a today-only illness change, or a hint for 設定).
 * - [Notify], [Say], [Record]: remind, say a line in the conversation, write down.
 * - [Chain]: one more signal (depth at most [Nerve.MAX_DEPTH]).
 */
@Serializable
sealed interface Effect {
    @Serializable @SerialName("adjust")
    data class AdjustToday(val change: DayAdjust, val because: String) : Effect

    @Serializable @SerialName("proposal")
    data class Proposal(
        val todayOnly: Boolean,
        /** What changes, as he reads it on the card. */
        val change: String,
        val because: String,
        /** A today-only illness change (L2): the only way training gets lighter (§3b). */
        val reduction: Reduction? = null,
        /** L23: a daily kcal to set in 設定 › 公司規章 (accepting changes nothing by itself). */
        val suggestQuota: Int? = null,
    ) : Effect

    @Serializable @SerialName("notify")
    data class Notify(
        /** One notification per key (a later one replaces it). */
        val key: String,
        val text: String,
        val kind: NotifyKind,
        /** When to post it; null is now. */
        val at: Long? = null,
        /** While riding: sound once, later updates silent (L8, L12). */
        val soundOnce: Boolean = false,
    ) : Effect

    @Serializable @SerialName("say")
    data class Say(val text: String, val role: RoleKey = RoleKey.MANAGER) : Effect

    @Serializable @SerialName("record")
    data class Record(val what: String, val detail: String = "") : Effect

    @Serializable @SerialName("chain")
    data class Chain(val signal: Signal) : Effect
}

/** What a link reads besides the signal and the day state: the time, the rules (read only) and the plan sections. */
data class LinkContext(val now: Instant, val zone: ZoneId, val rules: CompanyRules, val specs: PlanSpecs)

/**
 * One connection (agency-links §4): 「收到訊號 A，在條件 B 下，產生效果 C」, pure Kotlin. [dailyCap] is how many signals it may
 * answer in one coach day; [batch] says where its data comes from when it is not fed yet.
 */
interface Link {
    val id: String
    val zh: String
    val dailyCap: Int
    fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect>
}
