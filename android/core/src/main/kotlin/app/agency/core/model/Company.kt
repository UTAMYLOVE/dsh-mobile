package app.agency.core.model

/**
 * The trainee's file (one row). [joinDate] and every other `day` in the model is a coach day `yyyy-MM-dd`; [level] is `C` in
 * stage 1; [areaText] is a coarse area he types himself (for food search), never a location from the phone.
 */
data class Profile(
    val heightCm: Double? = null,
    val startWeightKg: Double? = null,
    val stageName: String? = null,
    val memberColor: String? = null,
    val companyName: String? = null,
    val traineeNo: String? = null,
    val joinDate: String? = null,
    val level: String = "C",
    val areaText: String? = null,
    val idolNames: List<String> = emptyList(),
    val dayStartHour: Int = 5,
    val onboardingDone: Boolean = false,
)

/**
 * 公司規章 (one current row plus one history row per version). The defaults are plan v3's. Only the 公司規章 settings screen
 * writes them; the store assigns [version] and [updatedAt]. [banned] holds category keys.
 */
data class CompanyRules(
    val quotaKcal: Int = 1700,
    val proteinG: Int = 140,
    val fatG: Int = 50,
    val carbsG: Int = 170,
    val floorKcal: Int = 1200,
    val mealCapKcal: Int = 800,
    val cheatMealsPerWeek: Int = 1,
    val zeroCalPerWeek: Int = 3,
    val marginPct: Int = 10,
    val banned: List<String> = emptyList(),
    val goalsKg: List<Double> = listOf(75.0, 68.0),
    val version: Int = 0,
    val updatedAt: Long = 0,
)

/** The four parts of his plan that a confirmed proposal (or he, in 設定) may change. */
enum class PlanSectionKey { SCHEDULE, MEALS, TRAINING, WATER }

/**
 * One plan section as stored: [json] is the serialised typed spec of that section. [changedBy] is `OWNER_SETTINGS`,
 * `MIGRATION` or the id of the accepted proposal.
 */
data class PlanSection(
    val section: PlanSectionKey,
    val json: String,
    val version: Int,
    val updatedAt: Long,
    val changedBy: String,
) {
    companion object {
        const val BY_OWNER_SETTINGS = "OWNER_SETTINGS"
        const val BY_MIGRATION = "MIGRATION"
    }
}

/** What he declared for one coach day: 「今天休息」, the 休假餐 slot, 「早上很餓」. */
data class DayOverride(
    val day: String,
    val restDay: Boolean = false,
    val cheatSlot: String? = null,
    val soyMorning: Boolean = false,
    val declaredAt: Long,
)
