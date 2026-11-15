package app.dshmobile.chat

import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.gateway.Usage
import app.dshmobile.remote.SessionSummary
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3: 本對話花費 counted only the lead's loaded replies, while 本對話 tok counted the whole team. With helpers doing the work, or a
 * reply retried after an overload (billed, but not a loaded reply), the shown cost was a small part of the spend.
 */
class TeamCostTest {
    private val prices = PriceTable()
    private val now = Instant.parse("2026-11-07T12:00:00Z") // Saturday: DeepSeek off-peak all day
    private val earlier = Instant.parse("2026-11-07T11:00:00Z")

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun session(input: Long, output: Long, cacheRead: Long, steps: Long, provider: String, model: String) = mapOf(
        "tokenUsage" to j("""{"uncachedInputTokens":$input,"outputTokens":$output,"cacheReadTokens":$cacheRead,"cacheWriteTokens":0}"""),
        "sessionStats" to j("""{"turns":1,"steps":$steps}"""),
        "modelSelection" to j("""{"lastUsed":{"provider":"$provider","model":"$model"},"next":{"provider":"$provider","model":"$model"}}"""),
    )

    private fun summary(id: String, parent: String?) = SessionSummary(id, parent, null, null, null, null, null, null, null, null, null)

    private fun reply(provider: String, model: String, input: Long, output: Long, cacheRead: Long) = ReplyStats(
        provider, model, input, output, cacheRead, null, 0, null, 1, endedAtMs = earlier.toEpochMilli(),
    )

    // The lead talked on DeepSeek first, then switched to Opus 5.5; one Opus attempt failed and was retried.
    private val first = reply("deepseek-official", "deepseek-flash", 100_000, 10_000, 400_000)
    private val second = reply("anthropic", "claude-opus-5-5", 20_000, 5_000, 300_000)
    private val attempt = Usage(inputTokens = 20_000, outputTokens = 2_000, cacheReadTokens = 300_000)
    private val loaded = listOf(first, second)

    private val tree = SessionTreeState(
        summaries = listOf(summary("lead", null), summary("k1", "lead"), summary("k2", "lead"), summary("k3", "k1"), summary("other", null))
            .associateBy { it.id },
        projections = mapOf(
            "lead" to session(
                input = first.inputTokens + second.inputTokens + attempt.inputTokens,
                output = first.outputTokens + second.outputTokens + attempt.outputTokens,
                cacheRead = first.cacheReadTokens + second.cacheReadTokens + attempt.cacheReadTokens,
                steps = 3, provider = "anthropic", model = "claude-opus-5-5",
            ),
            "k1" to session(50_000, 8_000, 200_000, 4, "deepseek-official", "deepseek-v4-pro"),
            "k2" to session(30_000, 3_000, 120_000, 4, "anthropic", "claude-haiku-5-5"),
            "k3" to session(10_000, 1_000, 40_000, 2, "anthropic", "claude-sonnet-5-5"),
            "other" to session(9_000_000, 9_000_000, 0, 1, "anthropic", "claude-fable-5-1"),
        ),
    )

    private fun cost(p: Provider, model: String, u: Usage, requests: Long = 1) = prices.costUsd(p, model, u, now, requests = requests)

    @Test fun theConversationCostIncludesItsHelpersAndRetriedAttempts() {
        val loadedCost = loaded.sumOf { replyCostUsd(prices, it, now)!! }
        val expected = loadedCost +
            cost(Provider.ANTHROPIC, "claude-opus-5-5", attempt) +
            cost(Provider.DEEPSEEK, "deepseek-v4-pro", Usage(50_000, 8_000, 200_000), 4) +
            cost(Provider.ANTHROPIC, "claude-haiku-5-5", Usage(30_000, 3_000, 120_000), 4) +
            cost(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(10_000, 1_000, 40_000), 2)
        val team = teamCostUsd(tree, "lead", prices, loaded, now)!!
        assertEquals(expected, team, 1e-12)
        assertTrue("helpers and the retried attempt add to the loaded replies", team > loadedCost)
        // Another conversation's spend is not this one's.
        assertTrue(team < 1.0)
    }

    @Test fun aLeadThatSwitchedModelsIsNotPricedWhollyAtTheLastModel() {
        val own = ownCostUsd(tree, "lead", prices, loaded, now)!!
        val allAtOpus = cost(Provider.ANTHROPIC, "claude-opus-5-5", Usage(140_000, 17_000, 1_000_000), 3)
        assertEquals(loaded.sumOf { replyCostUsd(prices, it, now)!! } + cost(Provider.ANTHROPIC, "claude-opus-5-5", attempt), own, 1e-12)
        assertTrue("the DeepSeek reply stays at DeepSeek's price", own < allAtOpus)
    }

    @Test fun withoutDshsCountsItIsTheLoadedRepliesAlone() {
        val bare = SessionTreeState(summaries = mapOf("lead" to summary("lead", null)))
        assertEquals(loaded.sumOf { replyCostUsd(prices, it, now)!! }, teamCostUsd(bare, "lead", prices, loaded, now)!!, 1e-12)
        assertNull(teamCostUsd(bare, "lead", prices, emptyList(), now))
    }

    @Test fun moreLoadedThanDshCountedNeverGoesNegative() {
        // dsh's count can lag the loaded replies for a moment; the remainder is clamped, not subtracted.
        val lagging = tree.copy(projections = tree.projections + ("lead" to session(0, 0, 0, 0, "anthropic", "claude-opus-5-5")))
        assertEquals(loaded.sumOf { replyCostUsd(prices, it, now)!! }, ownCostUsd(lagging, "lead", prices, loaded, now)!!, 1e-12)
    }
}
