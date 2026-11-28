package app.dshmobile.wire

import app.agency.core.base.Clock
import app.agency.core.metering.PriceTable
import app.agency.core.metering.Provider
import app.agency.core.metering.SpendGuard
import app.agency.core.metering.Usage
import app.agency.core.metering.UsageSink
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import app.dshmobile.data.Settings
import kotlinx.serialization.Serializable

/**
 * What the model providers have cost so far, kept in the small doc [DOC] (`spend`) of the store, so the encrypted backup carries
 * it: `days` (calendar date in the clock's zone, last [KEEP_DAYS] days) and `months` (`yyyy-MM`, last [KEEP_MONTHS]) map to US
 * dollars. Every paid response is priced with [PriceTable] and added here ([UsageSink]); the monthly cap of the settings is
 * enforced through [SpendGuard], which `CallChecks` asks before any socket opens (cap reached: the call fails with `Fail.CAP`).
 * Estimates only, and on the high side. Never holds a key, a prompt or a reply.
 */
class SpendMeter(
    private val store: Store,
    private val settings: Settings,
    private val clock: () -> Clock,
    private val prices: PriceTable = PriceTable(),
) : UsageSink, SpendGuard {

    @Serializable
    private data class Ledger(val days: Map<String, Double> = emptyMap(), val months: Map<String, Double> = emptyMap())

    private fun ledger(): Ledger = store.readDoc<Ledger>(DOC) ?: Ledger()

    private fun day(): String = clock().let { it.now().atZone(it.zone).toLocalDate().toString() }

    override fun record(p: Provider, model: String, u: Usage) {
        val c = clock()
        val cost = prices.costUsd(p, model, u, c.now())
        if (cost <= 0.0 || cost.isNaN()) return
        synchronized(this) {
            val d = day()
            val m = d.substring(0, 7)
            val l = ledger()
            val days = (l.days + (d to (l.days[d] ?: 0.0) + cost)).toSortedMap().entries.toList().takeLast(KEEP_DAYS).associate { it.key to it.value }
            val months = (l.months + (m to (l.months[m] ?: 0.0) + cost)).toSortedMap().entries.toList().takeLast(KEEP_MONTHS).associate { it.key to it.value }
            store.writeDoc(DOC, Ledger(days, months))
        }
    }

    fun todayUsd(): Double = synchronized(this) { ledger().days[day()] ?: 0.0 }

    fun monthUsd(): Double = synchronized(this) { ledger().months[day().substring(0, 7)] ?: 0.0 }

    /** The cap in US dollars (null = none), as set in the settings. */
    fun capUsd(): Double? = settings.get().monthlyCapUsd?.takeIf { it > 0 }

    override fun isBlocked(): Boolean {
        val cap = capUsd() ?: return false
        return monthUsd() >= cap
    }

    companion object {
        const val DOC = "spend"
        const val KEEP_DAYS = 62
        const val KEEP_MONTHS = 24

        /** The settings page speaks New Taiwan dollars; the cap is kept in US dollars. A fixed round rate, on purpose. */
        const val YUAN_PER_USD = 32.0

        fun yuan(usd: Double): Int = Math.ceil(usd * YUAN_PER_USD - 1e-9).toInt().coerceAtLeast(0)

        fun usd(yuan: Int): Double = yuan / YUAN_PER_USD
    }
}
