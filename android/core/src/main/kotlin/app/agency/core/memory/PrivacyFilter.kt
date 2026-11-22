package app.agency.core.memory

/**
 * What may never become a memory: numbers (they belong to records), keys and passwords, ids and card numbers, addresses,
 * phone numbers, e-mail and links. Returns the reason for the first problem, or null when the text may be kept.
 */
object PrivacyFilter {
    private val DIGIT = Regex("[0-9０-９]")
    private val CHINESE_FIGURE = Regex("[零〇一二兩三四五六七八九十百千萬]+\\s*(大卡|卡|公斤|斤|公克|克|公分|毫升|元|塊錢|號|樓|歲)")
    private val SECRET_WORDS = Regex("金鑰|密碼|密鑰|驗證碼|帳號|卡號|信用卡|提款卡|身分證|身份證|護照|健保卡|銀行")
    // No \b: next to Han it means different things on the JVM and on Android's ICU engine; ASCII lookarounds say it once.
    private val TOKEN = Regex("(?i)(?<![A-Za-z0-9_])(sk|pk|api|key|token)[-_][A-Za-z0-9]|[A-Za-z0-9_\\-]{16,}")
    private val ADDRESS = Regex("地址|住址|住在|(路|街|大道)\\p{script=Han}{0,4}(段|巷|弄|號|樓)|[巷弄]\\p{script=Han}{0,3}號")
    private val CONTACT = Regex("(?i)@|https?://|www\\.|\\.(com|tw|net|org)(?![A-Za-z0-9_])|電話|手機號碼|門牌")

    fun reason(text: String): String? = when {
        DIGIT.containsMatchIn(text) || CHINESE_FIGURE.containsMatchIn(text) -> "不能有數字，數字要記在紀錄裡"
        SECRET_WORDS.containsMatchIn(text) || TOKEN.containsMatchIn(text) -> "不能記金鑰、密碼、帳號、證件或卡號"
        ADDRESS.containsMatchIn(text) -> "不能記地址"
        CONTACT.containsMatchIn(text) -> "不能記聯絡方式或網址"
        else -> null
    }
}
