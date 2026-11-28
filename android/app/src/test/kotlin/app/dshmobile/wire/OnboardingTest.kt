package app.dshmobile.wire

import app.agency.core.base.Days
import app.agency.core.base.J
import app.agency.core.metering.Provider
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.stage.Stages
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** First start from nothing to day 1, with a scripted model answering the document read. Real wiring, invented document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingTest {
    private val rig = WireRig()

    @After fun tearDown() = rig.close()

    private val ob get() = rig.wire.uiGraph.onboarding

    private fun answerWithRules() {
        val rules = J.encodeToString(Rules.serializer(), Fixtures.rules())
        rig.claudeSays = { body -> if (body.contains("callName")) """{"rules":$rules,"handbook":"他喜歡簡單直接的說法。"}""" else "好。" }
    }

    @Test fun fromNothingToDayOne() {
        answerWithRules()
        assertFalse(rig.wire.company.today().ready) // no rules, no key: the screens show onboarding
        assertTrue(runBlocking { ob.saveKeys(WireRig.CLAUDE_KEY, "") })
        assertTrue(rig.graph.keys.has(Provider.ANTHROPIC))
        assertFalse(rig.graph.keys.has(Provider.DEEPSEEK))

        val out = runBlocking { ob.readDocument("（測試用的文件）每天兩千大卡，早上七點簽到……") }
        assertNull(out.failure)
        assertTrue(out.groups.any { it.title == "作息" && it.lines.any { l -> l.contains("阿星") } })
        assertTrue(out.groups.any { it.title == "飲食" && it.lines.any { l -> l.contains("2100") } })
        assertNull("nothing is saved before he confirms", rig.graph.store.doc("rules"))
        assertEquals(WireRig.CLAUDE_KEY, rig.claudeSeen.single().keyHeader)

        runBlocking { ob.confirm("小星", "#ff8fb1", "阿星") }
        val rules = rig.wire.company.rules()
        assertEquals("小星", rules.stageName)
        assertEquals("#FF8FB1", rules.memberColor)
        assertEquals("阿星", rules.callName)
        assertEquals(2100, rules.diet.kcal)
        assertTrue(rig.wire.company.today().ready)

        ob.finish()
        assertEquals(Days.today(rig.clock).toString(), Stages.get(rig.graph.store)!!.joinedOn)
        assertEquals("第 1 階段 · 第 1 天", rig.wire.company.today().stageLabel)
        val end = System.currentTimeMillis() + 5_000
        while (rig.graph.alarms.armedAt() == null && System.currentTimeMillis() < end) Thread.sleep(25)
        assertNotNull("the first alarm is armed after day 1 starts", rig.graph.alarms.armedAt())
    }

    @Test fun aDocumentThatCannotBeReadComesBackAsAFailureNotACrash() {
        rig.claudeSays = { "這不是 JSON" }
        runBlocking { ob.saveKeys(WireRig.CLAUDE_KEY, "") }
        val out = runBlocking { ob.readDocument("（測試用的文件）") }
        assertNotNull(out.failure)
        assertTrue(out.groups.isEmpty())
        // He can still go on: confirming without a read keeps the names on blank rules instead of failing.
        runBlocking { ob.confirm("小星", "#FF8FB1", "阿星") }
        assertEquals("阿星", rig.wire.company.rules().callName)
    }

    @Test fun keysMustBeUsableAndABlankDeepseekKeyMeansNone() {
        assertFalse(runBlocking { ob.saveKeys("   ", "") })
        assertFalse(runBlocking { ob.saveKeys("密碼不是金鑰", "") })
        assertFalse(rig.graph.keys.has(Provider.ANTHROPIC))
        assertTrue(runBlocking { ob.saveKeys(WireRig.CLAUDE_KEY, WireRig.DEEPSEEK_KEY) })
        assertTrue(rig.graph.keys.has(Provider.DEEPSEEK))
        assertTrue(runBlocking { ob.saveKeys(WireRig.CLAUDE_KEY, "") })
        assertFalse(rig.graph.keys.has(Provider.DEEPSEEK))
    }

    @Test fun anOlderVersionsKeyLetsTheKeysStepBeLeftBlank() {
        assertFalse(ob.keysSaved())
        rig.graph.keys.set(Provider.ANTHROPIC, WireRig.CLAUDE_KEY)
        assertTrue(ob.keysSaved())
        assertTrue(runBlocking { ob.saveKeys("", "") })
        assertEquals(WireRig.CLAUDE_KEY, rig.graph.keys.get(Provider.ANTHROPIC))
    }
}
