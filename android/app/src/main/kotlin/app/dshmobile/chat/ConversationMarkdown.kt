package app.dshmobile.chat

/** A conversation as Markdown for 匯出: the owner's messages and the replies, in order (tool cards as one line each). */
object ConversationMarkdown {
    fun of(title: String, items: List<ChatItem>): String = buildString {
        append("# ").append(title.ifBlank { "對話" }).append("\n\n")
        for (item in items) {
            when (item) {
                is ChatItem.User -> {
                    append("## 我\n\n").append(item.text.trim()).append("\n")
                    if (item.attachments.isNotEmpty()) append("\n附件：").append(item.attachments.joinToString("、")).append("\n")
                    append("\n")
                }
                is ChatItem.Assistant -> if (item.text.isNotBlank()) append("## AI\n\n").append(item.text.trim()).append("\n\n")
                is ChatItem.Tool -> append("> 工具 ").append(item.name).append(if (item.summary.isNotBlank()) "：" + item.summary.lines().first() else "").append("\n\n")
                is ChatItem.Notice -> Unit
            }
        }
    }.trimEnd() + "\n"
}
