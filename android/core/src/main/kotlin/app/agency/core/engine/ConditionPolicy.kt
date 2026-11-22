package app.agency.core.engine

/**
 * The morning 狀態打分 (arch 1.5 `ConditionPolicy`, T19), pure. The score is recorded and read gently, never as a reason to do
 * less (agency-links §3b, owner 2026-11-22): a low score makes the day gentle and the plan stays (「今天照表」); only 生病 or
 * 受傷, marked by him, goes on to L2's question and today-only proposal, with the line to eat normally and see a doctor if it
 * goes on.
 */
object ConditionPolicy {
    val SCORES = 1..5

    /** The score in his words: 「3」「3 分」「三分」; null for anything else. */
    fun parse(text: String): Int? {
        val m = Regex("^\\s*([1-5一二三四五])\\s*分?\\s*$").find(text) ?: return null
        return m.groupValues[1].let { it.toIntOrNull() ?: "一二三四五".indexOf(it) + 1 }
    }

    const val ASK = "今天狀態幾分？一到五分，按下面的分數，或直接打數字。"

    fun line(score: Int, ill: IllKind?): String = when {
        ill != null -> "記下：今天 $score 分，${ill.zh}。先照顧身體：正常吃東西、多喝水；一直不好就去看醫生。"
        score <= DayState.LOW_SCORE -> "記下：今天 $score 分。哪裡不舒服要說；只是累或沒睡好的話，今天照表，晚上早點睡。"
        score == 3 -> "記下：今天 3 分。今天照表。"
        else -> "記下：今天 $score 分。很好，照表走。"
    }
}
