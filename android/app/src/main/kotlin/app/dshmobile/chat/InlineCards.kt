package app.dshmobile.chat

/**
 * Cards in the conversation (0.6.0, the owner: features 「自己在對話框跑出來」). A reply may hold hidden blocks
 * `<!--dshm-card TYPE\n名稱: 值\n…-->`; they never show as text: the reply is stored without them and each becomes a card under
 * it. The app skills say when to write one. Unknown types and blocks without a usable field are dropped.
 */
data class InlineCard(val type: Type, val fields: List<Pair<String, String>>) {
    enum class Type(val id: String) { WEATHER("weather"), LEDGER("ledger"), QUOTE("quote"), REMINDER("reminder") }

    operator fun get(name: String): String? = fields.firstOrNull { it.first == name }?.second
}

object InlineCards {
    const val OPEN = "<!--dshm-card"
    private const val CLOSE = "-->"
    const val MAX = 6
    const val MAX_FIELDS = 8
    const val MAX_CHARS = 60

    /** The text without the blocks (and without one still streaming) and up to [MAX] cards in the order they were written. */
    fun split(text: String): Pair<String, List<InlineCard>> {
        if (!text.contains("<!--")) return text to emptyList()
        val out = StringBuilder()
        val cards = ArrayList<InlineCard>()
        var i = 0
        while (true) {
            val at = text.indexOf(OPEN, i)
            if (at < 0) { out.append(text, i, text.length); break }
            out.append(text, i, at)
            val end = text.indexOf(CLOSE, at + OPEN.length)
            if (end < 0) return out.toString().trimEnd() to cards // still streaming: nothing of the block shows
            parse(text.substring(at + OPEN.length, end))?.let { if (cards.size < MAX) cards += it }
            i = end + CLOSE.length
        }
        return stripPartialOpen(out.toString()).replace(Regex("\n{3,}"), "\n\n").trim() to cards
    }

    private fun parse(block: String): InlineCard? {
        val lines = block.trim().lines()
        val type = InlineCard.Type.entries.firstOrNull { it.id == lines.firstOrNull()?.trim() } ?: return null
        val fields = lines.drop(1).mapNotNull { line ->
            val at = line.indexOfFirst { it == ':' || it == '：' }
            if (at <= 0) return@mapNotNull null
            val name = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            if (name.isEmpty() || value.isEmpty() || name.length > 12) null else name to value.take(MAX_CHARS)
        }.take(MAX_FIELDS)
        // The weather card is the app's own analysis and needs no field; the others say something or are nothing.
        return if (fields.isEmpty() && type != InlineCard.Type.WEATHER) null else InlineCard(type, fields)
    }

    /** A reply that is streaming the start of a marker (`<!--dsh…`) shows nothing of it yet. */
    private fun stripPartialOpen(text: String): String {
        for (n in OPEN.length - 1 downTo 4) if (text.endsWith(OPEN.substring(0, n))) return text.dropLast(n).trimEnd()
        return text
    }
}
