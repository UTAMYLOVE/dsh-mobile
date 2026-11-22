package app.agency.core.rules

import app.agency.core.model.AuditLog
import app.agency.core.model.CompanyRules
import app.agency.core.store.AgencyStore
import java.time.LocalDate

/**
 * 公司規章 (plan v3 §6): nobody changes them in a conversation, not him, not a role, not a link (arch 1.5). Only the 公司規章
 * page in 設定 does, with two confirmations, inside [SafetyBounds], keeping the history and an `audit_log` row.
 */
object RulesBook {
    /** The fixed reply to any attempt in chat. */
    const val LOCKED = "公司規章不能在對話裡改，要改請到設定的公司規章。"

    /** 休假餐 is closed until this day (owner, 2026-11-22: 「12/31 前關閉」); from the day after it is back, one a week. */
    val CHEAT_CLOSED_THROUGH: LocalDate = LocalDate.parse("2026-12-31")

    fun cheatOpen(day: LocalDate): Boolean = day.isAfter(CHEAT_CLOSED_THROUGH)

    const val CHEAT_CLOSED = "休假餐 2026 年 12 月 31 日前暫停，2027 年 1 月 1 日起恢復一週一餐。"

    /** The rules by name (bare numbers are not enough: 「晚餐換成 800 的便當」 is a meal, not a rule). */
    private val TERMS = Regex(
        "公司規章|規章|規定|(?:每天|每日|一天)的?(?:熱量|大卡|卡路里)|熱量(?:上限|額度|目標|規定)|單餐(?:的)?上限|最低熱量|" +
            "熱量底線|底線|休假餐(?:的)?(?:次數|額度)|休假餐.{0,4}(?:兩餐|兩次|多一)|零卡(?:飲料)?(?:的)?(?:額度|瓶數|上限)|" +
            "目標體重|體重目標|蛋白質(?:的)?(?:目標|要求|規定)",
    )
    private val CHANGE = Regex(
        "改|調|提高|降低|增加|減少|放寬|取消|加到|減到|降到|拉高|變成|換成|多給|多一|少一點|鬆一點|不要算|不算了|拿掉|廢除|兩餐|兩次",
    )

    /** He (or anyone) asks in chat to change a company rule. */
    fun isChangeAttempt(text: String): Boolean = TERMS.containsMatchIn(text) && CHANGE.containsMatchIn(text)

    /**
     * Writes [next] from the 公司規章 page after his two confirmations: the bounds first (nothing is written when one fails),
     * then the next version with its history row and an audit row naming what changed (numbers only).
     */
    suspend fun edit(store: AgencyStore, next: CompanyRules, at: Long, newId: () -> String): Result {
        val bad = SafetyBounds.check(next)
        if (bad.isNotEmpty()) return Result.Refused(bad)
        val before = store.rules.current() ?: CompanyRules()
        val changed = diff(before, next)
        if (changed.isEmpty()) return Result.Unchanged
        return store.transaction {
            val saved = store.rules.put(next, at)
            store.journal.audit(AuditLog(newId(), at, "rules_edit", "v${saved.version}: " + changed.joinToString("; ")))
            Result.Saved(saved)
        }
    }

    fun diff(a: CompanyRules, b: CompanyRules): List<String> = buildList {
        fun <T> f(name: String, x: T, y: T) { if (x != y) add("$name $x→$y") }
        f("quota_kcal", a.quotaKcal, b.quotaKcal)
        f("protein_g", a.proteinG, b.proteinG)
        f("fat_g", a.fatG, b.fatG)
        f("carbs_g", a.carbsG, b.carbsG)
        f("floor_kcal", a.floorKcal, b.floorKcal)
        f("meal_cap_kcal", a.mealCapKcal, b.mealCapKcal)
        f("cheat_meals_per_week", a.cheatMealsPerWeek, b.cheatMealsPerWeek)
        f("zero_cal_per_week", a.zeroCalPerWeek, b.zeroCalPerWeek)
        f("margin_pct", a.marginPct, b.marginPct)
        f("goals_kg", a.goalsKg, b.goalsKg)
        f("banned", a.banned, b.banned)
    }

    sealed interface Result {
        data class Saved(val rules: CompanyRules) : Result
        data class Refused(val why: List<RuleViolation>) : Result
        data object Unchanged : Result
    }
}
