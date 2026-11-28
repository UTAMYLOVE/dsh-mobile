package app.dshmobile.wire

import app.agency.core.base.TestClock
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.agency.core.store.MemStore
import app.dshmobile.data.Settings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cost meter: per-day and per-month totals in a store doc, and the monthly cap. Plain JVM. */
class SpendMeterTest {
    private val store = MemStore()
    private val settings = Settings(store)
    private val clock = TestClock()
    private val meter = SpendMeter(store, settings, { clock })

    /** Two US dollars on Sonnet (a million prompt tokens at two dollars). */
    private fun spendTwo() = meter.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 1_000_000, outputTokens = 0))

    @Test fun totalsPerDayAndPerMonth() {
        spendTwo()
        spendTwo()
        assertEquals(4.0, meter.todayUsd(), 1e-9)
        assertEquals(4.0, meter.monthUsd(), 1e-9)
        clock.plus(Duration.ofDays(1)) // 2030-01-08, same month
        assertEquals(0.0, meter.todayUsd(), 1e-9)
        assertEquals(4.0, meter.monthUsd(), 1e-9)
        spendTwo()
        assertEquals(2.0, meter.todayUsd(), 1e-9)
        assertEquals(6.0, meter.monthUsd(), 1e-9)
        clock.plus(Duration.ofDays(30)) // February
        assertEquals(0.0, meter.monthUsd(), 1e-9)
    }

    @Test fun theTotalsLiveInTheStoreSoABackupCarriesThem() {
        spendTwo()
        val again = SpendMeter(store, Settings(store), { clock })
        assertEquals(2.0, again.todayUsd(), 1e-9)
        assertTrue(store.doc(SpendMeter.DOC)!!.contains("2030-01-07"))
    }

    @Test fun deepseekIsPricedToo() {
        meter.record(Provider.DEEPSEEK, "deepseek-flash", Usage(inputTokens = 1_000_000, outputTokens = 0))
        assertTrue(meter.monthUsd() > 0.0)
    }

    @Test fun theCapBlocksOnceTheMonthReachesIt() {
        assertFalse(meter.isBlocked())
        settings.update { it.copy(monthlyCapUsd = SpendMeter.usd(96)) } // three US dollars
        spendTwo()
        assertFalse(meter.isBlocked())
        spendTwo()
        assertTrue(meter.isBlocked())
        clock.plus(Duration.ofDays(30)) // the month turned over
        assertFalse(meter.isBlocked())
        settings.update { it.copy(monthlyCapUsd = null) }
        assertFalse(meter.isBlocked())
    }

    @Test fun yuanAndDollarsConvertAtAFixedRate() {
        assertEquals(64, SpendMeter.yuan(2.0))
        assertEquals(0, SpendMeter.yuan(0.0))
        assertEquals(1.0, SpendMeter.usd(32), 1e-9)
    }
}
