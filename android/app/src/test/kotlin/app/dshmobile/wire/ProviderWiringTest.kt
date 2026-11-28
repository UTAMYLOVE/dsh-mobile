package app.dshmobile.wire

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.metering.CallBudget
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.dshmobile.data.ModelTier
import app.dshmobile.data.ProviderMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which model answers what, by mode, tier and photos, against two fake servers; keys come from the vault at call time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderWiringTest {
    private val rig = WireRig()

    @After fun tearDown() = rig.close()

    private fun ask(photos: Boolean = false, purpose: Purpose = Purpose.CHAT, small: Boolean = false): ChatResult = runBlocking {
        val req = ChatRequest(
            system = "你是小葵", turns = listOf(Turn.User("今天好嗎", if (photos) listOf(byteArrayOf(1, 2, 3)) else emptyList())),
            purpose = purpose, maxTokens = 200,
        )
        (if (small) rig.wire.providers.small else rig.wire.providers.chat).chat(req)
    }

    private fun mode(m: ProviderMode, t: ModelTier = ModelTier.BALANCED) {
        rig.graph.settings.update { it.copy(providerMode = m, tier = t) }
    }

    private fun keys(claude: Boolean = true, deepseek: Boolean = true) {
        if (claude) rig.graph.keys.set(Provider.ANTHROPIC, WireRig.CLAUDE_KEY)
        if (deepseek) rig.graph.keys.set(Provider.DEEPSEEK, WireRig.DEEPSEEK_KEY)
    }

    @Test fun allClaudeSendsChatAtTheTierAndSmallToTheSmallModel() {
        keys()
        mode(ProviderMode.ALL_CLAUDE, ModelTier.SMART)
        assertEquals("好。", (ask() as ChatResult.Ok).text)
        assertTrue(rig.claudeSeen.single().body.contains("\"claude-opus-5-5\""))
        assertEquals(WireRig.CLAUDE_KEY, rig.claudeSeen.single().keyHeader)
        mode(ProviderMode.ALL_CLAUDE, ModelTier.SAVING)
        ask()
        assertTrue(rig.claudeSeen.last().body.contains("\"claude-haiku-5-5\""))
        ask(small = true, purpose = Purpose.JUDGE)
        assertTrue(rig.claudeSeen.last().body.contains("\"claude-haiku-5-5\""))
        mode(ProviderMode.ALL_CLAUDE, ModelTier.BALANCED)
        ask()
        assertTrue(rig.claudeSeen.last().body.contains("\"claude-sonnet-5-5\""))
        assertTrue(rig.deepseekSeen.isEmpty())
    }

    @Test fun chatDeepseekSendsTextToDeepseekAtTheTier() {
        keys()
        mode(ProviderMode.CHAT_DEEPSEEK, ModelTier.SMART)
        assertEquals("好。", (ask() as ChatResult.Ok).text)
        assertTrue(rig.deepseekSeen.single().body.contains("\"deepseek-v4-pro\""))
        assertEquals("Bearer ${WireRig.DEEPSEEK_KEY}", rig.deepseekSeen.single().keyHeader)
        mode(ProviderMode.CHAT_DEEPSEEK, ModelTier.BALANCED)
        ask()
        assertTrue(rig.deepseekSeen.last().body.contains("\"deepseek-flash\""))
        ask(small = true, purpose = Purpose.JUDGE)
        assertTrue(rig.deepseekSeen.last().body.contains("\"deepseek-flash\""))
        assertTrue(rig.claudeSeen.isEmpty())
    }

    @Test fun aTurnWithPhotosGoesToClaudeWhenTheDeepseekChatModelCannotSee() {
        keys()
        mode(ProviderMode.CHAT_DEEPSEEK, ModelTier.SMART)
        ask(photos = true)
        assertEquals(1, rig.claudeSeen.size)
        assertTrue(rig.deepseekSeen.isEmpty())
        assertTrue(rig.claudeSeen.single().body.contains("\"image\""))
    }

    @Test fun aTurnWithPhotosStaysOnTheDeepseekModelThatCanSee() {
        keys()
        mode(ProviderMode.CHAT_DEEPSEEK, ModelTier.BALANCED)
        ask(photos = true)
        assertEquals(1, rig.deepseekSeen.size)
        assertTrue(rig.claudeSeen.isEmpty())
        assertTrue(rig.deepseekSeen.single().body.contains("image_url"))
    }

    @Test fun withoutAClaudeKeyPhotosOnTheBigModelAreRefusedNotDropped() {
        keys(claude = false)
        mode(ProviderMode.CHAT_DEEPSEEK, ModelTier.SMART)
        val r = ask(photos = true)
        assertEquals(Fail.NO_VISION, (r as ChatResult.Failed).fail)
        assertTrue(rig.deepseekSeen.isEmpty() && rig.claudeSeen.isEmpty())
    }

    @Test fun searchExistsOnlyWithADeepseekKey() {
        keys(deepseek = false)
        assertNull(rig.wire.providers.search())
        assertNull(rig.wire.providers.providers().search)
        keys(claude = false)
        assertNotNull(rig.wire.providers.search())
    }

    @Test fun keysAreReadAtCallTime() {
        mode(ProviderMode.ALL_CLAUDE)
        assertEquals(Fail.NO_KEY, (ask() as ChatResult.Failed).fail)
        keys(deepseek = false)
        assertTrue(ask() is ChatResult.Ok)
        rig.graph.keys.clear(Provider.ANTHROPIC)
        assertEquals(Fail.NO_KEY, (ask() as ChatResult.Failed).fail)
        assertEquals(1, rig.claudeSeen.size)
    }

    @Test fun theMonthlyCapRefusesCallsBeforeAnythingIsSent() {
        keys()
        mode(ProviderMode.ALL_CLAUDE)
        rig.graph.settings.update { it.copy(monthlyCapUsd = SpendMeter.usd(32)) } // one US dollar
        assertTrue(ask() is ChatResult.Ok)
        rig.wire.spend.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 1_000_000, outputTokens = 0)) // two US dollars
        assertEquals(Fail.CAP, (ask() as ChatResult.Failed).fail)
        assertEquals(1, rig.claudeSeen.size)
        rig.graph.settings.update { it.copy(monthlyCapUsd = null) }
        assertTrue(ask() is ChatResult.Ok)
    }

    @Test fun tooManyCallsInAMinuteAreRefusedAndTold() {
        keys()
        mode(ProviderMode.ALL_CLAUDE)
        var told = 0
        val wiring = ProviderWiring(
            rig.graph.transport, rig.graph.keys, rig.graph.settings, rig.wire.spend, { rig.graph.claudeBase }, { rig.graph.deepseekBase },
            budget = CallBudget(maxPerMinute = 1, clock = java.time.Clock.systemUTC()), onRateLimited = { told++ },
        )
        val req = ChatRequest("你是小葵", listOf(Turn.User("嗨")), Purpose.CHAT, 100)
        runBlocking {
            assertTrue(wiring.chat.chat(req) is ChatResult.Ok)
            assertEquals(Fail.RATE, (wiring.chat.chat(req) as ChatResult.Failed).fail)
        }
        assertEquals(1, told)
        assertEquals(1, rig.claudeSeen.size)
    }
}
