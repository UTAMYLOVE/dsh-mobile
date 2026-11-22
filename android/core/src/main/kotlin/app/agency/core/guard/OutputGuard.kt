package app.agency.core.guard

/** What kind of sentence is checked: a reply, a 討論 reply (exactly one question), or a proactive line (≤ 2 sentences). */
enum class GuardMode { NORMAL, DISCUSS, NUDGE }

/**
 * Why a sentence failed. The first four drop just that sentence; the last three ([hard]) make the caller retry with the
 * reasons and then use its template.
 */
enum class GuardRule(val hard: Boolean) {
    NUMBER(false), LINK(false), NAME(false), LATIN(false), BANNED(true), MODE(true), PROMISE(true)
}

/** One finding: [detail] is the offending piece (a number, a name, a phrase) or the mode rule broken, said to the model. */
data class GuardViolation(val rule: GuardRule, val detail: String)

/** [kept] is the text with the dropped sentences taken out. */
data class GuardResult(val kept: String, val violations: List<GuardViolation>) {
    val hard: List<GuardViolation> get() = violations.filter { it.rule.hard }

    /** Nothing useful left, or a banned phrase, a mode rule or a false promise: ask again, then the template. */
    val needsRetry: Boolean get() = kept.isBlank() || hard.isNotEmpty()
}

/**
 * What a model sentence may rely on, from this turn only: [texts] are the facts, the tool results and his message (their
 * numbers and words are evidence); [names] are the food-table names and aliases, search-result titles and favourite places;
 * [latin] adds Latin words he gave himself (idol names, song titles) to the fixed whitelist; [acceptedProposal] is true only
 * when a proposal was ACCEPTED in this turn.
 */
class Evidence(
    texts: List<String>,
    names: Collection<String> = emptyList(),
    latin: Collection<String> = emptyList(),
    val acceptedProposal: Boolean = false,
) {
    /** Ids and keys (any token mixing letters and digits, a UUID) are not numbers: their digits never count as evidence. */
    private val idLike = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|(?=[\\w-]*[A-Za-z])(?=[\\w-]*\\d)[\\w-]+")

    val texts: List<String> = texts + names
    val names: List<String> = names.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }
    val latin: List<String> = (OutputGuard.LATIN_WHITELIST + latin).map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    private val numbers: List<Double> = this.texts.flatMap { CoachNumbers.all(idLike.replace(it, " ")) }

    fun hasNumber(v: Double): Boolean = numbers.any { kotlin.math.abs(it - v) < 1e-6 }

    /** [word] appears in any evidence text or name. */
    fun mentions(word: String): Boolean = texts.any { it.contains(word) }

    companion object {
        val NONE = Evidence(emptyList())
    }
}

/**
 * The last word on every model sentence (plan v3 §1.9): numbers only from the evidence, no links, no shop or chain name that
 * this turn did not show, no Latin letters beyond the whitelist, none of the banned phrases or attachment patterns, the mode's
 * sentence and question counts, and no claim that a plan changed without an accepted proposal. Code lines (numbers, the four
 * over-quota lines) never pass through here: only model text does.
 */
object OutputGuard {
    /** His idol's group and the songs he named, and the level letters (C, B, A are matched as single capitals below). */
    val LATIN_WHITELIST = listOf("BABYMONSTER", "DESERVE", "On My Way")
    const val MAX_SENTENCES = 4
    const val MAX_NUDGE_SENTENCES = 2

    private val SENTENCE = Regex("(?<=[。！？!?\\n])")
    private val ARABIC = CoachNumbers.ARABIC

    /** Deadlines and reps carry their own numbers (「10 分鐘」「3 組」「三輪」). */
    private val UNIT_OK = Regex("^\\s*(分鐘|小時|點|秒|次|下|組|輪)")

    /** A Chinese numeral that is an amount: followed by a measure or a count. */
    private const val MEASURE = "大卡|塊錢|個月|公斤|公克|公分|毫升|公里|卡|斤|克|週|年|歲|元"
    private const val COUNT = "份|餐|天|碗|杯|瓶|盒|包|顆|片|壺|個|塊|條|口"
    private val CHINESE_AMOUNT = Regex("([零〇一二兩三四五六七八九十百千萬]+|半)\\s*($MEASURE|$COUNT)")
    private val COUNT_ONLY = Regex("^($COUNT)$")

    /** Everyday words, not amounts: 「三餐正常吃」「一天」「一份」 never hide an invented number. */
    private val PLAIN_WORDS = Regex("(?<![零〇一二兩三四五六七八九十百千萬半])(三餐|一天|一份|一餐|一口|一下|一些|一半)")

    private val LINK = Regex("(?i)(https?://|www\\.)\\S*|[A-Za-z0-9-]+\\.(com|tw|net|org|io|app|me|co|cc|ly|gl|xyz|info)\\b")
    private val LATIN = Regex("[A-Za-zＡ-Ｚａ-ｚ]")
    private val LEVEL_LETTER = Regex("(?<![A-Za-z])[CBA](?![A-Za-z])")

    private val SHOP_SUFFIX = Regex("餐廳|小吃|便當|麵館|食堂|店|館|屋|坊|堂")
    private val HAN = Regex("\\p{IsHan}")
    private const val LEADING_FILLER = "去在到買吃找那這要先再就跟和或也還來點叫吧"
    private const val GENERIC_END = "的家間個些"

    /**
     * Place words that are not a shop's name (taken out before the suffix rule). A bare suffix word (「餐廳」「便當」) needs no
     * entry: with nothing but a filler word before it there is no name to check.
     */
    private val GENERIC_PLACES = listOf(
        "便利商店", "超商", "店家", "店員", "店裡", "早餐店", "便當店", "自助餐店", "小吃店", "麵店", "飲料店", "藥局",
        "學生餐廳", "員工餐廳", "學校餐廳", "圖書館", "體育館", "教堂", "課堂", "天堂", "禮堂", "澡堂", "旅館", "飯店", "書店",
        "屋子", "屋裡", "房屋",
    )

    /** Chains and brands: named only when this turn's evidence names them. */
    private val CHAINS = listOf(
        "全家", "萊爾富", "麥當勞", "肯德基", "摩斯", "漢堡王", "星巴克", "路易莎", "八方雲集", "四海遊龍", "鬍鬚張", "爭鮮",
        "吉野家", "頂呱呱", "拿坡里", "必勝客", "達美樂", "迷客夏", "五十嵐", "清心", "可不可", "大苑子", "茶湯會", "春水堂",
        "鼎泰豐", "丹丹", "三商巧福", "美而美", "弘爺", "拉亞", "麥味登", "胖老爹", "繼光香香雞", "家樂福", "全聯", "好市多",
    )

    private fun phrases(vararg p: String) = Regex(p.joinToString("|"))

    /** 1.7's forbidden list: attachment, guilt, comfort that excuses a slip, insults, starving or making up, pressure, diagnosis. */
    private val BANNED: List<Pair<String, Regex>> = listOf(
        "依附或情緒勒索" to phrases(
            "不要走", "別走", "不准走", "再陪我", "多陪我", "陪陪我", "你不在我會", "我會難過", "我會很難過", "我會傷心", "我會想你",
            "好想你", "只有我懂你", "只有我最懂你", "只有我了解你", "只有我在乎你", "好感度", "好感(?![動冒])", "你不理我", "都不理我", "冷落我",
            "離不開我", "你是我的(?!練習生)", "不准離開", "讓我失望", "讓我很失望", "對得起我", "都是你害的", "丟下我",
        ),
        "替違規找台階的安慰" to phrases("沒關係", "沒差", "偶爾一次", "已經很棒了", "不用在意", "別在意", "吃了就吃了", "放縱一下", "一次而已"),
        "罵人" to phrases("廢物", "沒用(?![到完過])", "胖子", "肥豬", "死胖", "肥宅", "懶鬼", "懶惰鬼", "笨蛋", "白癡", "魯蛇", "沒救了", "丟臉"),
        "叫他少吃、跳餐或斷食" to phrases(
            "少吃", "吃少一點", "跳餐", "跳過.{0,3}餐", "餐.{0,3}跳過", "不吃[早午晚]餐", "不要吃[早午晚]餐", "不要吃宵夜",
            "斷食", "禁食", "空腹",
            "餓一下", "餓肚子", "餓著", "省一餐", "少一餐",
        ),
        "叫他用運動補回來" to phrases(
            "補回來", "補回去", "抵銷", "抵消", "抵掉", "消耗掉", "燃燒掉", "燒掉", "加倍運動", "運動補", "加練",
            "多跑.{0,6}(補|抵|消耗|燒)", "多練.{0,6}(補|抵|消耗|燒)", "多做.{0,6}(補|抵|消耗|燒)", "罰.{0,4}(跑|做|練)",
        ),
        "給體重速度或唱歌高度壓力" to phrases("瘦得?太慢", "減得?太慢", "掉得?太慢", "怎麼還沒瘦", "還這麼胖", "還是這麼胖", "唱不上去", "高音.{0,4}(不行|太爛|太差)"),
        "下醫療診斷" to phrases("診斷", "你得了", "罹患", "(應該|可能|大概|八成|一定|就)是\\p{IsHan}{0,6}(症|炎|病)"),
    )

    private val PROMISE = phrases(
        "我已經(幫你)?(改|換|調|排|加|刪|移|設|更新)", "已經幫你(改|換|調|排|加|刪|移|設|更新)", "幫你改好了",
        "(計畫|行程|菜單|課表|時間|作息)已經(改|換|調|更新)", "改好了",
    )

    fun sentences(text: String): List<String> = SENTENCE.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

    /** Questions in [text]: question marks, plus a sentence that ends in 嗎 or 呢 without one. */
    fun questions(text: String): Int = sentences(text).sumOf { s ->
        val marks = s.count { it == '？' || it == '?' }
        if (marks > 0) marks else if (Regex("[嗎呢][。！!]?$").containsMatchIn(s)) 1 else 0
    }

    fun check(text: String, evidence: Evidence, mode: GuardMode): GuardResult {
        val all = sentences(text.replace("BMI", "身體質量指數"))
        val violations = mutableListOf<GuardViolation>()
        val kept = all.filter { s ->
            val dropped = sentenceIssues(s, evidence)
            violations += dropped
            BANNED.forEach { (label, re) -> re.find(s)?.let { violations += GuardViolation(GuardRule.BANNED, "$label：「${it.value}」") } }
            if (!evidence.acceptedProposal) PROMISE.find(s)?.let { violations += GuardViolation(GuardRule.PROMISE, "「${it.value}」：計畫沒有改，他還沒確認") }
            dropped.isEmpty()
        }
        val cap = if (mode == GuardMode.NUDGE) MAX_NUDGE_SENTENCES else MAX_SENTENCES
        if (all.size > cap) violations += GuardViolation(GuardRule.MODE, "最多 $cap 句，現在 ${all.size} 句")
        if (mode == GuardMode.DISCUSS) {
            val q = questions(text)
            if (q != 1) violations += GuardViolation(GuardRule.MODE, "討論時只能問一個問題，現在 $q 個")
        }
        return GuardResult(kept.joinToString(""), violations)
    }

    /** The sentence-level rules (1-4 of §1.9); any finding drops the sentence. */
    private fun sentenceIssues(s: String, ev: Evidence): List<GuardViolation> {
        val out = mutableListOf<GuardViolation>()
        numberIssues(s, ev).forEach { out += GuardViolation(GuardRule.NUMBER, "數字「$it」不在資料裡") }
        LINK.find(s)?.let { out += GuardViolation(GuardRule.LINK, "不准寫網址或網域") }
        unknownNames(s, ev).forEach { out += GuardViolation(GuardRule.NAME, "「$it」不在這一輪的搜尋結果、常去店家或食物表裡") }
        if (out.none { it.rule == GuardRule.LINK } && latinLeft(s, ev)) out += GuardViolation(GuardRule.LATIN, "不准用英文字母")
        return out
    }

    private fun numberIssues(s: String, ev: Evidence): List<String> {
        val plain = PLAIN_WORDS.replace(s, "　")
        val bad = mutableListOf<String>()
        ARABIC.findAll(plain).forEach { m ->
            if (UNIT_OK.containsMatchIn(plain.substring(m.range.last + 1))) return@forEach
            val v = m.value.toDoubleOrNull() ?: return@forEach
            if (!ev.hasNumber(v)) bad += m.value
        }
        CHINESE_AMOUNT.findAll(plain).forEach { m ->
            val n = m.groupValues[1]
            // 「一包」「半碗」 are the article, not a figure; 「一公斤」「兩百卡」 are figures.
            if ((n == "一" || n == "半") && COUNT_ONLY.matches(m.groupValues[2])) return@forEach
            val v = if (n == "半") 0.5 else CoachNumbers.chinese(n) ?: return@forEach
            if (!ev.hasNumber(v)) bad += m.value
        }
        return bad
    }

    /** Shop-like names (a shop suffix after a name) and chain names that no evidence of this turn contains. */
    private fun unknownNames(sentence: String, ev: Evidence): List<String> {
        var s = sentence
        ev.names.forEach { s = s.replace(it, "　") }
        val unknown = CHAINS.filter { s.contains(it) && !ev.mentions(it) }.toMutableList()
        CHAINS.forEach { s = s.replace(it, "　") }
        GENERIC_PLACES.sortedByDescending { it.length }.forEach { s = s.replace(it, "　") }
        SHOP_SUFFIX.findAll(s).forEach { m ->
            var from = m.range.first
            while (from > 0 && HAN.matches(s[from - 1].toString()) && m.range.first - from < 8) from--
            val prefix = s.substring(from, m.range.first)
            if (prefix.isEmpty() || prefix.last() in GENERIC_END) return@forEach
            val name = prefix.trimStart { it in LEADING_FILLER }
            if (name.length < 2) return@forEach
            // The scan may take in words before the name (「附近阿明牛肉麵館」); any tail of two or more characters that the
            // evidence shows with the same suffix counts as known.
            val known = (2..name.length).any { k -> ev.mentions(name.takeLast(k) + m.value) }
            if (!known) unknown += name + m.value
        }
        return unknown
    }

    private fun latinLeft(s: String, ev: Evidence): Boolean {
        var t = s
        ev.latin.forEach { t = t.replace(it, " ", ignoreCase = true) }
        t = LEVEL_LETTER.replace(t, " ")
        return LATIN.containsMatchIn(t)
    }

    /**
     * The old coach's filter (CoachEngine, until T09 deletes it): the sentence rules only, against its facts and his message,
     * then at most [MAX_SENTENCES] sentences.
     */
    fun legacyFilter(modelText: String, facts: String, ownerText: String): String {
        val ev = Evidence(listOf(facts, ownerText))
        return sentences(modelText.replace("BMI", "身體質量指數")).filter { sentenceIssues(it, ev).isEmpty() }
            .take(MAX_SENTENCES).joinToString("").trim()
    }
}
