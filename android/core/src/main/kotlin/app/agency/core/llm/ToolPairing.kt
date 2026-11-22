package app.agency.core.llm

/**
 * Makes a turn list well formed before it is sent: every tool call of an assistant turn gets exactly one result right after it,
 * and a result that answers no call of the assistant turn before it is dropped. Both providers reject a request otherwise
 * (an unanswered call, a stray or duplicated result). A missing result becomes an error result, so the model sees the call failed.
 * The idea follows Kai's message pairing repair (Apache-2.0, see KaiNotice.md); this code is our own.
 */
object ToolPairing {
    const val MISSING_RESULT = """{"error":"FAILED","reason":"no result"}"""

    fun repair(turns: List<Turn>): List<Turn> {
        val out = ArrayList<Turn>(turns.size)
        var pending = LinkedHashSet<String>()
        fun close() {
            pending.forEach { out += Turn.ToolResult(it, MISSING_RESULT, isError = true) }
            pending = LinkedHashSet()
        }
        for (t in turns) {
            when (t) {
                is Turn.ToolResult -> if (pending.remove(t.callId)) out += t
                is Turn.Assistant -> {
                    close()
                    out += t
                    pending = t.toolCalls.mapTo(LinkedHashSet()) { it.id }
                }
                else -> {
                    close()
                    out += t
                }
            }
        }
        close()
        return out
    }
}
