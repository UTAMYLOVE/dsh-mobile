package app.dshmobile.chat

/**
 * 「自動 context」 (the owner, 2026-11-12): once a conversation's last prompt is past [TOKENS], and no turn runs, the app asks dsh
 * to condense it (`/compact`: the older part becomes one summary, the recent part stays word for word), so the owner need not
 * start a new conversation. dsh's own automatic condensation only starts near the models' million-token windows (its
 * threshold lives inside the agent preset and a patch cannot reach it), far too late for speed and sharpness.
 */
object AutoCompact {
    const val COMMAND = "/compact"
    const val TOKENS = 120_000L
    /** After a condensation that did not bring the prompt down, the next try waits until it grew this much more. */
    const val RETRY_GROWTH = 30_000L

    /** Whether to condense now: [tokens] of the last prompt, and the size at which this conversation was last condensed (0 = never). */
    fun due(tokens: Long?, running: Boolean, lastAt: Long): Boolean {
        if (running || tokens == null || tokens < TOKENS) return false
        return lastAt == 0L || tokens >= lastAt + RETRY_GROWTH
    }
}
