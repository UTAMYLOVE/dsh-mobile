package app.agency.core.agent

/** What the guard must know besides the text: see [ContextSource] for where each comes from. */
data class GuardFlags(
    val unwellReducedByCode: Boolean = false,
    val closedFeatureWords: List<String> = emptyList(),
    val neverBelowKg: Double = 0.0,
) {
    companion object {
        fun of(c: ContextSource) = GuardFlags(c.unwellReducedByCode, c.closedFeatureWords, c.neverBelowKg)
    }
}

/** One sentence that broke a rule. [reason] is written to 小葵 (Traditional Chinese), so she can rewrite it. */
data class Problem(val sentence: String, val reason: String)

sealed interface GuardResult {
    data object Ok : GuardResult

    data class Fix(val problems: List<Problem>) : GuardResult
}

/**
 * The code check on 小葵's final text (SPEC D "Guard"). It never rewrites her words: it names what is wrong so she can rewrite
 * ([check]), and as a last resort removes only the offending sentences ([strip]).
 *
 * It is deliberately conservative, because a false alarm makes her sound broken:
 * - Numbers: only Arabic digits (half or full width, decimals, thousands commas) directly followed by one of [UNITS], and
 *   `H:MM` times, are checked. A number passes when the same value appears anywhere in the facts or in his own message,
 *   compared numerically (`1,200` = `1200`, `7:00` = `07:00`); for a range like 「300–400 大卡」 both ends must pass. Plain counts
 *   without a unit and numbers written in Chinese numerals are not checked.
 * - Advice: small regex families for skipping a meal, making it up tomorrow, granting a lighter training herself, shaming
 *   words, and pushing his weight under the floor. A match right after a negation (「不要跳餐」) is not a problem.
 * - Links and named videos or places (in 《》, or in 「」 next to a video/place word) must appear in the facts.
 * - A closed feature's word together with an instruction to do it, unless the sentence says it starts later.
 */
object Guard {
    /** Units after which a number is a claim about him. Longest first so 「分鐘」 is not read as 「分」. */
    val UNITS = listOf(
        "大卡", "公克", "公斤", "公分", "毫升", "分鐘", "卡", "克", "元", "秒", "壺", "%", "％", "輪", "下", "次", "天", "週", "支",
        "題", "分",
    )

    private const val NUM = """[0-9０-９]+(?:[,，][0-9０-９]{3})*(?:[.．][0-9０-９]+)?"""
    private val unitAlt = UNITS.joinToString("|") { Regex.escape(it) }
    private val numberWithUnit = Regex(
        """(?<![0-9０-９A-Za-z.．:：])($NUM)(?:\s*(?:-|–|—|~|～|〜|至|到)\s*($NUM))?\s*($unitAlt)""",
    )
    private val anyNumber = Regex(NUM)
    private val time = Regex("""(?<![0-9０-９])([0-9０-９]{1,2})[:：]([0-5０-５][0-9０-９])(?![0-9０-９])""")
    private val url = Regex("""(?i)(?:https?://|www\.)[^\s，。、！？）)」』\]]+""")
    private val bookTitle = Regex("""《([^》]{2,40})》""")
    private val cornerQuote = Regex("""「([^」]{2,40})」""")
    private val videoOrPlaceWord = Regex("""影片|頻道|教學影片|餐廳|店家|小吃店|這家|那家|這間|那間""")

    /** What may follow a unit character that makes it something else (「下午」, 「三分之一」, 「下面」). */
    private fun notReallyAUnit(unit: String, next: Char?): Boolean = when (unit) {
        "分" -> next == '之'
        "下" -> next != null && next in "午面來去週個禮"
        else -> false
    }

    // Negations that, right before a match (within the same clause), turn a forbidden phrase into the opposite advice.
    private val mealNegation = listOf(
        "不要", "不能", "不可以", "不准", "不用", "千萬別", "別", "絕對不", "不會", "沒有", "不該", "不必", "避免", "禁止", "不是",
    )
    private val trainingNegation = listOf("不能", "不可以", "不會", "沒有", "不准", "別想", "不是", "不行", "沒辦法")
    private val shameNegation = listOf("不", "沒", "一點也不", "並不", "沒什麼好", "不用覺得", "不會")

    // Negations inside a match (「明天不用少吃」, 「核心不能跳過」): the match then says the opposite.
    private val innerMealNegation = Regex("""不用|不要|不必|不需要|沒必要|別""")
    private val innerTrainingNegation = Regex("""不能|不可以|不准|不會|不行|別想|沒有""")

    private const val MEALS = "早餐|午餐|晚餐|宵夜|消夜|這餐|下一餐|那餐|第一餐|一餐|正餐"
    private val skipMeal = listOf(
        Regex("""跳(?:過|掉)?(?:一|這|那|下一|今天的|早|午|晚)?餐|跳餐|斷食|空腹一整天|餓(?:一|個)?餐|餓肚子|省(?:掉|下)?(?:一|這)餐|少吃一餐"""),
        Regex("""不吃(?:$MEALS)"""),
        Regex("""(?:$MEALS)(?:就|先|乾脆)?(?:不要|別|不用|免|先別)吃(?:了|吧|啦|囉)*(?=$|[。，！？、,.!?\s～~])"""),
        Regex("""(?:$MEALS)(?:就|先|乾脆)?(?:免了|不用了|省了|省掉|跳過)"""),
    )
    private val tomorrowMakeUp = listOf(
        Regex("""(?:明天|明日|隔天|改天|之後)[^。！？\n]{0,8}?(?:少吃|吃少|扣回|扣掉|補回|彌補|抵[銷消])"""),
        Regex("""(?:明天|明日|隔天|改天)[^。！？\n]{0,6}?(?:吃|份量|分量|熱量|餐)[^。！？\n]{0,4}?(?:少一點|少一些|減少|減半)"""),
    )
    private const val TRAIN = "練|訓練|做訓練|做核心|做肌力|做伸展|做體能|運動|伸展|收操|核心|肌力|體能"
    private val lighterTraining = listOf(
        Regex("""(?:不用|不必|免|可以不用?|先不要?|就別|別)(?:$TRAIN)(?:了|吧|啦|囉|一天|也沒關係|沒關係)?(?=$|[。，！？、,.!?\s～~])"""),
        Regex("""(?:訓練|核心|肌力|運動|體能|伸展)量?[^。！？\n]{0,4}?(?:減半|減量|少做|少一輪|少兩輪|取消|暫停|放假|跳過|免了|停一天|休息一天)"""),
        Regex("""少做(?:一|兩|幾|[0-9０-９]+)?\s*(?:輪|組|下)"""),
        Regex("""(?:放|給)(?:你|自己)一天假|准你(?:休息|不練)"""),
    )
    private val shaming = listOf(
        Regex("""你(?:真的|真|好|很|太|就是|根本|有夠|超|也太)*(?:懶(?!得)|廢|沒用|沒救|可悲|丟臉|噁心|胖|肥|蠢|笨)"""),
        Regex("""廢物|胖子|肥豬|死胖|豬頭|沒出息|不要臉|可恥|活該|失敗者|白癡|白痴|笨蛋|丟臉"""),
    )
    private val lossPush = Regex("""瘦到|減到|降到|掉到|目標|再瘦|再減|衝到|達到|到達""")
    private val kg = Regex("""($NUM)\s*(?:公斤|kg|KG)""")
    private val instruction = Regex(
        """記得|快去|趕快|現在就|現在去|開始|先去|去做|去量|去喝|去拍|去練|來做|來量|來練|應該|該做|該去|請你|你要|要不要|別忘了|順便|試著|可以先""",
    )
    private val later = Regex("""之後|以後|後面|下一?個?階段|還沒開|還沒開始|還沒輪到|才開始|才會|開放後|到時候|等.{0,6}開""")

    fun check(reply: String, facts: String, ownerText: String, flags: GuardFlags = GuardFlags()): GuardResult {
        val known = values(facts) + values(ownerText)
        val knownTimes = times(facts) + times(ownerText)
        val problems = ArrayList<Problem>()
        for (s in sentences(reply).map { it.trim() }.filter { it.isNotEmpty() }) {
            reasons(s, facts, ownerText, known, knownTimes, flags).firstOrNull()?.let { problems += Problem(s, it) }
        }
        return if (problems.isEmpty()) GuardResult.Ok else GuardResult.Fix(problems)
    }

    /** [reply] without the sentences named in [problems]; the rest is kept exactly as she wrote it. */
    fun strip(reply: String, problems: List<Problem>): String {
        val bad = problems.map { it.sentence }.toSet()
        val out = StringBuilder()
        var dropNewline = false
        for (piece in sentences(reply)) {
            if (piece == "\n" && dropNewline) { dropNewline = false; continue }
            dropNewline = false
            if (piece.trim() in bad) {
                // A removed sentence that filled its whole line takes its line break with it.
                dropNewline = out.isEmpty() || out.endsWith("\n")
                continue
            }
            out.append(piece)
        }
        return out.toString().replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun reasons(
        s: String,
        facts: String,
        owner: String,
        known: Set<Double>,
        knownTimes: Set<Int>,
        flags: GuardFlags,
    ): List<String> = buildList {
        for (m in numberWithUnit.findAll(s)) {
            val unit = m.groupValues[3]
            if (notReallyAUnit(unit, s.getOrNull(m.range.last + 1))) continue
            val ends = listOfNotNull(m.groupValues[1], m.groupValues[2].takeIf { it.isNotEmpty() })
            val missing = ends.firstOrNull { e -> value(e)?.let { v -> known.none { same(it, v) } } == true }
            if (missing != null) add("「${m.value.trim()}」這個數字不在這次的情境或工具結果裡。關於他的數字只能照工具或情境給的說；需要的話先用工具查，或不要提數字。")
        }
        for (m in time.findAll(s)) {
            val minutes = minutes(m) ?: continue
            if (minutes !in knownTimes) add("「${m.value}」這個時間不在這次的情境或工具結果裡。時間只能照情境或工具給的說。")
        }
        if (anyMatch(skipMeal, s, mealNegation)) add("不能叫他跳過一餐、不吃或斷食。可以說今天的下一餐小一點。")
        if (anyMatch(tomorrowMakeUp, s, mealNegation, innerMealNegation)) {
            add("不能叫他明天少吃來補。吃多了就照實說對身體和身形的影響，只在今天之內調整。")
        }
        if (!flags.unwellReducedByCode && anyMatch(lighterTraining, s, trainingNegation, innerTrainingNegation)) {
            add("訓練減量或停練只能由程式依生病或受傷決定，你不能自己准他少練或不練；累、痠、想睡都照原本的計畫。")
        }
        if (anyMatch(shaming, s, shameNegation)) add("不要用羞辱或貶低他的字眼。說事實和下一步就好。")
        if (flags.neverBelowKg > 0 && lossPush.containsMatchIn(s) &&
            kg.findAll(s).any { k -> value(k.groupValues[1])?.let { it < flags.neverBelowKg } == true }
        ) add("不能鼓勵他的體重往更低的方向走，那已經低於公司訂的下限。")
        for (m in url.findAll(s)) {
            if (!facts.contains(m.value.trimEnd('.', ',', '。'))) add("連結只能來自搜尋工具的結果，不要自己寫網址；卡片上會有連結。")
        }
        val named = bookTitle.findAll(s).map { it.groupValues[1] } +
            (if (videoOrPlaceWord.containsMatchIn(s)) cornerQuote.findAll(s).map { it.groupValues[1] } else emptySequence())
        for (n in named) {
            if (!containsLoosely(facts, n) && !containsLoosely(owner, n)) add("「$n」不在這次的搜尋結果裡。地點和影片只能說搜尋工具真的找到的。")
        }
        for (w in flags.closedFeatureWords.filter { it.isNotBlank() }) {
            if (s.contains(w) && instruction.containsMatchIn(s) && !later.containsMatchIn(s)) {
                add("「$w」還沒開放，不能叫他做或提醒他做；他自己回報的話就記下來，並說這項之後才開始。")
            }
        }
    }

    /**
     * True when some match of [family] in [s] is not negated: neither by a word of [negations] in the few characters before it
     * (within the same clause; a one-character negation must touch the match), nor by [inner] inside the match itself.
     */
    private fun anyMatch(family: List<Regex>, s: String, negations: List<String>, inner: Regex? = null): Boolean =
        family.any { r ->
        r.findAll(s).any { m ->
            val window = s.substring(maxOf(0, m.range.first - NEGATION_WINDOW), m.range.first)
            val before = window.substring(window.indexOfLast { it in CLAUSE_BREAKS } + 1)
            val negated = negations.any { before.endsWith(it) || (it.length > 1 && before.contains(it)) }
            !negated && (inner == null || !inner.containsMatchIn(m.value))
        }
    }

    private const val NEGATION_WINDOW = 6
    private const val CLAUSE_BREAKS = "，,、：:「」"

    /** The text cut into sentences; joining them gives the text back exactly. */
    internal fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            cur.append(c)
            if (c == '\n') {
                out += cur.toString(); cur.clear()
            } else if (c in ENDS) {
                while (i + 1 < text.length && (text[i + 1] in ENDS || text[i + 1] in CLOSERS)) { i++; cur.append(text[i]) }
                out += cur.toString(); cur.clear()
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private const val ENDS = "。！？!?；;"
    private const val CLOSERS = "」』）)～~"

    private fun normalise(s: String): String = buildString {
        s.forEach { c ->
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '．' -> '.'
                    '，', ',' -> return@forEach
                    else -> c
                },
            )
        }
    }

    private fun value(raw: String): Double? = normalise(raw).toDoubleOrNull()

    private fun values(text: String): Set<Double> = anyNumber.findAll(text).mapNotNull { value(it.value) }.toSet()

    private fun minutes(m: MatchResult): Int? {
        val h = normalise(m.groupValues[1]).toIntOrNull() ?: return null
        val mi = normalise(m.groupValues[2]).toIntOrNull() ?: return null
        if (h > 29 || mi > 59) return null
        return (h % 24) * 60 + mi
    }

    private fun times(text: String): Set<Int> = time.findAll(text).mapNotNull { minutes(it) }.toSet()

    private fun same(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6

    private fun containsLoosely(hay: String, needle: String): Boolean {
        val h = hay.replace(Regex("""\s"""), "")
        val n = needle.replace(Regex("""\s"""), "")
        return n.isEmpty() || h.contains(n)
    }
}
