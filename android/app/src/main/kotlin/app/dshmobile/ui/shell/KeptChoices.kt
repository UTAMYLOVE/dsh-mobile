package app.dshmobile.ui.shell

import app.dshmobile.ui.chat.ChatUiCache
import app.dshmobile.ui.fog.UiPrefs
import java.util.concurrent.ConcurrentHashMap

/**
 * A conversation's own chat choices (思考強度, 審查輪數, the prompt size at which the app last condensed it), kept outside the
 * per-dsh [ShellSession]. They lived only in that session's [ChatUiCache], and every dsh restart (套用並重啟, 權限, a crash
 * restart, the background stop) builds a new session: the owner's 「審查 N 輪」 silently went back to 不審.
 *
 * One per process (DshMobileApp), handed to each new [ShellSession]; any thread. 審查輪數 is also written to [prefs] as it
 * changes, so it survives the process too. The other two come back from dsh itself after a process death: the strength from
 * its modelSelection ([ChatUiCache.seedEffort]), the condensation from its compaction-aware context size.
 */
class KeptChoices(private val prefs: UiPrefs? = null) {
    private data class Choice(val effort: String?, val effortKnown: Boolean, val reviewRounds: Int, val compactedAt: Long)

    private val kept = ConcurrentHashMap<String, Choice>()

    /** A conversation's cache is going away (evicted, or its dsh stopped): remember its choices. */
    fun keep(id: String, ui: ChatUiCache) {
        kept[id] = Choice(ui.effort.takeIf { ui.effortKnown }, ui.effortKnown, ui.reviewRounds, ui.compactedAt)
    }

    /**
     * Fills a new cache for conversation [id] with what was kept (this process), else the stored 審查輪數, and from then on
     * stores its 審查輪數 whenever it changes.
     */
    fun restore(id: String, ui: ChatUiCache) {
        val c = kept.remove(id)
        if (c != null) {
            if (c.effortKnown) ui.effort = c.effort
            ui.reviewRounds = c.reviewRounds
            ui.compactedAt = c.compactedAt
        } else {
            prefs?.reviewRounds(id)?.let { ui.reviewRounds = it }
        }
        // Set after the values above, so restoring writes nothing back.
        ui.onReviewRounds = { n -> prefs?.setReviewRounds(id, n) }
    }
}
