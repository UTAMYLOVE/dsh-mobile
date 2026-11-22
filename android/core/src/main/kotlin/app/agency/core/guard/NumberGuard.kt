package app.agency.core.guard

/**
 * The last word on a model sentence: it may not hold a number the code did not hand over (Arabic or Chinese), nor a Latin
 * letter (the owner reads only Traditional Chinese). Deadlines and reps (「10 分鐘」「3 組」) may carry their own numbers.
 */
object NumberGuard {
    private val NUMBER = CoachNumbers.ARABIC
    private val UNIT_OK = Regex("^\\s*(分鐘|小時|點|秒|次|下|組)")
    private val CHINESE_AMOUNT = Regex("[零〇一二兩三四五六七八九十百千萬半]+\\s*(大卡|卡|公斤|斤|克|天|餐|份)")
    private val LATIN = Regex("[A-Za-zＡ-Ｚａ-ｚ]")
    /** Everyday words, not amounts: 「三餐正常吃」「一天」「一份」 never hide an invented number. */
    private val PLAIN_WORDS = Regex("(?<![零〇一二兩三四五六七八九十百千萬半])(三餐|一天|一份|一餐)")
    private val SENTENCE = Regex("(?<=[。！？!?\\n])")
    const val MAX_SENTENCES = 4

    fun filter(modelText: String, facts: String, ownerText: String): String {
        val allowed = (NUMBER.findAll(facts).map { it.value } + NUMBER.findAll(ownerText).map { it.value }).toSet()
        val text = modelText.trim().replace("BMI", "身體質量指數")
        val kept = SENTENCE.split(text).map { it.trim() }.filter { it.isNotEmpty() }.filter { s ->
            !LATIN.containsMatchIn(s) &&
                !CHINESE_AMOUNT.containsMatchIn(PLAIN_WORDS.replace(s, "")) &&
                NUMBER.findAll(s).all { m -> m.value in allowed || UNIT_OK.containsMatchIn(s.substring(m.range.last + 1)) }
        }
        return kept.take(MAX_SENTENCES).joinToString("").trim()
    }
}
