package app.agency.core.brain

import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.Who
import java.time.LocalDate

/**
 * 討論 mode (arch 1.7, T12): when he raises a problem the manager discusses it with him, one question per reply with up to four
 * options and her recommendation, and ends with a proposal; nothing changes until he says yes. Company rules are never in it
 * (the fixed reply sends him to 設定), and neither is doing less training (§3b: only illness or injury, decided by code).
 *
 * The state lives in the conversation itself, so it survives a restart: a discussion is open while the newest company line
 * of today that is either a 討論 line or an end line is a 討論 line. It ends with his 「先這樣」, with his answer to a
 * proposal, or with the coach day.
 */
object Discussion {
    /** His tap on 「這裡有問題」 (or the same words typed) opens a discussion. */
    const val OPEN_WORD = "這裡有問題"

    /** What closes one. */
    val END_WORDS = setOf("先這樣", "先這樣就好", "先這樣吧", "就先這樣", "不討論了")

    /** The kind of the line that closes it on his 「先這樣」. */
    const val END_KIND = "discuss_end"

    /** The kind of the manager's line after he answered a proposal (it closes a discussion too). */
    const val DECIDED_KIND = "proposal"

    const val END_LINE = "好，先這樣。有需要再找我討論。"

    private val ENDS = setOf(END_KIND, DECIDED_KIND)

    /** Whether a discussion is open in [recent] (oldest first) on [today]; [dayOf] gives a message's coach day. */
    fun active(recent: List<Message>, today: LocalDate, dayOf: (Long) -> LocalDate): Boolean {
        for (m in recent.asReversed()) {
            if (m.who != Who.COMPANY) continue
            if (dayOf(m.at) != today) return false
            if (m.kind in ENDS) return false
            if (m.mode == MessageMode.DISCUSS) return true
        }
        return false
    }
}
