package app.dshmobile.config

import app.dshmobile.remote.PromptPart

/**
 * Stage 2 batch 2 「自動選模型」: a cheap look at the message decides between DeepSeek Flash and Pro. Long messages, attachments,
 * code, analysis, planning and creative writing go to Pro; short everyday questions to Flash. Never touches a Claude choice.
 */
object ModelRouter {
    const val FLASH = "deepseek-flash"
    const val PRO = "deepseek-v4-pro"
    private const val LONG_CHARS = 280
    private val HEAVY = listOf(
        "程式", "程式碼", "code", "debug", "bug", "分析", "比較", "規劃", "計畫", "設計", "架構", "證明", "推導", "數學",
        "寫一篇", "寫一章", "小說", "章", "報告", "論文", "翻譯整篇", "重構", "refactor", "審查", "review", "研究", "步驟",
        "```", "function", "class ", "sql", "python", "kotlin", "javascript",
    )

    fun pick(parts: List<PromptPart>): String {
        val text = parts.filterIsInstance<PromptPart.Text>().joinToString("\n") { it.text }
        if (parts.any { it !is PromptPart.Text }) return PRO
        if (text.length >= LONG_CHARS) return PRO
        val lower = text.lowercase()
        return if (HEAVY.any { it in lower }) PRO else FLASH
    }
}
