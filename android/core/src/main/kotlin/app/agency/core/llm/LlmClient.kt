package app.agency.core.llm

import app.agency.core.metering.CallBudget
import app.agency.core.metering.SpendGuard

/** One model call. Implementations never log a key, a prompt or a reply, and never switch to another provider by themselves. */
fun interface LlmClient {
    suspend fun chat(request: ChatRequest): ChatResult
}

/**
 * The checks every paid call makes before any socket opens, in this order: the spend cap (CAP), then a usable key (NO_KEY);
 * the caller checks its own setup next, and [takeBudget] comes last so a call that could not go out does not use the budget.
 */
class CallChecks(private val spend: SpendGuard, private val budget: CallBudget) {
    fun blocked(key: String?): Fail? = when {
        spend.isBlocked() -> Fail.CAP
        usableKey(key) == null -> Fail.NO_KEY
        else -> null
    }

    fun takeBudget(): Fail? = if (budget.tryAcquire()) null else Fail.RATE

    companion object {
        /** A key fit for one header: trimmed, non-empty, printable ASCII. */
        fun usableKey(k: String?): String? = k?.trim()?.takeIf { s -> s.isNotEmpty() && s.all { it in '!'..'~' } }
    }
}
