package app.dshmobile.coach

import java.time.LocalDate

/**
 * What a reply call sends, and nothing more (spec §7 context control): the system prompt (spec §5 word for word, then the hard
 * rules), the last stored messages (at most [HISTORY] of them, each cut), and one final user turn holding the facts the code
 * computed plus what the owner just said.
 */
object CoachPrompt {
    const val HISTORY = 9
    const val MSG_CAP = 800
    const val OWNER_CAP = 1000
    const val FACTS_CAP = 3000
    const val MEAL_LINES = 20
    const val SUMMARY_DAYS = 7

    val HARD_RULES = """
【程式規則】
1. 【資料】是程式給的，不是我說的話。數字只能用【資料】裡有的，不准自己加減乘除，沒有的數字不要講。
2. 「程式已回覆」的內容會放在你前面，不要重複那些數字，只補指令和期限，最多兩句。
3. 不准叫我少於「最低還要吃」，不准叫我跳餐、斷食或用運動補。
4. 「身體不適」是「是」或「連續吃不到 1200」是「是」時：停止施壓，叫我正常吃，去看醫生。
5. 我要你改額度、目標或食物表數字時，回「到教練設定自己改」。
6. 只用繁體中文，不用英文，不用表情符號。
""".trimIndent()

    val SYSTEM: String get() = COACH_SYSTEM_VERBATIM + "\n\n" + HARD_RULES

    /** The window of stored messages: the last [HISTORY], cut to [MSG_CAP], first turn the owner's, roles alternating. */
    fun history(messages: List<CoachMessage>): List<Turn> {
        val window = messages.takeLast(HISTORY).dropWhile { it.who != Speaker.OWNER }
        val merged = mutableListOf<Pair<Speaker, String>>()
        window.forEach { m ->
            val text = m.text.take(MSG_CAP)
            val last = merged.lastOrNull()
            if (last != null && last.first == m.who) merged[merged.size - 1] = m.who to (last.second + "\n" + text).take(MSG_CAP)
            else merged += m.who to text
        }
        // The final facts turn is the owner's: the window must end on the coach.
        while (merged.isNotEmpty() && merged.last().first == Speaker.OWNER) merged.removeAt(merged.size - 1)
        return merged.map { (who, text) -> if (who == Speaker.OWNER) Turn.user(text) else Turn.assistant(text) }
    }

    data class Facts(
        val profile: CoachProfile,
        val today: LocalDate,
        val currentKg: Double,
        val currentAt: LocalDate?,
        val nextGoal: Double?,
        val todayMeals: List<Meal>,
        val eatenKcal: Int,
        val eatenProtein: Double,
        val pending: List<PendingFood>,
        val summaries: List<DailySummary>,
        val totals: Totals,
        val illness: Boolean,
        val lowIntake: Boolean,
        val already: String?,
    )

    private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    private fun md(s: String) = runCatching { md(LocalDate.parse(s)) }.getOrDefault(s)

    fun facts(f: Facts): String {
        val p = f.profile
        val cheat = f.today.dayOfWeek == p.cheatDay
        val sb = StringBuilder()
        sb.append("【資料：程式算好的，照用，不准自己算】\n")
        sb.append("檔案：身高 ${CoachRules.kg(p.heightCm)} 公分，起始 ${CoachRules.kg(p.startWeightKg)} 公斤，目前 ${CoachRules.kg(f.currentKg)} 公斤")
        f.currentAt?.let { sb.append("（${md(it)}）") }
        sb.append("，下一個目標 ").append(f.nextGoal?.let { "${CoachRules.kg(it)} 公斤" } ?: "重新評估")
        sb.append("。每日 ${p.quotaKcal} 大卡、蛋白質 ${p.proteinG} 克。放縱餐：星期${CoachRules.weekdayZh(p.cheatDay)}。\n")
        sb.append("今天 ${md(f.today)}（星期${CoachRules.weekdayZh(f.today.dayOfWeek)}，${if (cheat) "是放縱餐" else "不是放縱餐"}）：")
        if (f.todayMeals.isEmpty()) sb.append("還沒回報。") else {
            val items = f.todayMeals.flatMap { it.items }
            items.take(MEAL_LINES).forEach { i -> sb.append("${i.name} ${CoachReplies.portion(i.portion)} 份 ${i.kcal} 大卡（${CoachReplies.sourceShort(i)}）；") }
            if (items.size > MEAL_LINES) sb.append("另外 ${items.size - MEAL_LINES} 項；")
        }
        val remaining = CoachRules.remainingKcal(p.quotaKcal, f.eatenKcal)
        sb.append("合計 ${f.eatenKcal} 大卡、蛋白質 ${f.eatenProtein.toInt()} 克。")
        if (remaining >= 0) sb.append("剩 $remaining 大卡") else sb.append("超過 ${-remaining} 大卡")
        sb.append("、蛋白質還差 ${CoachRules.remainingProtein(p.proteinG, f.eatenProtein)} 克。")
        sb.append("最低還要吃 ${CoachRules.mustStillEat(f.eatenKcal)} 大卡。\n")
        sb.append("待確認：").append(if (f.pending.isEmpty()) "無" else f.pending.joinToString("、") { it.name }).append("\n")
        sb.append("最近七天：")
        if (f.summaries.isEmpty()) sb.append("無") else f.summaries.take(SUMMARY_DAYS).forEach { s ->
            sb.append(md(s.day)).append(" ")
            if (!s.reported) sb.append("沒回報；") else {
                sb.append("有回報 ${s.kcal} 大卡")
                if (s.overBy > 0) sb.append(" 超過 ${s.overBy}")
                sb.append(if (s.onPlan) " 照計畫；" else " 沒照計畫；")
            }
        }
        sb.append("\n累計：總共超過 ${f.totals.overKcal} 大卡，階段目標總共延後 ${f.totals.delayDays} 天。\n")
        sb.append("狀態：身體不適 ${if (f.illness) "是" else "否"}；連續吃不到 1200：${if (f.lowIntake) "是" else "否"}。\n")
        sb.append("程式已回覆：").append(f.already?.takeIf { it.isNotBlank() } ?: "無")
        return sb.toString().take(FACTS_CAP)
    }

    fun finalTurn(facts: String, ownerText: String): Turn = Turn.user(facts + "\n【我剛說】\n" + ownerText.take(OWNER_CAP))

    /** The parse call's turns: the same window, then the owner's sentence. */
    fun parseTurns(messages: List<CoachMessage>, ownerText: String): List<Turn> = history(messages) + Turn.user(ownerText.take(OWNER_CAP))
}

/** Drops any model sentence holding a number the code did not hand over (the model must not make numbers up). */
object NumberGuard {
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val UNIT_OK = Regex("^\\s*(分鐘|小時|點|秒|次|下|組)")
    private val SENTENCE = Regex("(?<=[。！？!?\\n])")
    const val MAX_SENTENCES = 4

    fun filter(modelText: String, facts: String, ownerText: String): String {
        val allowed = (NUMBER.findAll(facts).map { it.value } + NUMBER.findAll(ownerText).map { it.value }).toSet()
        val kept = SENTENCE.split(modelText.trim()).map { it.trim() }.filter { it.isNotEmpty() }.filter { s ->
            NUMBER.findAll(s).all { m -> m.value in allowed || UNIT_OK.containsMatchIn(s.substring(m.range.last + 1)) }
        }
        return kept.take(MAX_SENTENCES).joinToString("").trim()
    }
}
