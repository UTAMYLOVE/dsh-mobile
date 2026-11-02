package app.dshmobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PriceTableTest {
    private val prices = PriceTable()
    private val million = Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadTokens = 1_000_000)

    private fun flash(at: String, model: String = "deepseek-flash") =
        prices.costUsd(Provider.DEEPSEEK, model, million, Instant.parse(at))

    @Test fun deepseekPeakBoundariesAreUtcWeekdays() {
        val off = 0.15 + 0.60 + 0.003
        val peak = 0.30 + 1.20 + 0.006
        // 2026-11-02 is a Monday.
        assertEquals(off, flash("2026-11-02T00:59:00Z"), 1e-9)
        assertEquals(peak, flash("2026-11-02T01:00:00Z"), 1e-9)
        assertEquals(peak, flash("2026-11-02T03:59:59Z"), 1e-9)
        assertEquals(off, flash("2026-11-02T04:00:00Z"), 1e-9)
        assertEquals(off, flash("2026-11-02T05:59:59Z"), 1e-9)
        assertEquals(peak, flash("2026-11-02T06:00:00Z"), 1e-9)
        assertEquals(peak, flash("2026-11-02T09:59:59Z"), 1e-9)
        assertEquals(off, flash("2026-11-02T10:00:00Z"), 1e-9)
        assertEquals(peak, flash("2026-11-06T02:00:00Z"), 1e-9) // Friday
        assertEquals(off, flash("2026-11-07T02:00:00Z"), 1e-9) // Saturday
        assertEquals(off, flash("2026-11-08T07:00:00Z"), 1e-9) // Sunday
    }

    @Test fun deepseekProPrices() {
        val off = prices.costUsd(Provider.DEEPSEEK, "deepseek-v4-pro", million, Instant.parse("2026-11-07T02:00:00Z"))
        val peak = prices.costUsd(Provider.DEEPSEEK, "deepseek-v4-pro", million, Instant.parse("2026-11-02T02:00:00Z"))
        assertEquals(0.022 + 0.66 + 1.98, off, 1e-9)
        assertEquals(0.044 + 1.32 + 3.96, peak, 1e-9)
    }

    @Test fun legacyDeepseekNamesPriceAsFlash() {
        val expected = flash("2026-11-02T02:00:00Z")
        assertEquals(expected, flash("2026-11-02T02:00:00Z", "deepseek-v4-flash"), 1e-12)
        assertEquals(expected, flash("2026-11-02T02:00:00Z", "deepseek-v4-flash-vision-exp"), 1e-12)
    }

    @Test fun deepseekCacheWriteBillsAsMissAndSearchesAdd() {
        val u = Usage(inputTokens = 1_000_000, outputTokens = 0, cacheWriteTokens = 1_000_000, webSearchRequests = 4)
        val cost = prices.costUsd(Provider.DEEPSEEK, "deepseek-flash", u, Instant.parse("2026-11-07T02:00:00Z"))
        assertEquals(0.15 + 0.15 + 4 * 0.00075, cost, 1e-9)
    }

    @Test fun anthropicModelPrices() {
        val u = Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadTokens = 1_000_000, cacheWriteTokens = 1_000_000)
        val at = Instant.parse("2026-11-02T02:00:00Z")
        assertEquals(10.0 + 50 + 0.25 + 12.50, prices.costUsd(Provider.ANTHROPIC, "claude-fable-5-1", u, at), 1e-9)
        assertEquals(4.0 + 20 + 0.20 + 5, prices.costUsd(Provider.ANTHROPIC, "claude-opus-5-5", u, at), 1e-9)
        assertEquals(2.0 + 10 + 0.10 + 2.50, prices.costUsd(Provider.ANTHROPIC, "claude-sonnet-5-5", u, at), 1e-9)
        assertEquals(1.0 + 5 + 0.10 + 1.25, prices.costUsd(Provider.ANTHROPIC, "claude-haiku-4-5", u, at), 1e-9)
    }

    @Test fun anthropicIgnoresDeepseekPeakHours() {
        val u = Usage(inputTokens = 1_000_000, outputTokens = 0)
        val peak = prices.costUsd(Provider.ANTHROPIC, "claude-sonnet-5-5", u, Instant.parse("2026-11-02T02:00:00Z"))
        val off = prices.costUsd(Provider.ANTHROPIC, "claude-sonnet-5-5", u, Instant.parse("2026-11-07T02:00:00Z"))
        assertEquals(off, peak, 1e-12)
    }

    @Test fun haiku55SwitchesTierAbove100kTotalInput() {
        val at = Instant.parse("2026-11-07T02:00:00Z")
        val atLimit = Usage(inputTokens = 60_000, outputTokens = 1_000_000, cacheReadTokens = 20_000, cacheWriteTokens = 20_000)
        val over = atLimit.copy(cacheWriteTokens = 20_001)
        val low = prices.costUsd(Provider.ANTHROPIC, "claude-haiku-5-5", atLimit, at)
        val high = prices.costUsd(Provider.ANTHROPIC, "claude-haiku-5-5", over, at)
        assertEquals((60_000 * 0.10 + 20_000 * 0.01 + 20_000 * 0.125 + 1_000_000 * 0.50) / 1e6, low, 1e-9)
        assertEquals((60_000 * 0.50 + 20_000 * 0.05 + 20_001 * 0.625 + 1_000_000 * 2.50) / 1e6, high, 1e-9)
    }

    @Test fun anthropicSearchCostsOneCentEach() {
        val u = Usage(inputTokens = 0, outputTokens = 0, webSearchRequests = 3)
        assertEquals(0.03, prices.costUsd(Provider.ANTHROPIC, "claude-sonnet-5-5", u, Instant.EPOCH), 1e-12)
    }

    @Test fun unknownModelsUseConservativePrices() {
        val at = Instant.parse("2026-11-07T02:00:00Z") // off-peak: conservative DeepSeek price is still the pro peak price
        assertEquals(0.044 + 1.32 + 3.96, prices.costUsd(Provider.DEEPSEEK, "mystery", million, at), 1e-9)
        val u = Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadTokens = 1_000_000, cacheWriteTokens = 1_000_000)
        assertEquals(10.0 + 50 + 0.25 + 12.50, prices.costUsd(Provider.ANTHROPIC, "mystery", u, at), 1e-9)
    }

    @Test fun dateSuffixIsStrippedBeforeLookup() {
        val u = Usage(inputTokens = 1_000_000, outputTokens = 0)
        val at = Instant.EPOCH
        assertEquals(
            prices.costUsd(Provider.ANTHROPIC, "claude-haiku-4-5", u, at),
            prices.costUsd(Provider.ANTHROPIC, "claude-haiku-4-5-20251001", u, at),
            1e-12,
        )
    }
}
