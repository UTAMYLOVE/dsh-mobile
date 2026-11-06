package app.dshmobile.chat

/**
 * 這次要怎麼做 (0.5.0, mockup 2): a one-shot mode for the next message, chosen in the ＋ sheet and cleared after the send.
 * PLAN runs dsh's `/plan` before the prompt (dsh then presents the plan for approval); the others name the app skill that
 * does the work, so the agent loads it.
 */
enum class ComposeMode(val zh: String, val hint: String, val skill: String?) {
    NORMAL("一般", "直接回答", null),
    PLAN("計畫模式", "先給你看計畫，你按同意才動手", null),
    CONTEMPLATE("沉思", "派 3 個 AI 各自想，再合成最好的答案", "dshm-contemplate"),
    RESEARCH("深度研究", "上網查很多資料，寫成附來源的報告", "dshm-research"),
    DELIVERABLE("成品模式", "交出文件、表格或網頁，存到書架", "dshm-deliverable");

    /** The text dsh gets: the owner's words, led by the skill to use. */
    fun apply(text: String): String = skill?.let { "請用 $it 技能：$text" } ?: text

    /** Whether dsh's `/plan` runs before the prompt. */
    val plan: Boolean get() = this == PLAN

    companion object {
        private val LEAD = Regex("^請用 dshm-[a-z-]+ 技能：")

        /** The owner's own words, for the bubble: the skill lead [apply] added is not shown. */
        fun strip(text: String): String = text.removePrefix(ConversationPrefs.TEMPORARY_LEAD).replaceFirst(LEAD, "")
    }
}
