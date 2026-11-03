package app.dshmobile.gateway

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * USD per million tokens (docs/dsh-findings.md, Providers, 2026-11-02).
 * Estimates only: holidays are not modelled, which errs on the high side.
 */
class PriceTable {
    private class DeepseekRates(val cacheHit: Double, val miss: Double, val output: Double)

    private class AnthropicRates(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    fun costUsd(p: Provider, model: String, u: Usage, at: Instant): Double = when (p) {
        Provider.DEEPSEEK -> deepseekCost(model, u, at)
        Provider.ANTHROPIC -> anthropicCost(model, u)
    }

    private fun deepseekCost(model: String, u: Usage, at: Instant): Double {
        val peak = isDeepseekPeak(at)
        val key = stripDate(model).let { legacyDeepseek[it] ?: it }
        val rates = (if (peak) deepseekPeak else deepseekOffPeak)[key] ?: deepseekPeak.getValue(CONSERVATIVE_DEEPSEEK)
        val tokens = u.cacheReadTokens * rates.cacheHit +
            (u.inputTokens + u.cacheWriteTokens) * rates.miss +
            u.outputTokens * rates.output
        return tokens / MILLION + u.webSearchRequests * DEEPSEEK_SEARCH_USD
    }

    private fun anthropicCost(model: String, u: Usage): Double {
        val key = stripDate(model)
        val totalInput = u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens
        val rates = when {
            key == HAIKU_5_5 && totalInput > HAIKU_5_5_TIER_LIMIT -> haiku55LongContext
            else -> anthropic[key] ?: anthropic.getValue(CONSERVATIVE_ANTHROPIC)
        }
        val tokens = u.inputTokens * rates.input +
            u.outputTokens * rates.output +
            u.cacheReadTokens * rates.cacheRead +
            u.cacheWriteTokens * rates.cacheWrite
        return tokens / MILLION + u.webSearchRequests * ANTHROPIC_SEARCH_USD
    }

    /** Peak: UTC Monday to Friday, 01:00-04:00 and 06:00-10:00 (start inclusive, end exclusive). */
    private fun isDeepseekPeak(at: Instant): Boolean {
        val utc = at.atZone(ZoneOffset.UTC)
        if (utc.dayOfWeek == DayOfWeek.SATURDAY || utc.dayOfWeek == DayOfWeek.SUNDAY) return false
        return utc.hour in 1..3 || utc.hour in 6..9
    }

    private fun stripDate(model: String) = model.replace(dateSuffix, "")

    private companion object {
        const val MILLION = 1_000_000.0
        const val DEEPSEEK_SEARCH_USD = 0.00075
        const val ANTHROPIC_SEARCH_USD = 0.01
        const val HAIKU_5_5 = "claude-haiku-5-5"
        const val HAIKU_5_5_TIER_LIMIT = 100_000L
        const val CONSERVATIVE_DEEPSEEK = "deepseek-v4-pro"
        const val CONSERVATIVE_ANTHROPIC = "claude-fable-5-1"

        val dateSuffix = Regex("-\\d{8}$")

        val legacyDeepseek = mapOf(
            "deepseek-v4-flash" to "deepseek-flash",
            "deepseek-v4-flash-vision-exp" to "deepseek-flash",
        )
        val deepseekOffPeak = mapOf(
            "deepseek-flash" to DeepseekRates(0.003, 0.15, 0.60),
            "deepseek-v4-pro" to DeepseekRates(0.022, 0.66, 1.98),
        )
        val deepseekPeak = mapOf(
            "deepseek-flash" to DeepseekRates(0.006, 0.30, 1.20),
            "deepseek-v4-pro" to DeepseekRates(0.044, 1.32, 3.96),
        )
        val anthropic = mapOf(
            "claude-fable-5-1" to AnthropicRates(10.0, 50.0, 0.25, 12.50),
            "claude-opus-5-5" to AnthropicRates(4.0, 20.0, 0.20, 5.0),
            "claude-sonnet-5-5" to AnthropicRates(2.0, 10.0, 0.20, 2.50),
            HAIKU_5_5 to AnthropicRates(0.10, 0.50, 0.01, 0.125),
            "claude-haiku-4-5" to AnthropicRates(1.0, 5.0, 0.10, 1.25),
        )
        val haiku55LongContext = AnthropicRates(0.50, 2.50, 0.05, 0.625)
    }
}
