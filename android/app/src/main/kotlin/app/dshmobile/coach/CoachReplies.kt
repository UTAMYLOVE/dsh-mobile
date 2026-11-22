package app.dshmobile.coach

import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.MealItem
import app.agency.core.rules.CoachRules
import app.agency.core.rules.FoodTable
import java.math.BigDecimal
import java.math.RoundingMode

/** The coach's fixed sentences. Every number in them is computed by the code. */
object CoachReplies {
    const val DOCTOR = "今天正常吃，身體不舒服就去看醫生。"
    const val ILL_NO_QUOTA = "今天先正常吃，不用管額度。"
    const val NOT_UNDERSTOOD = "沒聽懂。說清楚吃了什麼、幾份。"
    const val NO_PENDING = "沒有待確認的食物。"
    const val UNVERIFIED = "這餐先記「未證實」。"
    const val OFF_PLAN = "炸物，今天不是休假餐，記「沒照計畫」。"
    const val CHAT_DOWN = "公司現在連不上，晚點再說。"
    const val FOOD_INFO_UNCLEAR = "說清楚包裝標示寫幾大卡，或回「對」，就照估算存進表。"
    const val NOT_SAVED = "紀錄沒存成功，等一下再說一次。"
    /** A model reply the guard emptied: the code's own line instead. */
    const val CHAT_FALLBACK = "收到。照計畫走，吃了什麼就回報。"
    private val ATE_WORDS = listOf("吃了", "喝了", "吃完", "剛吃", "吃過", "喝完")
    private val COUNT = Regex("[0-9０-９兩二三四五六七八九十半]")
    private val NEGATION = Regex("沒|不")
    private val QUESTION = Regex("幾|多少|嗎|？|\\?")

    /** 「想吃…」 with no 「吃了」: a craving, not a meal. */
    fun isCraving(text: String): Boolean = CRAVING_WORDS.any { text.contains(it) } && ATE_WORDS.none { text.contains(it) }

    /** Offline, a sentence may be recorded from the table only if it plainly says one portion was eaten: no count, no 沒/不, no question, no craving. */
    fun plainReport(text: String, table: List<FoodEntry> = emptyList()): Boolean {
        // A numeral inside a table food's own name (三明治, 三杯雞) is not a count.
        var t = FoodTable.normalize(text)
        table.flatMap { listOf(it.name) + it.aliases }.map(FoodTable::normalize).filter { it.length >= 2 }.sortedByDescending { it.length }
            .forEach { t = t.replace(it, "") }
        return plainReportOf(t, text)
    }

    private fun plainReportOf(stripped: String, text: String): Boolean =
        !COUNT.containsMatchIn(stripped) && !NEGATION.containsMatchIn(text) && !QUESTION.containsMatchIn(text) && CRAVING_WORDS.none { text.contains(it) }
    val ILL_WORDS = listOf("頭暈", "不舒服", "想吐", "昏倒", "心悸", "發燒", "胸悶", "暈倒", "頭痛", "肚子痛")
    val CRAVING_WORDS = listOf("想吃", "要吃", "可以吃", "能吃", "想點", "要點", "可不可以", "能不能", "想喝", "要喝")

    fun portion(p: Double): String {
        val b = BigDecimal(p).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (b.scale() <= 0) b.setScale(0).toPlainString() else b.toPlainString()
    }

    fun marginWords(marginPct: Int): String = when (marginPct) {
        0 -> "取高值"
        10 -> "取高值加一成"
        else -> "取高值加 $marginPct%"
    }

    fun sourceShort(i: MealItem): String = when (i.source) {
        KcalSource.LABEL -> "標示"
        KcalSource.TABLE -> if (i.tableEstimated) "食物表・估算" else "食物表"
        KcalSource.ESTIMATE -> "估算"
    }

    private val UNIT = Regex("(?:一|1)?(盤|碗|份|個|杯|盒|條|塊)$")

    /** 「蚵仔麵線一碗」 × 2 → 「蚵仔麵線 2碗」: the table name's own unit, never 「一碗 2份」. */
    fun amount(name: String, p: Double): String {
        val m = UNIT.find(name)
        return if (m != null && m.range.first > 0) "${name.substring(0, m.range.first)} ${portion(p)}${m.groupValues[1]}" else "$name ${portion(p)}份"
    }

    fun itemLine(i: MealItem, marginPct: Int): String {
        val src = when {
            i.zeroCal && i.kcal == 0 -> "零卡飲料"
            i.source == KcalSource.ESTIMATE -> "估算，${marginWords(marginPct)}"
            else -> sourceShort(i)
        }
        return "記下：${amount(i.name, i.portion)} ${i.kcal} 大卡（$src）。"
    }

    fun askLabel(name: String) = "${name}不在食物表。把包裝標示或份量傳來，確認了才存進表。"

    fun noEstimate(name: String) = "${name}估不出來，沒有算進去。把包裝標示或份量傳來。"

    /** 「今天剩 R 大卡，蛋白質還差 P 克。」 plus the floor line; or the four-line block when over. */
    fun dayStatus(profile: CoachProfile, eaten: Int, protein: Double, goalKg: Double?, currentKg: Double, illness: Boolean): List<String> {
        val remaining = CoachRules.remainingKcal(profile.quotaKcal, eaten)
        if (remaining < 0) {
            return if (illness) listOf(ILL_NO_QUOTA)
            else CoachRules.overQuota(-remaining, profile.quotaKcal, goalKg, currentKg, profile.heightCm)
        }
        val lines = mutableListOf("今天剩 $remaining 大卡，蛋白質還差 ${CoachRules.remainingProtein(profile.proteinG, protein)} 克。")
        val must = CoachRules.mustStillEat(eaten)
        if (must > 0) lines += "今天至少還要吃 $must 大卡。"
        return lines
    }

    fun weight(kg: Double, waist: Double?, profile: CoachProfile, goal: Double?): String {
        val sb = StringBuilder("記下：${CoachRules.kg(kg)} 公斤")
        waist?.let { sb.append("（腰圍 ${CoachRules.kg(it)} 公分）") }
        sb.append("。身體質量指數 ${CoachRules.bmi(kg, profile.heightCm)}，${CoachRules.zone(kg, profile.heightCm).zh}。")
        if (goal != null) {
            val left = BigDecimal(kg - goal).setScale(1, RoundingMode.HALF_UP).toPlainString()
            sb.append("下一個目標 ${CoachRules.kg(goal)} 公斤，還差 $left 公斤。")
        } else {
            sb.append("階段目標都到了，到設定重新訂目標。")
        }
        return sb.toString()
    }

    fun failReason(f: Fail): String = when (f) {
        Fail.NO_KEY -> "沒有金鑰，到設定"
        Fail.NO_SETUP -> "模型還沒設定好，到設定"
        Fail.CAP -> "今天的花費上限到了"
        Fail.OFFLINE -> "網路不通"
        Fail.KEY_REJECTED -> "金鑰被拒絕，到設定檢查"
        Fail.RATE -> "太多人在用，等一下"
        Fail.SERVER -> "服務暫時出問題"
        Fail.REFUSED -> "模型拒絕回答"
        Fail.BAD_RESPONSE -> "回覆看不懂"
    }

    fun cannotReach(f: Fail) = "公司連不上（${failReason(f)}）。紀錄沒有存，等一下再說一次。"
}
