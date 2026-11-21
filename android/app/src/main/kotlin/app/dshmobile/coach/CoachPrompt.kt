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
    const val PENDING_LINES = 5

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
    fun history(messages: List<CoachMessage>, keep: Int = HISTORY): List<Turn> {
        val window = messages.takeLast(keep).dropWhile { it.who != Speaker.OWNER }
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
        // First, so the cap below can never cut it off.
        sb.append("狀態：身體不適 ${if (f.illness) "是" else "否"}；連續吃不到 1200：${if (f.lowIntake) "是" else "否"}。\n")
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
        val pend = f.pending.takeLast(PENDING_LINES)
        sb.append("待確認：").append(if (pend.isEmpty()) "無" else pend.joinToString("、") { it.name })
        if (f.pending.size > PENDING_LINES) sb.append("，另外 ${f.pending.size - PENDING_LINES} 項")
        sb.append("\n")
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
        sb.append("程式已回覆：").append(f.already?.takeIf { it.isNotBlank() }?.take(600) ?: "無")
        return sb.toString().take(FACTS_CAP)
    }

    fun finalTurn(facts: String, ownerText: String): Turn = Turn.user(facts + "\n【我剛說】\n" + ownerText.take(OWNER_CAP))

    /** The parse call's turns: the same window, then the owner's sentence. */
    fun parseTurns(messages: List<CoachMessage>, ownerText: String, keep: Int = HISTORY): List<Turn> = history(messages, keep) + Turn.user(ownerText.take(OWNER_CAP))
}

/** Numbers the way the owner writes them: Arabic (79.2) or Chinese (七十九點二, 兩百五十). */
object CoachNumbers {
    val ARABIC = Regex("\\d+(?:\\.\\d+)?")
    private val CHINESE = Regex("[零〇一二兩三四五六七八九十百千萬]+(?:點[零〇一二兩三四五六七八九]+)?")
    private val DIGIT = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 七十九點二 → 79.2; null when [s] is not a Chinese number. */
    fun chinese(s: String): Double? {
        val parts = s.split('點')
        var total = 0L
        var section = 0L
        var num = 0L
        var any = false
        for (ch in parts[0]) {
            val d = DIGIT[ch]
            if (d != null) { num = d.toLong(); any = true; continue }
            when (ch) {
                '十' -> { section += (if (num == 0L) 1 else num) * 10; num = 0; any = true }
                '百' -> { section += num * 100; num = 0 }
                '千' -> { section += num * 1000; num = 0 }
                '萬' -> { total += (section + num) * 10000; section = 0; num = 0 }
                else -> return null
            }
        }
        if (!any) return null
        val whole = (total + section + num).toDouble()
        if (parts.size < 2) return whole
        val frac = parts[1].map { DIGIT[it] ?: return null }.joinToString("")
        return "${whole.toLong()}.$frac".toDouble()
    }

    /** Every number in [text], Arabic or Chinese. */
    fun all(text: String): List<Double> =
        ARABIC.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList() + CHINESE.findAll(text).mapNotNull { chinese(it.value) }.toList()

    /** [v] (from the model) only if the owner wrote that same number himself; null otherwise. */
    fun ownersOnly(v: Double?, ownerText: String): Double? = v?.takeIf { x -> all(ownerText).any { kotlin.math.abs(it - x) < 1e-6 } }
}

/**
 * The last word on a model sentence: it may not hold a number the code did not hand over (Arabic or Chinese), nor a Latin
 * letter (the owner reads only Traditional Chinese). Deadlines and reps (「10 分鐘」「3 組」) may carry their own numbers.
 */
object NumberGuard {
    private val NUMBER = CoachNumbers.ARABIC
    private val UNIT_OK = Regex("^\\s*(分鐘|小時|點|秒|次|下|組)")
    private val CHINESE_AMOUNT = Regex("[零〇一二兩三四五六七八九十百千萬半]+\\s*(大卡|卡|公斤|斤|克|天|餐|份)")
    private val LATIN = Regex("[A-Za-z]")
    private val SENTENCE = Regex("(?<=[。！？!?\\n])")
    const val MAX_SENTENCES = 4

    fun filter(modelText: String, facts: String, ownerText: String): String {
        val allowed = (NUMBER.findAll(facts).map { it.value } + NUMBER.findAll(ownerText).map { it.value }).toSet()
        val text = modelText.trim().replace("BMI", "身體質量指數")
        val kept = SENTENCE.split(text).map { it.trim() }.filter { it.isNotEmpty() }.filter { s ->
            !LATIN.containsMatchIn(s) &&
                !CHINESE_AMOUNT.containsMatchIn(s) &&
                NUMBER.findAll(s).all { m -> m.value in allowed || UNIT_OK.containsMatchIn(s.substring(m.range.last + 1)) }
        }
        return kept.take(MAX_SENTENCES).joinToString("").trim()
    }
}
