package app.agency.core.rules

import app.agency.core.model.CompanyRules

/**
 * The day's numbers after a record (code lines, never the model's): what is left and the protein gap, the floor line while
 * the day is under 1200, or the fixed four lines when it is over (spec §5, [CoachRules.overQuota]) ending in 「明天回到 1700。
 * 不准少吃來補。」. On a day he is ill the four lines give way to 「今天先正常吃，不用管額度。」.
 */
object OverQuota {
    const val ILL_NO_QUOTA = "今天先正常吃，不用管額度。"
    const val DOCTOR = "今天正常吃，身體不舒服就去看醫生。"

    /** The four lines for [over] kcal; without his height or weight the 肥胖區 clause is left out. */
    fun lines(over: Int, rules: CompanyRules, goalKg: Double?, currentKg: Double?, heightCm: Double?): List<String> {
        val known = currentKg != null && heightCm != null
        return CoachRules.overQuota(over, rules.quotaKcal, goalKg, if (known) currentKg!! else 0.0, if (known) heightCm!! else 170.0)
    }

    fun dayStatus(
        rules: CompanyRules,
        eatenKcal: Int,
        eatenProteinG: Double,
        goalKg: Double?,
        currentKg: Double?,
        heightCm: Double?,
        ill: Boolean,
    ): List<String> {
        val left = rules.quotaKcal - eatenKcal
        if (left < 0) return if (ill) listOf(ILL_NO_QUOTA) else lines(-left, rules, goalKg, currentKg, heightCm)
        val out = mutableListOf("今天吃了 $eatenKcal 大卡，剩 $left 大卡，蛋白質還差 ${CoachRules.remainingProtein(rules.proteinG, eatenProteinG)} 克。")
        val must = rules.floorKcal - eatenKcal
        if (must > 0) out += "今天至少還要吃 $must 大卡。"
        return out
    }
}
