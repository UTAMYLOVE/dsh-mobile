package app.dshmobile.chat

/**
 * Where a branch starts (0.5.0: 編輯並重新送出・重新回答・從這裡開分支). dsh forks "an exact event prefix": the new conversation
 * holds every event up to and including the boundary seq. Message keys are `e<seq>` (ConversationReducer).
 */
object Branching {
    fun seqOf(key: String): Long? = key.removePrefix("e").toLongOrNull()?.takeIf { key.startsWith("e") }

    /**
     * The boundary just before the owner's message that [key] is or answers: for a user message its own seq − 1; for a reply,
     * the seq − 1 of the user message before it — from the loaded items, else from dsh's turn outline ([outline]: every turn's
     * prompt seq, also for turns paged out of the chat).
     */
    fun boundaryBefore(items: List<ChatItem>, key: String, outline: List<Long> = emptyList()): Long? {
        val seq = seqOf(key) ?: return null
        val item = items.firstOrNull { it.key == key }
        if (item is ChatItem.User) return seq - 1
        val user = items.takeWhile { it.key != key }.lastOrNull { it is ChatItem.User }?.key?.let(::seqOf)
            ?: outline.filter { it < seq }.maxOrNull()
        return user?.let { it - 1 }
    }

    /** The text of the owner's message a reply [key] answers (to send again for 重新回答). */
    fun questionOf(items: List<ChatItem>, key: String): String? =
        (items.takeWhile { it.key != key }.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.text?.takeIf { it.isNotBlank() }

    /** 從這裡開分支: a reply keeps itself (its seq), a user message is left out (seq − 1) so the branch starts with a new question. */
    fun branchPoint(items: List<ChatItem>, key: String): Long? {
        val seq = seqOf(key) ?: return null
        return if (items.firstOrNull { it.key == key } is ChatItem.User) seq - 1 else seq
    }
}
