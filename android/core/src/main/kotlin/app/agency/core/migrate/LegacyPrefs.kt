package app.agency.core.migrate

/**
 * The coach's settings in SharedPreferences `dshm_coach` (v0.8.3 and the 5a94d3c build), read from a snapshot of its values
 * (`SharedPreferences.all`). Each read applies the range the coach applied (`CoachSettings` in the app); a value out of range
 * or of the wrong type reads as absent, so the caller's default applies, as it did in the coach.
 */
class LegacyPrefs(private val values: Map<String, Any?>) {
    val isEmpty: Boolean get() = values.isEmpty()

    fun has(key: String): Boolean = values[key] != null

    fun string(key: String): String? = values[key] as? String
    fun int(key: String): Int? = values[key] as? Int

    private fun int(key: String, range: IntRange): Int? = int(key)?.takeIf { it in range }
    private fun dbl(key: String, range: ClosedFloatingPointRange<Double>): Double? = string(key)?.toDoubleOrNull()?.takeIf { it in range }

    val heightCm: Double? get() = dbl(HEIGHT, 120.0..230.0)
    val startWeightKg: Double? get() = dbl(START_WEIGHT, 30.0..250.0)
    val quotaKcal: Int? get() = int(QUOTA)
    val proteinG: Int? get() = int(PROTEIN)
    val fatG: Int? get() = int(FAT, 10..300)
    val carbsG: Int? get() = int(CARBS, 20..800)
    /** The goals string as saved (「80.0,75.0,68.0」 from the settings page). */
    val goalsRaw: String? get() = string(GOALS)
    /** `DayOfWeek` value 1..7 (v0.8.3 only). */
    val cheatDay: Int? get() = int(CHEAT_DAY, 1..7)
    val bedtime: String? get() = string(BEDTIME)?.take(20)?.takeIf { it.isNotBlank() }
    val marginPct: Int? get() = int(MARGIN, 0..50)
    val plannedMeal: String? get() = string(PLANNED_MEAL)?.takeIf { it.isNotBlank() }?.take(40)
    val evidenceMinutes: Int? get() = int(EVIDENCE_MIN, 5..240)
    val dayStartHour: Int? get() = int(DAY_START, 0..8)
    val company: String? get() = string(COMPANY)?.trim()?.takeIf { it.isNotEmpty() }?.take(12)
    val joinDate: String? get() = string(JOIN_DATE)?.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess }

    /** A slot time in minutes after midnight, or null when he never saved one. */
    fun slotMinute(key: String): Int? = int(key, 0..(if (key == T_NIGHT) 30 * 60 else 24 * 60 - 1))

    companion object {
        const val NAME = "dshm_coach"

        const val PROVIDER = "provider"
        const val MODEL_DEEPSEEK = "model_deepseek"
        const val MODEL_CLAUDE = "model_claude"
        const val COMPAT_URL = "compat_url"
        const val COMPAT_MODEL = "compat_model"
        const val OPEN_ON_COACH = "open_on_coach"
        const val HEIGHT = "height"
        const val START_WEIGHT = "start_weight"
        const val QUOTA = "quota"
        const val PROTEIN = "protein"
        const val FAT = "fat"
        const val CARBS = "carbs"
        const val GOALS = "goals"
        const val CHEAT_DAY = "cheat_day"
        const val BEDTIME = "bedtime"
        const val MARGIN = "margin"
        const val PLANNED_MEAL = "planned_meal"
        const val EVIDENCE_MIN = "evidence_min"
        const val DAY_START = "day_start"
        const val COMPANY = "company"
        const val JOIN_DATE = "join_date"
        const val T_LUNCH = "t_lunch"
        const val T_DINNER = "t_dinner"
        const val T_CHICKEN = "t_chicken"
        const val T_NIGHT = "t_night"
        const val COMPAT_DAY = "compat_day"
        const val COMPAT_COUNT = "compat_count"

        /** The 5a94d3c schedule's slots and their default minutes. */
        val SLOT_DEFAULTS: Map<String, Int> = linkedMapOf(T_LUNCH to 12 * 60, T_DINNER to 17 * 60 + 30, T_CHICKEN to 21 * 60, T_NIGHT to 23 * 60 + 30)

        /** The coach's old defaults, which the importer replaces with plan v3's. */
        val OLD_DEFAULT_GOALS = listOf(80.0, 75.0, 68.0)
        const val OLD_DEFAULT_QUOTA = 1700
        const val OLD_DEFAULT_PROTEIN = 140
        const val OLD_CHEAT_DAY = 5 // Friday

        /** 「80, 75, 68」 → [80, 75, 68] descending, as the coach parsed it; null when nothing valid. */
        fun parseGoals(s: String?): List<Double>? = s?.split(',', '，', ' ', '、')
            ?.mapNotNull { it.trim().toDoubleOrNull()?.takeIf { v -> v in 40.0..200.0 } }
            ?.distinct()?.sortedDescending()?.takeIf { it.isNotEmpty() }
    }
}
