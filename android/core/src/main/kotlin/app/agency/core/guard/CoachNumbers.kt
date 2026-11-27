package app.agency.core.guard

/** Numbers the way the owner writes them: Arabic (95.2) or Chinese (九十五點二, 兩百五十). */
object CoachNumbers {
    /**
     * Arabic figures, half-width or full-width, with a decimal part (95.2, ９５．２). A thousands separator is an ASCII comma
     * between groups of three (1,071); a full-width comma is punctuation (「上限是 800，1071 是今天剩的」 holds two numbers).
     */
    val ARABIC = Regex("[0-9０-９]{1,3}(?:,[0-9０-９]{3})+(?:[.．][0-9０-９]+)?|[0-9０-９]+(?:[.．][0-9０-９]+)?")
    val CHINESE = Regex("[零〇一二兩三四五六七八九十百千萬]+(?:點[零〇一二兩三四五六七八九]+)?")
    private val DIGIT = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 九十五點二 → 95.2; null when [s] is not a Chinese number. */
    fun chinese(s: String): Double? {
        val parts = s.split('點')
        var total = 0L
        var section = 0L
        var num = 0L
        var any = false
        var lastUnit = 1L
        for (ch in parts[0]) {
            val d = DIGIT[ch]
            if (d != null) { num = d.toLong(); any = true; continue }
            when (ch) {
                '十' -> { section += (if (num == 0L) 1 else num) * 10; num = 0; any = true; lastUnit = 10 }
                // 百五 = 150, 千二 = 1200: a 百 or 千 with no digit before it means one, as 十 does.
                '百' -> { section += (if (num == 0L) 1 else num) * 100; num = 0; any = true; lastUnit = 100 }
                '千' -> { section += (if (num == 0L) 1 else num) * 1000; num = 0; any = true; lastUnit = 1000 }
                '萬' -> { total += (section + num) * 10000; section = 0; num = 0; lastUnit = 10000 }
                else -> return null
            }
        }
        if (!any) return null
        // 兩百五 = 250, 一千二 = 1200: a bare digit right after 百/千/萬 counts in the next unit down.
        val head = parts[0]
        if (num > 0 && head.length >= 2 && head[head.length - 2] in "百千萬" && lastUnit >= 100) num *= lastUnit / 10
        val whole = (total + section + num).toDouble()
        if (parts.size < 2) return whole
        val frac = parts[1].map { DIGIT[it] ?: return null }.joinToString("")
        return "${whole.toLong()}.$frac".toDouble()
    }

    /** An [ARABIC] match as a number: full-width digits made half-width, separators dropped. */
    fun arabic(s: String): Double? = buildString {
        for (c in s) when (c) {
            in '０'..'９' -> append('0' + (c - '０'))
            '．' -> append('.')
            ',' -> Unit
            else -> append(c)
        }
    }.toDoubleOrNull()

    /** Every number in [text], Arabic or Chinese. */
    fun all(text: String): List<Double> =
        ARABIC.findAll(text).mapNotNull { arabic(it.value) }.toList() + CHINESE.findAll(text).mapNotNull { chinese(it.value) }.toList()

    /** [v] (from the model) only if the owner wrote that same number himself; null otherwise. */
    fun ownersOnly(v: Double?, ownerText: String): Double? = v?.takeIf { x -> all(ownerText).any { kotlin.math.abs(it - x) < 1e-6 } }
}
