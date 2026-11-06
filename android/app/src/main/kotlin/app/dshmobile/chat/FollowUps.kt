package app.dshmobile.chat

/**
 * 建議下一步 (0.5.0): the app skill `dshm-followups` asks the model to end an ordinary answer with
 * `<!--dshm-followups\n問題一\n問題二\n-->`. The block never shows: the reply is stored without it and its lines become chips.
 */
object FollowUps {
    const val OPEN = "<!--dshm-followups"
    private const val CLOSE = "-->"
    const val MAX = 3
    const val MAX_CHARS = 30

    /** The text without the block (and without a half-streamed one) and up to [MAX] suggestions of at most [MAX_CHARS] each. */
    fun split(text: String): Pair<String, List<String>> {
        val at = text.lastIndexOf(OPEN)
        if (at < 0) return stripPartialOpen(text) to emptyList()
        val end = text.indexOf(CLOSE, at + OPEN.length)
        val body = text.substring(0, at).trimEnd()
        if (end < 0) return body to emptyList() // still streaming
        val items = text.substring(at + OPEN.length, end).lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_CHARS }
            .take(MAX).toList()
        val tail = text.substring(end + CLOSE.length).trim()
        return (if (tail.isEmpty()) body else "$body\n\n$tail") to items
    }

    /** A reply that is streaming the start of the marker (`<!--dsh…`) shows nothing of it yet. */
    private fun stripPartialOpen(text: String): String {
        for (n in OPEN.length - 1 downTo 4) if (text.endsWith(OPEN.substring(0, n))) return text.dropLast(n).trimEnd()
        return text
    }
}
