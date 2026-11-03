package app.dshmobile.gateway

import android.content.SharedPreferences
import java.time.Clock
import java.time.ZoneId

/**
 * Today's estimated spend (day boundary in [zone]) plus the user's alert threshold and hard cap, all kept in [store].
 * Every method is synchronized, so concurrent streams cannot lose a record.
 */
class CostMeter(
    private val store: SharedPreferences,
    private val prices: PriceTable,
    private val zone: ZoneId = ZoneId.of("Asia/Taipei"),
    private val clock: Clock,
) {
    /** Warn once a day when today's spend goes above this many USD. */
    var alertUsd: Double
        get() = synchronized(this) { store.getString(KEY_ALERT, null)?.toDoubleOrNull() ?: DEFAULT_ALERT_USD }
        set(value) = synchronized(this) { store.edit().putString(KEY_ALERT, value.toString()).apply() }

    /** Refuse model requests once today's spend reaches this many USD; null means no cap. */
    var hardCapUsd: Double?
        get() = synchronized(this) { store.getString(KEY_HARD_CAP, null)?.toDoubleOrNull() }
        set(value) = synchronized(this) {
            val edit = store.edit()
            if (value == null) edit.remove(KEY_HARD_CAP) else edit.putString(KEY_HARD_CAP, value.toString())
            edit.apply()
        }

    fun record(p: Provider, model: String, u: Usage) {
        val cost = prices.costUsd(p, model, u, clock.instant())
        synchronized(this) {
            val today = today()
            val total = if (store.getString(KEY_DAY, null) == today) readTotal() else 0.0
            store.edit().putString(KEY_DAY, today).putString(KEY_TOTAL, (total + cost).toString()).apply()
        }
    }

    fun todayUsd(): Double = synchronized(this) {
        if (store.getString(KEY_DAY, null) == today()) readTotal() else 0.0
    }

    /** True the first time today's spend is above [alertUsd]; false for the rest of that day. */
    fun consumeAlert(): Boolean = synchronized(this) {
        val today = today()
        if (store.getString(KEY_ALERTED_DAY, null) == today || todayUsd() <= alertUsd) return false
        store.edit().putString(KEY_ALERTED_DAY, today).apply()
        true
    }

    fun isBlocked(): Boolean = synchronized(this) {
        val cap = hardCapUsd
        cap != null && todayUsd() >= cap
    }

    private fun today(): String = clock.instant().atZone(zone).toLocalDate().toString()

    private fun readTotal(): Double = store.getString(KEY_TOTAL, null)?.toDoubleOrNull() ?: 0.0

    private companion object {
        const val DEFAULT_ALERT_USD = 5.0
        const val KEY_DAY = "day"
        const val KEY_TOTAL = "day_usd"
        const val KEY_ALERTED_DAY = "alerted_day"
        const val KEY_ALERT = "alert_usd"
        const val KEY_HARD_CAP = "hard_cap_usd"
    }
}
