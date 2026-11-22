package app.agency.core.brain.handlers

import app.agency.core.model.Message
import app.agency.core.model.SignKind
import app.agency.core.model.Who
import app.agency.core.store.AgencyStore

/**
 * 睡前回顧 at sign-out (T19, plan v3 §4 「睡前簽退與檢討」): code asks two questions, one at a time — one thing done well today,
 * one thing to fix tomorrow — and keeps his answers on the sign-out row. The question waiting is the last company line of the
 * coach day ([GOOD_KIND] or [FIX_KIND]); his next message is its answer. The 檢討卡 and the proposal come from L19.
 */
class ReviewDesk(private val store: AgencyStore) {
    enum class Open { GOOD, FIX }

    /** The review question waiting for [day], or null. [history] is the conversation before his message, oldest first. */
    fun open(history: List<Message>, dayOf: (Long) -> String, day: String): Open? {
        val last = history.lastOrNull { it.who == Who.COMPANY } ?: return null
        if (dayOf(last.at) != day) return null
        return when (last.kind) {
            GOOD_KIND -> Open.GOOD
            FIX_KIND -> Open.FIX
            else -> null
        }
    }

    /** Keeps an answer on today's sign-out row; false when he has not signed out today. */
    suspend fun keep(day: String, open: Open, text: String): Boolean {
        val out = store.signs.onDay(day).filter { it.kind == SignKind.OUT }.minByOrNull { it.at } ?: return false
        val t = clean(text)
        store.signs.upsert(if (open == Open.GOOD) out.copy(reviewGood = t) else out.copy(reviewFix = t))
        return true
    }

    companion object {
        const val GOOD_KIND = "review_good"
        const val FIX_KIND = "review_fix"
        const val LINE_KIND = "review_line"
        const val MAX_CHARS = 60
        const val ASK_GOOD = "睡前回顧，兩題就好。第一題：今天做得好的一件事是什麼？"
        const val ASK_FIX = "第二題：明天要改的一件事是什麼？"
        const val GOOD_NIGHT = "晚安，明天見。"

        fun clean(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(MAX_CHARS)
    }
}
