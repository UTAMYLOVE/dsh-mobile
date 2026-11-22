package app.agency.core.rules

import app.agency.core.model.CompanyRules

/** One field of the rules that is out of bounds, with the line he reads. */
data class RuleViolation(val field: String, val zh: String)

/**
 * The hard bounds code keeps under 公司規章 (arch 1.2, 1.5): even he cannot set rules outside them in 設定, and no link, tool
 * or proposal can change the rules at all. Floor ≥ 1200; quota ≥ floor and ≤ 4000; every goal ≥ 52 kg; meal cap 300..1200 and
 * not above the quota; one 休假餐 a week at most; zero-calorie drinks 0..7 a week; protein 40..300 g.
 */
object SafetyBounds {
    const val FLOOR_MIN = 1200
    const val QUOTA_MAX = 4000
    const val GOAL_MIN_KG = 52.0
    const val MEAL_CAP_MIN = 300
    const val MEAL_CAP_MAX = 1200

    fun check(r: CompanyRules): List<RuleViolation> = buildList {
        if (r.floorKcal < FLOOR_MIN) add(RuleViolation("floor_kcal", "最低熱量不能低於 $FLOOR_MIN 大卡。"))
        if (r.quotaKcal < r.floorKcal) add(RuleViolation("quota_kcal", "每天的熱量不能低於最低熱量。"))
        if (r.quotaKcal > QUOTA_MAX) add(RuleViolation("quota_kcal", "每天的熱量不能超過 $QUOTA_MAX 大卡。"))
        if (r.goalsKg.any { it < GOAL_MIN_KG }) add(RuleViolation("goals_kg", "目標體重不能低於 ${GOAL_MIN_KG.toInt()} 公斤。"))
        if (r.mealCapKcal !in MEAL_CAP_MIN..MEAL_CAP_MAX) add(RuleViolation("meal_cap_kcal", "單餐上限要在 $MEAL_CAP_MIN 到 $MEAL_CAP_MAX 大卡之間。"))
        if (r.mealCapKcal > r.quotaKcal) add(RuleViolation("meal_cap_kcal", "單餐上限不能比每天的熱量高。"))
        if (r.cheatMealsPerWeek !in 0..1) add(RuleViolation("cheat_meals_per_week", "休假餐一週最多一餐。"))
        if (r.zeroCalPerWeek !in 0..7) add(RuleViolation("zero_cal_per_week", "零卡飲料一週在 0 到 7 瓶之間。"))
        if (r.proteinG !in 40..300) add(RuleViolation("protein_g", "蛋白質要在 40 到 300 克之間。"))
        if (r.fatG !in 10..200 || r.carbsG !in 20..600) add(RuleViolation("macros", "脂肪或碳水的數字不合理。"))
    }
}
