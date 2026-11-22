package app.agency.core.guard

import app.agency.core.engine.Tone
import app.agency.core.engine.ToneDecider
import app.agency.core.rules.FoodTable

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
 * What a model sentence may rely on, from this turn only.
 * - [texts]: the facts and the tool results. Their dates (`2026-11-20`, `11/20`, `11月20日`) and clock times (`12:10`,
 *   `晚上九點`) are taken out before numbers are collected and kept as date and time tokens, so 20 or 12 never becomes
 *   evidence for a gram or a kcal figure; ids and keys are taken out too. The values of `left` and `floor_left` are also
 *   kept apart: a figure said to be what is left (「還剩」「還差」) must be one of them.
 * - [ownerText]: his message of this turn. Everything in it counts, dates included: they are his own words.
 * - [names]: food-table names and aliases, search-result titles, favourite places.
 * - [latin]: Latin words he gave himself (idol names, song titles), added to the fixed whitelist.
 * - [acceptedProposal]: a proposal was ACCEPTED in this turn. [breachToday]: today's records hold a breach.
 */
class Evidence(
    texts: List<String>,
    names: Collection<String> = emptyList(),
    latin: Collection<String> = emptyList(),
    val acceptedProposal: Boolean = false,
    ownerText: String = "",
    val breachToday: Boolean = false,
) {
    val names: List<String> = names.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }
    val latin: List<String> = (OutputGuard.LATIN_WHITELIST + latin).map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    private val words: List<String> = texts + names + ownerText
    private val days = mutableSetOf<String>()
    private val times = mutableSetOf<String>()
    private val numbers: List<Double>
    private val lefts: List<Double>

    init {
        val data = (texts + names).map { t ->
            var s = ISO_DAY.replace(t) { m -> days += OutputGuard.dayToken(m.groupValues[2], m.groupValues[3]); " " }
            s = OutputGuard.takeDays(s, days)
            s = OutputGuard.takeTimes(s, times)
            ID_LIKE.replace(s, " ")
        }
        OutputGuard.takeTimes(OutputGuard.takeDays(ownerText, days), times)
        numbers = (data + ownerText).flatMap { CoachNumbers.all(it) }
        lefts = texts.flatMap { t -> LEFT.findAll(t).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList() }
    }

    fun hasNumber(v: Double): Boolean = numbers.any { kotlin.math.abs(it - v) < 1e-6 }
    fun hasDay(token: String): Boolean = token in days
    fun hasTime(token: String): Boolean = token in times

    /** Null when the facts carry no `left` field (then any evidence number will do), else whether [v] is one of them. */
    fun isLeft(v: Double): Boolean? = if (lefts.isEmpty()) null else lefts.any { kotlin.math.abs(it - v) < 1e-6 }

    /** [word] appears in any evidence text, name or his message. */
    fun mentions(word: String): Boolean = words.any { it.contains(word) }

    companion object {
        val NONE = Evidence(emptyList())
        private val ISO_DAY = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
        private val LEFT = Regex("\"(?:left|floor_left)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)")

        /** Ids and keys (a UUID, any token mixing letters and digits) are not numbers. */
        private val ID_LIKE = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|(?=[\\w-]*[A-Za-z])(?=[\\w-]*\\d)[\\w-]+")
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
    private const val D = "[0-9０-９]"
    private const val ZH = "[零〇一二兩三四五六七八九十]"

    /** A clock time (「21:00」) and a calendar date (「11/20」「11月20日」「十二月五號」): one token each, matched as a whole. */
    val TIME = Regex("(?<!$D)($D{1,2})[:：]($D{2})(?!$D)")
    val DATE = Regex("(?<!$D)($D{1,2})\\s*[/／月]\\s*($D{1,2})(?!$D)\\s*[日號]?")
    private val ZH_DATE = Regex("($ZH{1,3})月($ZH{1,3})[日號]")

    /** 「晚上九點」「21 點半」「十點十五分」: an hour (and minutes) with 點. Without a part of the day it may be either half. */
    private val CLOCK = Regex("(早上|上午|中午|下午|傍晚|晚上|今晚|半夜|凌晨)?($D{1,2}|$ZH{1,3})\\s*點(半|$D{1,2}\\s*分|$ZH{1,3}分)?")

    fun timeToken(h: String, m: String) = "${CoachNumbers.arabic(h)!!.toInt()}:${CoachNumbers.arabic(m)!!.toInt()}"
    fun dayToken(mo: String, d: String) = "${CoachNumbers.arabic(mo)!!.toInt()}/${CoachNumbers.arabic(d)!!.toInt()}"

    private fun num(s: String): Int? = (CoachNumbers.arabic(s) ?: CoachNumbers.chinese(s))?.toInt()

    /**
     * The time tokens a 點 phrase may mean, or null when it is no clock time: a bare 「一點」 is "a little" (「放軟一點」), and
     * any bare 「X點」 right after 早/晚/快/慢/多/少 is a comparison (「早兩點」 is rare; 「晚一點」 is common).
     */
    private fun clockTokens(m: MatchResult, before: Char?): List<String>? {
        val part = m.groupValues[1]
        val hourText = m.groupValues[2]
        val tail = m.groupValues[3]
        if (part.isEmpty() && tail.isEmpty() && (hourText == "一" || before != null && before in "早晚快慢多少好有差")) return null
        val h = num(hourText) ?: return null
        if (h > 24) return null
        val min = when {
            tail.isEmpty() -> 0
            tail == "半" -> 30
            else -> num(tail.removeSuffix("分").trim()) ?: return null
        }
        val hours = when (part) {
            "下午", "傍晚", "晚上", "今晚" -> listOf(if (h < 12) h + 12 else h)
            "中午" -> listOf(if (h < 6) h + 12 else h)
            "早上", "上午", "半夜", "凌晨" -> listOf(h)
            else -> if (h < 12) listOf(h, h + 12) else listOf(h)
        }
        return hours.map { "$it:$min" }
    }

    /** [s] with its clock times taken out, each added to [into]. */
    fun takeTimes(s: String, into: MutableSet<String>): String {
        val t = TIME.replace(s) { m -> into += timeToken(m.groupValues[1], m.groupValues[2]); " " }
        return CLOCK.replace(t) { m ->
            val tokens = clockTokens(m, t.getOrNull(m.range.first - 1)) ?: return@replace m.value
            into += tokens; " "
        }
    }

    /** [s] with its calendar dates taken out, each added to [into]. */
    fun takeDays(s: String, into: MutableSet<String>): String {
        val t = DATE.replace(s) { m -> into += dayToken(m.groupValues[1], m.groupValues[2]); " " }
        return ZH_DATE.replace(t) { m ->
            val mo = num(m.groupValues[1]); val d = num(m.groupValues[2])
            if (mo != null && d != null) into += "$mo/$d"
            " "
        }
    }

    /** Deadlines and reps carry their own numbers (「10 分鐘」「3 組」「三輪」「十幾分鐘」). 點 is a clock time, checked above. */
    private val UNIT_OK = Regex("^(多|來|幾)?\\s*(分鐘|小時|秒|次|下|組|輪)")

    /** 次 is checked when the rules count it: 「兩次休假餐」「三次零卡」. */
    private val RULE_COUNT_AFTER = Regex("^\\s*次.{0,2}(休假餐|零卡)")

    /** Units that make a figure an amount; an approximation word may stand between (「兩百多大卡」「八百左右的」). */
    private const val MEASURE = "大卡|塊錢|個月|公斤|公克|公分|毫升|公里|卡|斤|克|週|年|歲|元"
    private const val COUNT = "份|餐|天|碗|杯|瓶|盒|包|顆|片|壺|口"
    private val AMOUNT_AFTER = Regex("^(多|來|幾|左右|上下|出頭)?\\s*($MEASURE|$COUNT)")
    private val ARTICLE_AFTER = Regex("^\\s*($COUNT)")
    private val HALF_AMOUNT = Regex("半\\s*($MEASURE)")

    /** A figure said to be what is left today (「還剩 1071」「蛋白質還差 102 克」) must be a `left` value of the facts. */
    private val LEFT_BEFORE = Regex("(剩|還差|還有|還能吃|只剩)\\s*(下)?\\s*$")
    private val LEFT_UNIT_AFTER = Regex("^\\s*(多|左右|上下|出頭)?\\s*(大卡|卡|克|公克)?\\s*([，,。！!？?、；]|$)|^\\s*(大卡|卡|克|公克)")

    /** Everyday words, not amounts: 「三餐正常吃」「一天」「一份」 never hide an invented number. */
    private val PLAIN_WORDS = Regex("(?<![零〇一二兩三四五六七八九十百千萬半])(三餐|一天|一份|一餐|一口|一下|一些|一半)")

    /** Dish names that hold a numeral (「三杯雞」「五穀飯」「三明治」): a name, not a figure. */
    private val DISH_NUMERALS = Regex("三杯(?=雞|中卷|透抽|杏鮑菇|豆腐|小卷)|五穀|八寶|十全|四神|三明治|七彩|三色|百頁|千層|一口(?=酥|吃)")

    /** Idioms whose numerals are no figure (「十分重要」「千萬別」「萬一」). */
    private val IDIOMS = Regex("十分(?!鐘)|千萬(?=[別不要記])|萬一|百分之百|一百分|三十而立")

    private val LATIN = Regex("[A-Za-zＡ-Ｚａ-ｚ]")
    private val LEVEL_LETTER = Regex("(?<![A-Za-z])[CBA](?![A-Za-z])")

    /** A shop suffix: a name part in front of it (two characters or more after the food words) needs evidence. */
    private val SHOP_SUFFIX = Regex("餐廳|麵館|食堂|店|館|屋|坊|堂")

    /** Suffixes that are part of a name even after one character (「好食堂」); 店 is not (「麵店」「書店」). */
    private val NAME_ONLY_SUFFIX = setOf("食堂", "館", "屋", "坊", "堂", "麵館")

    /** A dish head: the dish in front of it is fine unless it carries a proper-name mark. */
    private val DISH_HEAD = Regex("套餐|定食|便當|小吃|飯|麵|丼")
    private val PROPER = Regex("^(阿|老)\\p{IsHan}|^小(?![碗份杯盒包塊條火])\\p{IsHan}|\\p{IsHan}{1,3}記|^\\p{IsHan}{1,2}家(?![常人裡])")

    /** 「阿華滷味」「老王雞排」: a name in front of a snack-shop word, with no suffix at all. */
    private val NAMED_SNACK = Regex("(阿|老)\\p{IsHan}(滷味|雞排|豆花|水餃|鍋貼|鹽水雞|蔥油餅)|小(?![碗份杯盒包塊條火])\\p{IsHan}(滷味|雞排|豆花|水餃|鍋貼|鹽水雞|蔥油餅)")

    /** The backward scan stops at these: the candidate is the noun phrase only. */
    private const val STOP = "的了去到在買吃點叫找跟和或是有要想先再就也還那這天己煮帶選改"
    private const val GENERIC_END = "家間個些"

    /** Ingredients, ways of cooking and categories: what is left after them, if two characters or more, is a proper name. */
    private val FOOD_WORDS = listOf(
        "雞腿", "雞胸", "雞肉", "雞絲", "雞丁", "雞排", "鹽水雞", "排骨", "豬排", "魚排", "鮭魚", "鯖魚", "鯛魚", "鮪魚", "虱目魚", "牛肉",
        "豬肉", "羊肉", "鴨肉", "鵝肉", "控肉", "燒肉", "叉燒", "烤雞", "肉燥", "肉絲", "豬腳", "蝦仁", "海鮮", "豆腐", "豆花", "蔬菜",
        "青菜", "香菇", "蘑菇", "地瓜", "番茄", "蔥爆", "三杯", "宮保", "海南", "味噌", "關東煮", "水餃", "鍋貼", "餐盒", "健康餐",
        "糙米", "五穀", "紫米", "白米", "紅燒", "照燒", "鹽烤", "香煎", "清蒸", "麻辣", "酸辣", "黑胡椒", "咖哩", "義大利", "蛋包",
        "鍋燒", "炸醬", "麻醬", "陽春", "烏龍", "招牌", "雙主菜", "健康", "日式", "韓式", "泰式", "中式", "西式", "美式", "素食",
        "滷味", "火鍋", "水果", "早午餐", "早餐", "午餐", "晚餐", "便當", "藥妝", "便利", "自助餐", "牛肉麵", "拉麵", "健身", "飲料",
        "手搖", "咖啡", "麵包", "全麥", "親子", "超值", "經典", "特製", "綜合", "蔬食", "主廚", "家常", "古早味", "傳統",
        "雞", "豬", "牛", "羊", "鴨", "魚", "蝦", "蛋", "菜", "菇", "米", "滷", "烤", "煎", "蒸", "燙", "炒", "燉", "燴", "拌", "乾",
        "湯", "涼", "辣", "意", "白",
    ).sortedByDescending { it.length }

    /** Place words that are not a shop's name (taken out before the name rule). */
    private val GENERIC_PLACES = listOf(
        "便利商店", "超商", "商店", "店家", "店員", "店裡", "學生餐廳", "員工餐廳", "學校餐廳", "學校食堂", "員工食堂", "圖書館",
        "體育館", "健身房", "教堂", "課堂", "天堂", "禮堂", "澡堂", "旅館", "飯店", "飯館", "餐館", "書店", "屋子", "屋裡", "房屋",
    )

    /** Chains and brands: named only when this turn's evidence names them. Word guards keep everyday words out. */
    private val CHAINS: List<Regex> = listOf(
        "全家(?![人都一大])", "萊爾富", "麥當勞", "肯德基", "摩斯", "漢堡王", "星巴克", "路易莎", "八方雲集", "四海遊龍", "鬍鬚張",
        "爭鮮", "吉野家", "頂呱呱", "拿坡里", "必勝客", "達美樂", "迷客夏", "五十嵐", "清心(?!寡)", "可不可(?!以)", "大苑子",
        "茶湯會", "春水堂", "鼎泰豐", "丹丹", "三商巧福", "美而美", "弘爺", "拉亞", "麥味登", "胖老爹", "繼光香香雞", "家樂福",
        "全聯福利中心", "全聯", "好市多", "池上", "悟饕", "小七", "統一超商", "7-?11",
    ).map { Regex(it) }

    /** What a ban is about: memory text other than how_to_talk is checked only for [ATTACH] and [INSULT]. */
    enum class BanKind { ATTACH, INSULT, OTHER }

    /** A ban; [negatable] ones do not hit inside a clause that negates them (「別空腹」「晚餐不要少吃」「跳過午餐不行」). */
    private class Ban(val label: String, val re: Regex, val negatable: Boolean = false, val kind: BanKind = BanKind.OTHER)

    private fun phrases(vararg p: String) = Regex(p.joinToString("|"))

    private val NEG = Regex("不要|不用|不必|不需要|沒必要|別|勿|不能|不准|不可以|不該|不應該|避免|免得|千萬別|不會|沒辦法|無法|沒什麼|沒有什麼")
    private val NEG_AFTER = Regex("^(是)?(不行|不可以|不好|不准|不對)")
    private val CLAUSE_BREAK = charArrayOf('，', ',', '、', '；', ';', '。', '！', '？', '!', '?', '\n')

    /** The match at [r] sits in a clause that negates it: a negation word before it in the clause, or 「不行」 right after. */
    private fun negated(s: String, r: IntRange): Boolean {
        val clauseStart = s.lastIndexOfAny(CLAUSE_BREAK, r.first - 1) + 1
        val start = maxOf(clauseStart, r.first - 10, 0)
        return NEG.containsMatchIn(s.substring(start, r.first)) || NEG_AFTER.containsMatchIn(s.substring(r.last + 1))
    }

    /** Food he may be told to eat less of: the processed and fried kinds the company rules ban outside the 休假餐. */
    private val STEERABLE = (FoodTable.FRIED_WORDS + listOf(
        "油炸", "加工", "零食", "甜", "含糖", "泡麵", "餅乾", "洋芋片", "蛋糕", "甜點", "麵包", "香腸", "熱狗", "火腿", "培根", "鹽",
        "油", "宵夜", "飲料", "手搖",
    )).distinct().sortedByDescending { it.length }
    private val STEER = STEERABLE.joinToString("|")

    /** A starchy part of a meal: 「麵吃一半」「白飯半碗」 is how a meal is composed, not eating less. */
    private const val PART = "飯麵湯粉皮醬"

    /**
     * R1: eating less, in any tone. 「至少吃」「多少吃」 and 「少吃炸的」 are not this; a part of a meal halved (「麵吃一半」) is
     * not either; 減量 counts only next to a meal word, so a code-decided lighter training day (「肌力可以減量」) is not caught.
     */
    private val R1 = Regex(
        "(?<![多至很較])(吃少一點|吃少點|少吃(?:一點|點|一些|些|一口|幾口|半|一半|一餐|一份|一碗|飯))|(?<![$PART])(吃一半|吃半份)|只吃一點|減一半|減少一半|少一半" +
            "|(餐|頓|飯|吃|食量|份量|分量|那份).{0,4}(減量|減半|砍半)|份量少一點|份量少點|少一餐|省一餐|點少一點" +
            "|(?<![多至很較])少吃(?!$STEER)",
    )

    /** R3: exercise to burn it off, in any tone. Making up a photo or a missed set (「補拍」「補做」) is not this. */
    private val R3 = Regex(
        "(多跑|多走|多騎|多練|多做|加練|加做|加倍|再跑|再練|去跑|去走|去騎|運動|流汗|訓練).{0,10}(補(?:回|掉|熱量|卡|起來)|抵|消耗|燒|扣)" +
            "|抵銷|抵消|抵掉|消耗掉|燃燒|燒掉|扣回來|(跑|騎|走|游)(車|步|路|泳)?(跑|騎|走|游)?掉",
    )

    /** Meal words for R2. Breakfast is left out: he has none by plan on class days. */
    private const val MEAL = "[午晚]餐|這餐|下一餐|下一頓|中午|晚上|今晚"

    /** What a diagnosis names: a disease word or a named condition. */
    private const val CONDITION = "症|炎|病|逆流|潰瘍|感染|中暑|低血糖|貧血|骨折|扭傷|拉傷|過敏"

    /** Always banned, every tone. */
    private val ALWAYS: List<Ban> = listOf(
        Ban("依附或情緒勒索", phrases(
            "不要走", "別走", "不准走", "再陪我", "多陪我", "陪陪我", "你不在我會", "我會難過", "我會很難過", "我會傷心", "我會想你",
            "好想你", "想你了", "想我嗎", "都不找我", "怎麼不找我", "不回我", "不跟我說話", "不理我了", "(我|人家)(好|很|會|也)?寂寞", "，好寂寞", "^好寂寞", "只有我懂你", "只有我最懂你", "只有我了解你", "只有我在乎你", "好感度",
            "好感(?![動冒])", "你不理我", "都不理我", "冷落我", "離不開我", "你是我的(?!練習生)", "不准離開", "讓我失望", "讓我很失望",
            "對得起我", "都是你害的", "丟下我",
        ), kind = BanKind.ATTACH),
        Ban("替違規找台階的安慰", phrases(
            "偶爾一次", "沒差", "吃了就吃了", "放縱一下", "放鬆一下也好", "偶爾放鬆", "一次而已", "已經很(棒|努力|好)了", "就算了", "也還好",
            "還好啦", "沒事啦", "沒事的", "沒事沒事", "(吃|喝)一點沒事", "下不為例", "原諒你", "(?<![計打清總])算了(?!一下)", "你最棒(了)?", "破戒.{0,4}正常", "不會怎樣", "沒什麼大不了",
        )),
        Ban("罵人", phrases("廢物", "沒用(?![到完過])", "胖子", "肥豬", "死胖", "肥宅", "懶鬼", "懶惰鬼", "笨蛋", "白癡", "魯蛇", "沒救了"), kind = BanKind.INSULT),
        Ban("罵人", Regex("(很|真|好|太|超|有夠)丟臉|丟臉(死|欸|耶|啊)|你(這樣)?丟臉"), negatable = true, kind = BanKind.INSULT),
        Ban("叫他少吃", R1, negatable = true),
        Ban(
            "叫他跳餐或餓肚子",
            Regex("跳過?(?:[午晚]餐|這餐|下一餐|下一頓)|斷食|禁食|空腹|空著肚子|餓肚子|餓著|餓一下|忍著不吃|忍一下|撐過去|喝水撐|喝水止餓" +
                "|(?:$MEAL|早餐午餐|午餐晚餐).{0,4}(?:都)?(?:省掉|免了|省略)"),
            negatable = true,
        ),
        Ban(
            "叫他跳餐或餓肚子",
            Regex("(?:不吃|別吃|不要吃|不用吃|不必吃)(?:$MEAL)|(?:$MEAL)(?:就|先|也|乾脆|直接|今天|明天){0,2}(?:跳過|不吃|不要吃|別吃|不用吃)(?!$STEER)"),
            negatable = true,
        ),
        Ban("叫他用運動補回來", R3),
        Ban("給體重速度或唱歌高度壓力", phrases("瘦得?太慢", "減得?太慢", "掉得?太慢", "怎麼還沒瘦", "還這麼胖", "還是這麼胖", "唱不上去", "高音.{0,4}(不行|太爛|太差)")),
        Ban("下醫療診斷", Regex("(我|幫你|替你)(來|先)?(幫你)?診斷|診斷(結果)?(是|為)|你得了|你罹患"), negatable = true),
        Ban("下醫療診斷", Regex("((應該|可能|大概|八成|一定|就)是|像是?)\\p{IsHan}{0,6}($CONDITION)")),
    )

    /** Comfort words: banned in COLD and SWEET; in GENTLE only when the sentence excuses a slip. */
    private val COMFORT = Regex("沒關係|不用在意|別在意|不要緊")

    /** Words that make comfort an excuse whatever the feeling; eating and drinking count only when no feeling is named. */
    private val SLIP_ONLY = Regex("炸|甜|零食|宵夜|超過|違規|沒照|沒做|沒練|沒拍|沒回報|晚睡|遲到|偷懶|休假餐|規章|吃太多|吃多")
    private val SLIP = Regex("吃|喝|餐|輪|核心|伸展")

    /** R4: after an overrun, any cut or skip anywhere in the reply, and any 補 that is not an increase. */
    private val OVERRUN = Regex("超過|超標|超出|超了|爆卡|吃爆|爆了|爆表|吃多了|吃太多|多吃了|吃超量|吃過頭|過頭了|多出來的熱量")
    private val R4_CUT = Regex(
        "(?<![至多])少(?!(喝|吃|碰|點)?($STEER))|不吃|別吃|不要吃|宵夜不吃|下一餐不|跳過|補(?!充|蛋白|一份|吃|水|拍|交|上|給|做)" +
            "|減量|減半|減一半|砍半|吃一半|半碗|半份|一點點|只喝|只吃|(不要|別|不)再吃|份量少|省掉|省略|免了|撐過去|撐一下|忍一下" +
            "|隨便吃點|吃點水果|水果就好|換成(黑咖啡|咖啡|水|茶|水果|沙拉)|多做|多練|多跑|多走|多騎|加練|加做",
    )

    private val PROMISE = phrases(
        "我已經(幫你)?(改|換|調|排|加|刪|移|設|更新)", "已經幫你(改|換|調|排|加|刪|移|設|更新)", "幫你改好了",
        "(計畫|行程|菜單|課表|時間|作息|鬧鐘|設定).{0,4}(已經)?(改好了|換好了|更新了)", "(計畫|行程|菜單|課表|時間|作息)已經(改|換|調|更新)",
    )

    fun sentences(text: String): List<String> = SENTENCE.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

    /** Questions in [text]: question marks, plus a sentence that ends in 嗎 or 呢 without one. */
    fun questions(text: String): Int = sentences(text).sumOf { s ->
        val marks = s.count { it == '？' || it == '?' }
        if (marks > 0) marks else if (Regex("[嗎呢][。！!]?$").containsMatchIn(s)) 1 else 0
    }

    fun check(text: String, evidence: Evidence, mode: GuardMode, tone: Tone = Tone.COLD): GuardResult {
        val all = sentences(text.replace("BMI", "身體質量指數"))
        val violations = mutableListOf<GuardViolation>()
        val kept = all.filter { s ->
            val dropped = sentenceIssues(s, evidence, names = true)
            violations += dropped
            bans(s, ALWAYS).forEach { violations += GuardViolation(GuardRule.BANNED, it) }
            comfort(s, tone, evidence)?.let { violations += GuardViolation(GuardRule.BANNED, it) }
            if (!evidence.acceptedProposal) PROMISE.find(s)?.let { violations += GuardViolation(GuardRule.PROMISE, "「${it.value}」：計畫沒有改，他還沒確認") }
            dropped.isEmpty()
        }
        overrunCut(text)?.let { violations += GuardViolation(GuardRule.BANNED, it) }
        val cap = if (mode == GuardMode.NUDGE) MAX_NUDGE_SENTENCES else MAX_SENTENCES
        if (all.size > cap) violations += GuardViolation(GuardRule.MODE, "最多 $cap 句，現在 ${all.size} 句")
        if (mode == GuardMode.DISCUSS) {
            val q = questions(text)
            if (q != 1) violations += GuardViolation(GuardRule.MODE, "討論時只能問一個問題，現在 $q 個")
        }
        return GuardResult(kept.joinToString(""), violations.distinct())
    }

    /**
     * The first tone-free ban [text] breaks, as its label; null when there is none. With [full] (how_to_talk memories) that is
     * every ban plus a cut after an overrun; otherwise (facts about him: 「他討厭空腹練」 is true, not advice) only the
     * attachment and insult bans.
     */
    fun bannedLabel(text: String, full: Boolean = true): String? {
        val list = if (full) ALWAYS else ALWAYS.filter { it.kind != BanKind.OTHER }
        return sentences(text).firstNotNullOfOrNull { bans(it, list).firstOrNull() } ?: if (full) overrunCut(text) else null
    }

    private fun bans(s: String, list: List<Ban>): List<String> = list.mapNotNull { b ->
        b.re.findAll(s).firstOrNull { m -> !b.negatable || !negated(s, m.range) }?.let { "${b.label}：「${it.value}」" }
    }

    private fun comfort(s: String, tone: Tone, ev: Evidence): String? {
        val m = COMFORT.find(s) ?: return null
        val excuses = when (tone) {
            Tone.GENTLE -> {
                val feeling = ToneDecider.GENTLE_WORDS.any { s.contains(it) }
                SLIP_ONLY.containsMatchIn(s) || (!feeling && SLIP.containsMatchIn(s)) || (ev.breachToday && !feeling)
            }
            Tone.COLD, Tone.SWEET -> true
        }
        return if (excuses) "替違規找台階的安慰：「${m.value}」" else null
    }

    private fun overrunCut(text: String): String? {
        OVERRUN.findAll(text).firstOrNull { !negated(text, it.range) } ?: return null
        val cut = R4_CUT.findAll(text).firstOrNull { !negated(text, it.range) } ?: return null
        return "吃超過了只能說明天回到額度，不准少吃、跳餐或補回來：「${cut.value}」"
    }

    /** The sentence-level rules (1-4 of §1.9); any finding drops the sentence. */
    private fun sentenceIssues(s: String, ev: Evidence, names: Boolean): List<GuardViolation> {
        val out = mutableListOf<GuardViolation>()
        numberIssues(s, ev).forEach { out += GuardViolation(GuardRule.NUMBER, "數字「$it」不在資料裡") }
        val link = Links.containsAny(s)
        if (link) out += GuardViolation(GuardRule.LINK, "不准寫網址、網域或檔名")
        if (names) unknownNames(s, ev).forEach { out += GuardViolation(GuardRule.NAME, "「$it」不在這一輪的搜尋結果、常去店家或食物表裡") }
        if (!link && latinLeft(s, ev)) out += GuardViolation(GuardRule.LATIN, "不准用英文字母")
        return out
    }

    private fun numberIssues(sentence: String, ev: Evidence): List<String> {
        val bad = mutableListOf<String>()
        var s = TIME.replace(sentence) { m -> if (!ev.hasTime(timeToken(m.groupValues[1], m.groupValues[2]))) bad += m.value; "　" }
        s = CLOCK.replace(s) { m ->
            val tokens = clockTokens(m, s.getOrNull(m.range.first - 1)) ?: return@replace m.value
            if (tokens.none { ev.hasTime(it) }) bad += m.value.trim()
            "　"
        }
        s = DATE.replace(s) { m -> if (!ev.hasDay(dayToken(m.groupValues[1], m.groupValues[2]))) bad += m.value.trim(); "　" }
        s = ZH_DATE.replace(s) { m ->
            val mo = num(m.groupValues[1]); val d = num(m.groupValues[2])
            if (mo == null || d == null || !ev.hasDay("$mo/$d")) bad += m.value
            "　"
        }
        s = PLAIN_WORDS.replace(DISH_NUMERALS.replace(IDIOMS.replace(s, "　"), "　"), "　")
        fun leftWrong(at: IntRange, v: Double): Boolean {
            if (!LEFT_BEFORE.containsMatchIn(s.substring(0, at.first)) || !LEFT_UNIT_AFTER.containsMatchIn(s.substring(at.last + 1))) return false
            return ev.isLeft(v) == false
        }
        CoachNumbers.ARABIC.findAll(s).forEach { m ->
            val rest = s.substring(m.range.last + 1).trimStart()
            if (UNIT_OK.containsMatchIn(rest) && !RULE_COUNT_AFTER.containsMatchIn(rest)) return@forEach
            val v = CoachNumbers.arabic(m.value) ?: return@forEach
            if (!ev.hasNumber(v) || leftWrong(m.range, v)) bad += m.value
        }
        CoachNumbers.CHINESE.findAll(s).forEach { m ->
            val rest = s.substring(m.range.last + 1)
            val before = if (m.range.first > 0) s[m.range.first - 1] else ' '
            val ruleCount = RULE_COUNT_AFTER.containsMatchIn(rest)
            if ((UNIT_OK.containsMatchIn(rest.trimStart()) && !ruleCount) || before == '第') return@forEach
            // 「一包」 is the article, not a figure.
            if (m.value == "一" && ARTICLE_AFTER.containsMatchIn(rest)) return@forEach
            val v = CoachNumbers.chinese(m.value) ?: return@forEach
            val checked = ruleCount || v >= 10 || m.value.any { it in "十百千萬" } || AMOUNT_AFTER.containsMatchIn(rest)
            if (checked && (!ev.hasNumber(v) || leftWrong(m.range, v))) bad += m.value
        }
        HALF_AMOUNT.findAll(s).forEach { m -> if (!ev.hasNumber(0.5)) bad += m.value }
        return bad
    }

    /**
     * Chain names, and proper names in front of a shop suffix or carrying a name mark in front of a dish head, that no evidence
     * of this turn contains. Dish names and categories pass (「番茄炒蛋飯」「附近的滷味店」); a name (「池上」「阿明」「老王」
     * 「王記」「好食堂」) must be in the evidence.
     */
    private fun unknownNames(sentence: String, ev: Evidence): List<String> {
        var s = sentence
        ev.names.forEach { s = s.replace(it, "　") }
        val unknown = mutableListOf<String>()
        CHAINS.forEach { re ->
            re.findAll(s).forEach { m -> if (!ev.mentions(m.value)) unknown += m.value }
            s = re.replace(s, "　")
        }
        NAMED_SNACK.findAll(s).forEach { m -> if (!ev.mentions(m.value)) unknown += m.value }
        GENERIC_PLACES.sortedByDescending { it.length }.forEach { s = s.replace(it, "　") }
        fun prefixOf(at: Int): String {
            var from = at
            while (from > 0 && Character.UnicodeScript.of(s[from - 1].code) == Character.UnicodeScript.HAN &&
                s[from - 1] !in STOP && at - from < 8) from--
            return s.substring(from, at)
        }
        fun known(prefix: String, head: String, core: String) =
            ev.mentions(core) || (2..prefix.length).any { k -> ev.mentions(prefix.takeLast(k) + head) }
        SHOP_SUFFIX.findAll(s).forEach { m ->
            val prefix = prefixOf(m.range.first)
            if (prefix.isEmpty() || prefix.last() in GENERIC_END) return@forEach
            var core = prefix
            FOOD_WORDS.forEach { core = core.replace(it, "") }
            val name = core.length >= 2 || (prefix.length == 1 && core.length == 1 && m.value in NAME_ONLY_SUFFIX)
            if (name && !known(prefix, m.value, core)) unknown += prefix + m.value
        }
        val dishes = SHOP_SUFFIX.replace(s, "　")
        DISH_HEAD.findAll(dishes).forEach { m ->
            var from = m.range.first
            while (from > 0 && Character.UnicodeScript.of(dishes[from - 1].code) == Character.UnicodeScript.HAN &&
                dishes[from - 1] !in STOP && m.range.first - from < 8) from--
            val prefix = dishes.substring(from, m.range.first)
            val mark = PROPER.find(prefix) ?: return@forEach
            if (!known(prefix, m.value, mark.value)) unknown += prefix + m.value
        }
        return unknown.distinct()
    }

    private fun latinLeft(s: String, ev: Evidence): Boolean {
        var t = s
        ev.latin.forEach { t = t.replace(it, " ", ignoreCase = true) }
        t = LEVEL_LETTER.replace(t, " ")
        return LATIN.containsMatchIn(t)
    }

    /**
     * The old coach's filter (CoachEngine, until T09 deletes it): the number, link and Latin rules against its facts and his
     * message, then at most [MAX_SENTENCES] sentences. The name rule is left out: the old facts do not list his places.
     */
    fun legacyFilter(modelText: String, facts: String, ownerText: String): String {
        val ev = Evidence(listOf(facts), ownerText = ownerText)
        return sentences(modelText.replace("BMI", "身體質量指數")).filter { sentenceIssues(it, ev, names = false).isEmpty() }
            .take(MAX_SENTENCES).joinToString("").trim()
    }
}
