package app.dshmobile.coach

import android.content.SharedPreferences
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoachProvider { DEEPSEEK, CLAUDE, COMPAT }

/**
 * The coach's choices and the owner's body numbers, in SharedPreferences [PREFS]. Only the owner changes them (教練設定); the
 * coach engine has no path that writes here. Every read is range-checked: a bad value falls back to the default.
 */
class CoachSettings(private val prefs: SharedPreferences) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bump() { _revision.value += 1 }
    private fun putStr(k: String, v: String?) { prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply(); bump() }
    private fun putInt(k: String, v: Int) { prefs.edit().putInt(k, v).apply(); bump() }
    private fun putBool(k: String, v: Boolean) { prefs.edit().putBoolean(k, v).apply(); bump() }
    private fun dbl(k: String, def: Double, range: ClosedFloatingPointRange<Double>): Double =
        prefs.getString(k, null)?.toDoubleOrNull()?.takeIf { it in range } ?: def
    private fun int(k: String, def: Int, range: IntRange): Int = prefs.getInt(k, def).takeIf { it in range } ?: def

    var provider: CoachProvider
        get() = runCatching { CoachProvider.valueOf(prefs.getString(K_PROVIDER, null).orEmpty()) }.getOrDefault(CoachProvider.DEEPSEEK)
        set(v) = putStr(K_PROVIDER, v.name)

    var deepseekModel: String
        get() = prefs.getString(K_MODEL_DS, null)?.takeIf { it in DEEPSEEK_MODELS.map { m -> m.id } } ?: DEEPSEEK_MODELS[0].id
        set(v) = putStr(K_MODEL_DS, v)

    var claudeModel: String
        get() = prefs.getString(K_MODEL_CLAUDE, null)?.takeIf { it in CLAUDE_MODELS.map { m -> m.id } } ?: CLAUDE_MODELS[0].id
        set(v) = putStr(K_MODEL_CLAUDE, v)

    /** The compatible service's base address (https only; checked again before each call). */
    var compatUrl: String
        get() = prefs.getString(K_COMPAT_URL, null).orEmpty()
        set(v) = putStr(K_COMPAT_URL, v.trim().takeIf { it.isNotEmpty() })

    var compatModel: String
        get() = prefs.getString(K_COMPAT_MODEL, null).orEmpty()
        set(v) = putStr(K_COMPAT_MODEL, v.trim().takeIf { it.isNotEmpty() })

    var openOnCoach: Boolean
        get() = prefs.getBoolean(K_OPEN, true)
        set(v) = putBool(K_OPEN, v)

    var profile: CoachProfile
        get() {
            val d = CoachProfile()
            return CoachProfile(
                heightCm = dbl(K_HEIGHT, d.heightCm, 120.0..230.0),
                startWeightKg = dbl(K_START, d.startWeightKg, 30.0..250.0),
                // 公司規章: quota, protein and the 休假餐 day are fixed; a value an older version saved is ignored (never deleted).
                quotaKcal = AgencyRules.QUOTA_KCAL,
                proteinG = AgencyRules.PROTEIN_G,
                fatG = int(K_FAT, d.fatG, 10..300),
                carbsG = int(K_CARBS, d.carbsG, 20..800),
                goals = parseGoals(prefs.getString(K_GOALS, null)) ?: d.goals,
                cheatDay = AgencyRules.CHEAT_DAY,
                bedtime = prefs.getString(K_BEDTIME, null).orEmpty().take(20),
                marginPct = int(K_MARGIN, d.marginPct, 0..50),
                plannedMeal = prefs.getString(K_PLANNED, null)?.takeIf { it.isNotBlank() }?.take(40) ?: d.plannedMeal,
                evidenceMinutes = int(K_EVIDENCE, d.evidenceMinutes, 5..240),
                dayStartHour = int(K_DAY_START, d.dayStartHour, 0..8),
            )
        }
        set(p) {
            prefs.edit()
                .putString(K_HEIGHT, p.heightCm.coerceIn(120.0, 230.0).toString())
                .putString(K_START, p.startWeightKg.coerceIn(30.0, 250.0).toString())
                .putInt(K_FAT, p.fatG.coerceIn(10, 300))
                .putInt(K_CARBS, p.carbsG.coerceIn(20, 800))
                .putString(K_GOALS, p.goals.joinToString(","))
                .putString(K_BEDTIME, p.bedtime.take(20))
                .putInt(K_MARGIN, p.marginPct.coerceIn(0, 50))
                .putString(K_PLANNED, p.plannedMeal.take(40))
                .putInt(K_EVIDENCE, p.evidenceMinutes.coerceIn(5, 240))
                .putInt(K_DAY_START, p.dayStartHour.coerceIn(0, 8))
                .apply()
            bump()
        }

    /** The company's name on the 練習生證 and in the bar (公司 settings). */
    var companyName: String
        get() = prefs.getString(K_COMPANY, null)?.trim()?.takeIf { it.isNotEmpty() }?.take(12) ?: DEFAULT_COMPANY
        set(v) = putStr(K_COMPANY, v.trim().take(12).takeIf { it.isNotEmpty() })

    /** 入社日: day 1 of 入社第 N 天. */
    var joinDate: java.time.LocalDate
        get() = prefs.getString(K_JOIN, null)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: DEFAULT_JOIN
        set(v) = putStr(K_JOIN, v.toString())

    /** The 經紀人's schedule times, minutes after midnight. */
    var slotTimes: SlotTimes
        get() {
            val d = SlotTimes()
            return SlotTimes(
                lunch = int(K_T_LUNCH, d.lunch, 0..(24 * 60 - 1)),
                dinner = int(K_T_DINNER, d.dinner, 0..(24 * 60 - 1)),
                chicken = int(K_T_CHICKEN, d.chicken, 0..(24 * 60 - 1)),
                night = int(K_T_NIGHT, d.night, 0..(30 * 60)),
            )
        }
        set(t) {
            prefs.edit().putInt(K_T_LUNCH, t.lunch).putInt(K_T_DINNER, t.dinner).putInt(K_T_CHICKEN, t.chicken).putInt(K_T_NIGHT, t.night).apply()
            bump()
        }

    data class ModelChoice(val id: String, val zh: String)

    companion object {
        const val PREFS = "dshm_coach"
        /** The compatible service's key, in KeyVault's named slots. */
        const val COMPAT_KEY = "COACH_COMPAT"

        val DEEPSEEK_MODELS = listOf(ModelChoice("deepseek-flash", "深度求索（快）"), ModelChoice("deepseek-v4-pro", "深度求索（強）"))
        val CLAUDE_MODELS = listOf(
            ModelChoice("claude-sonnet-5-5", "克勞德（平衡）"),
            ModelChoice("claude-opus-5-5", "克勞德（最強）"),
            ModelChoice("claude-haiku-5-5", "克勞德（最省）"),
        )

        /** 「80, 75, 68」 → [80, 75, 68] descending; null when nothing valid. */
        fun parseGoals(s: String?): List<Double>? = s?.split(',', '，', ' ', '、')
            ?.mapNotNull { it.trim().toDoubleOrNull()?.takeIf { v -> v in 40.0..200.0 } }
            ?.distinct()?.sortedDescending()?.takeIf { it.isNotEmpty() }

        private const val K_PROVIDER = "provider"
        private const val K_MODEL_DS = "model_deepseek"
        private const val K_MODEL_CLAUDE = "model_claude"
        private const val K_COMPAT_URL = "compat_url"
        private const val K_COMPAT_MODEL = "compat_model"
        private const val K_OPEN = "open_on_coach"
        private const val K_HEIGHT = "height"
        private const val K_START = "start_weight"
        private const val K_QUOTA = "quota"
        private const val K_PROTEIN = "protein"
        private const val K_FAT = "fat"
        private const val K_CARBS = "carbs"
        private const val K_GOALS = "goals"
        private const val K_CHEAT = "cheat_day"
        private const val K_BEDTIME = "bedtime"
        private const val K_MARGIN = "margin"
        private const val K_PLANNED = "planned_meal"
        private const val K_EVIDENCE = "evidence_min"
        private const val K_DAY_START = "day_start"
        private const val K_COMPANY = "company"
        private const val K_JOIN = "join_date"
        private const val K_T_LUNCH = "t_lunch"
        private const val K_T_DINNER = "t_dinner"
        private const val K_T_CHICKEN = "t_chicken"
        private const val K_T_NIGHT = "t_night"
        const val DEFAULT_COMPANY = "小雨娛樂"
        val DEFAULT_JOIN: java.time.LocalDate = java.time.LocalDate.of(2026, 11, 21)
        internal const val K_COMPAT_COUNT_DAY = "compat_day"
        internal const val K_COMPAT_COUNT = "compat_count"
    }

    /** Ruling Q8: the compatible service has no price, so it is capped at [COMPAT_DAILY_CAP] requests a day instead. */
    @Synchronized fun takeCompatRequest(today: String): Boolean {
        val count = if (prefs.getString(K_COMPAT_COUNT_DAY, null) == today) prefs.getInt(K_COMPAT_COUNT, 0) else 0
        if (count >= COMPAT_DAILY_CAP) return false
        prefs.edit().putString(K_COMPAT_COUNT_DAY, today).putInt(K_COMPAT_COUNT, count + 1).apply()
        return true
    }
}

const val COMPAT_DAILY_CAP = 300
