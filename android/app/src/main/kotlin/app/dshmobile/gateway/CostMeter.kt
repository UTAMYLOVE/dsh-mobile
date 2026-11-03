package app.dshmobile.gateway

import android.content.SharedPreferences
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val _revision = MutableStateFlow(0L)

    /** Goes up by one on every [record] and every change of [alertUsd] or [hardCapUsd]: the overview recomputes on it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Warn once a day when today's spend goes above this many USD. */
    var alertUsd: Double
        get() = synchronized(this) { store.getString(KEY_ALERT, null)?.toDoubleOrNull() ?: DEFAULT_ALERT_USD }
        set(value) = synchronized(this) { store.edit().putString(KEY_ALERT, value.toString()).apply(); bump() }

    /** Refuse model requests once today's spend reaches this many USD; null means no cap. */
    var hardCapUsd: Double?
        get() = synchronized(this) { store.getString(KEY_HARD_CAP, null)?.toDoubleOrNull() }
        set(value) = synchronized(this) {
            val edit = store.edit()
            if (value == null) edit.remove(KEY_HARD_CAP) else edit.putString(KEY_HARD_CAP, value.toString())
            edit.apply()
            bump()
        }

    fun record(p: Provider, model: String, u: Usage) {
        val cost = prices.costUsd(p, model, u, clock.instant())
        synchronized(this) {
            val today = today()
            val month = today.substring(0, 7)
            val total = if (store.getString(KEY_DAY, null) == today) readTotal() else 0.0
            val own = todayUsd(p)
            val other = Provider.entries.filter { it != p }.associateWith { todayUsd(it) }
            val monthTotal = if (store.getString(KEY_MONTH, null) == month) readDouble(KEY_MONTH_USD) else 0.0
            val sameDay = store.getString(KEY_DAY, null) == today
            val edit = store.edit().putString(KEY_DAY, today).putString(KEY_TOTAL, (total + cost).toString())
                .putString(providerKey(p), (own + cost).toString())
                .putString(KEY_MONTH, month).putString(KEY_MONTH_USD, (monthTotal + cost).toString())
            // A provider that did not spend today is reset too, so a new day never keeps yesterday's split.
            other.forEach { (q, v) -> edit.putString(providerKey(q), v.toString()) }
            recordModel(edit, sameDay, model, u, cost)
            edit.apply()
            bump()
        }
    }

    /** Today's requests, tokens and spend per model, most expensive first (stage 2 §C.3). */
    fun todayByModel(): List<ModelSpend> = synchronized(this) {
        if (store.getString(KEY_DAY, null) != today()) return emptyList()
        decodeModels(store.getString(KEY_MODELS, null)).values.sortedByDescending { it.usd }
    }

    private fun recordModel(edit: SharedPreferences.Editor, sameDay: Boolean, model: String, u: Usage, cost: Double) {
        val all = if (sameDay) decodeModels(store.getString(KEY_MODELS, null)).toMutableMap() else mutableMapOf()
        val name = model.ifBlank { "?" }
        val old = all[name] ?: ModelSpend(name, 0, 0, 0, 0, 0.0)
        all[name] = old.copy(
            requests = old.requests + 1, inputTokens = old.inputTokens + u.inputTokens + u.cacheWriteTokens,
            outputTokens = old.outputTokens + u.outputTokens, cacheReadTokens = old.cacheReadTokens + u.cacheReadTokens, usd = old.usd + cost,
        )
        edit.putString(KEY_MODELS, encodeModels(all.values))
    }

    fun todayUsd(): Double = synchronized(this) {
        if (store.getString(KEY_DAY, null) == today()) readTotal() else 0.0
    }

    /** Today's spend on [p] alone; stores written before the split existed report 0 for each provider (the total is unchanged). */
    fun todayUsd(p: Provider): Double = synchronized(this) {
        if (store.getString(KEY_DAY, null) == today()) readDouble(providerKey(p)) else 0.0
    }

    /** This calendar month's spend (month boundary in the meter's zone, Asia/Taipei by default). */
    fun monthUsd(): Double = synchronized(this) {
        if (store.getString(KEY_MONTH, null) == today().substring(0, 7)) readDouble(KEY_MONTH_USD) else 0.0
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

    private fun readTotal(): Double = readDouble(KEY_TOTAL)

    private fun readDouble(key: String): Double = store.getString(key, null)?.toDoubleOrNull() ?: 0.0

    private fun providerKey(p: Provider) = when (p) {
        Provider.DEEPSEEK -> KEY_DAY_DEEPSEEK
        Provider.ANTHROPIC -> KEY_DAY_ANTHROPIC
    }

    private fun bump() {
        _revision.value += 1
    }

    internal companion object {
        const val DEFAULT_ALERT_USD = 5.0
        const val KEY_DAY = "day"
        const val KEY_TOTAL = "day_usd"
        const val KEY_DAY_DEEPSEEK = "day_usd_deepseek"
        const val KEY_DAY_ANTHROPIC = "day_usd_anthropic"
        const val KEY_MONTH = "month"
        const val KEY_MONTH_USD = "month_usd"
        const val KEY_ALERTED_DAY = "alerted_day"
        const val KEY_ALERT = "alert_usd"
        const val KEY_HARD_CAP = "hard_cap_usd"
        const val KEY_MODELS = "day_models"

        /** One model per line: `name<TAB>requests<TAB>input<TAB>output<TAB>cacheRead<TAB>usd` (model ids have no tabs or newlines). */
        fun encodeModels(all: Collection<ModelSpend>): String = all.joinToString("\n") {
            listOf(it.model.replace('\t', ' ').replace('\n', ' '), it.requests, it.inputTokens, it.outputTokens, it.cacheReadTokens, it.usd).joinToString("\t")
        }

        fun decodeModels(text: String?): Map<String, ModelSpend> = text.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 6) null else runCatching {
                ModelSpend(f[0], f[1].toLong(), f[2].toLong(), f[3].toLong(), f[4].toLong(), f[5].toDouble())
            }.getOrNull()
        }.associateBy { it.model }
    }
}

/** One model's share of today: [inputTokens] are uncached input plus cache writes. */
data class ModelSpend(
    val model: String,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val usd: Double,
) {
    /** Share of prompt tokens read from the cache, 0..1; null without input. */
    val cacheHit: Double? get() = (inputTokens + cacheReadTokens).takeIf { it > 0 }?.let { cacheReadTokens.toDouble() / it }
}
