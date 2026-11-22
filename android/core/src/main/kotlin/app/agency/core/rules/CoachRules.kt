package app.agency.core.rules

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** The owner's fixed numbers and formulas (spec §4). The model never computes any of these; it is handed the results. */
object CoachRules {
    const val FLOOR_KCAL = 1200
    const val DEFICIT_KCAL = 500
    const val KCAL_PER_KG = 7000
    const val DAYS_PER_MONTH = 30
    const val BMI_OBESE = 27
    const val BMI_HEALTHY = 24
    const val QUOTA_MAX = 4000

    fun oneDecimal(num: Long, den: Long): String =
        BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 1, RoundingMode.HALF_UP).toPlainString()

    /** 「每天都多 X 大卡」: kilograms gained over a month. */
    fun monthKg(x: Int): String = oneDecimal(x.toLong() * DAYS_PER_MONTH, KCAL_PER_KG.toLong())

    /** 延後天數 = X ÷ 500, one decimal. */
    fun delayDays(x: Int): String = oneDecimal(x.toLong(), DEFICIT_KCAL.toLong())

    /** The deficit left when every day is X over. */
    fun deficitLeft(x: Int): Int = DEFICIT_KCAL - x

    private fun sq(cm: Double) = (cm / 100) * (cm / 100)

    fun bmi(kg: Double, cm: Double): String = BigDecimal(kg / sq(cm)).setScale(1, RoundingMode.HALF_UP).toPlainString()

    fun obeseLineKg(cm: Double): BigDecimal = BigDecimal(BMI_OBESE * sq(cm)).setScale(1, RoundingMode.HALF_UP)

    fun healthyLineKg(cm: Double): BigDecimal = BigDecimal(BMI_HEALTHY * sq(cm)).setScale(1, RoundingMode.HALF_UP)

    enum class Zone(val zh: String) { OBESE("肥胖區"), OVER("過重區"), HEALTHY("健康區") }

    fun zone(kg: Double, cm: Double): Zone {
        val w = BigDecimal(kg).setScale(1, RoundingMode.HALF_UP)
        return when {
            w >= obeseLineKg(cm) -> Zone.OBESE
            w <= healthyLineKg(cm) -> Zone.HEALTHY
            else -> Zone.OVER
        }
    }

    /** The first step goal strictly below [currentKg]; null when every step is reached (重新評估). */
    fun nextGoal(goals: List<Double>, currentKg: Double): Double? = goals.sortedDescending().firstOrNull { it < currentKg - 1e-9 }

    fun quotaClamped(q: Int): Int = q.coerceIn(FLOOR_KCAL, QUOTA_MAX)

    fun remainingKcal(quota: Int, eaten: Int): Int = quota - eaten

    fun remainingProtein(target: Int, eaten: Double): Int = maxOf(0, floor(target - eaten).toInt())

    fun mustStillEat(eaten: Int): Int = maxOf(0, FLOOR_KCAL - eaten)

    /** The coach day: a 02:00 late-night meal belongs to the evening before ([dayStartHour] o'clock starts a new day). */
    fun coachDay(at: Instant, zone: ZoneId, dayStartHour: Int): LocalDate = at.atZone(zone).minusHours(dayStartHour.toLong()).toLocalDate()

    fun weekdayZh(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "一"
        DayOfWeek.TUESDAY -> "二"
        DayOfWeek.WEDNESDAY -> "三"
        DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"
        DayOfWeek.SATURDAY -> "六"
        DayOfWeek.SUNDAY -> "日"
    }

    /** 75.0 → "75", 79.25 → "79.3". */
    fun kg(v: Double): String {
        val b = BigDecimal(v).setScale(1, RoundingMode.HALF_UP)
        return if (b.stripTrailingZeros().scale() <= 0) b.setScale(0).toPlainString() else b.toPlainString()
    }

    /**
     * The fixed four-line answer when the day is [x] kcal over (spec §5). [x] is the day's total over. [goalKg] null: no step
     * goal left. [currentKg] decides whether the 肥胖區 clause applies.
     */
    fun overQuota(x: Int, quota: Int, goalKg: Double?, currentKg: Double, heightCm: Double): List<String> {
        val d = deficitLeft(x)
        val tail = when {
            d <= 0 -> "赤字變成 $d，等於在變胖。"
            d < 250 -> "赤字只剩 $d，等於不會瘦。"
            else -> "赤字只剩 $d。"
        }
        val days = delayDays(x)
        val obese = zone(currentKg, heightCm) == Zone.OBESE
        val goalPart = goalKg?.let { "${kg(it)} 公斤延後 $days 天。" }
        val body = when {
            obese && goalPart != null -> "身體：留在肥胖區的時間多 $days 天，$goalPart"
            obese -> "身體：留在肥胖區的時間多 $days 天。"
            goalPart != null -> "身體：$goalPart"
            else -> "身體：目標延後 $days 天。"
        }
        return listOf(
            "超過 $x 大卡。",
            "身材：每天都這樣，一個月多 ${monthKg(x)} 公斤，$tail",
            body,
            "明天回到 $quota。不准少吃來補。",
        )
    }
}
