package app.dshmobile.chat

import app.dshmobile.remote.PromptPart

/**
 * Stage 2 §B.2 「另派審查員檢查 N 次」: the app asks the lead agent, in the prompt itself, to have an independent reviewer
 * subagent check its answer and to revise, [rounds] times. Official dsh is untouched; the instruction rides after a marker the
 * chat strips from the user's bubble.
 */
object ReviewRounds {
    const val MAX = 3
    const val MARKER = "\n\n---\n［審查要求］"

    fun instruction(rounds: Int): String =
        MARKER + "回覆完成前，請用子代理人工具派出一位獨立的「審查員」子代理人，請它逐點檢查你的答案（正確性、遺漏、邏輯、是否符合我的要求）並列出問題，" +
            "你再依它的意見修改。這樣重複 $rounds 輪，每輪開始時說「審查 第 k/$rounds 輪」。最後只給我修改後的最終答案，並在最後一行用一句話說明審查了幾輪、改了什麼。"

    /** [parts] with the instruction appended to the first text part (or added as one); unchanged when [rounds] is 0. */
    fun decorate(rounds: Int, parts: List<PromptPart>): List<PromptPart> {
        val n = rounds.coerceIn(0, MAX)
        if (n == 0) return parts
        val i = parts.indexOfFirst { it is PromptPart.Text }
        return if (i < 0) listOf(PromptPart.Text(instruction(n).removePrefix("\n\n---\n"))) + parts
        else parts.toMutableList().also { it[i] = PromptPart.Text((parts[i] as PromptPart.Text).text + instruction(n)) }
    }

    /** The user's own words: the review instruction is the app's, not something to show in the bubble. */
    fun strip(text: String): String = text.substringBefore(MARKER)
}
