package app.dshmobile

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.dshmobile.ui.MainActivity
import app.dshmobile.wire.CameraActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The owner's first minutes on a real phone (the CI emulator), end to end through the real screens, the real wiring, the real
 * encrypted database and key vault, and a fake model server on 127.0.0.1 (see [E2eRunner]): onboarding with a fake key and an
 * invented document, home on stage 1, one sentence and 小葵's reply, and the 「下一件事」 button shown and tappable. Everything is
 * found by test tag or text and awaited with `waitUntil` (the work runs on background threads the Compose clock does not see).
 */
@OptIn(ExperimentalTestApi::class)
class JourneyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMs: Long = 30_000) = rule.waitUntil(timeoutMs) {
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tap(tag: String) {
        waitForTag(tag)
        rule.onNodeWithTag(tag).performClick()
    }

    private fun type(tag: String, text: String) {
        waitForTag(tag)
        rule.onNodeWithTag(tag).performTextInput(text)
    }

    /** One short line per node that has a tag or a text, for the failure message. */
    private fun outline(): String {
        val out = StringBuilder()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
            val cfg = n.config
            val tag = cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
            val text = cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("|") { it.text }
            if (tag != null || text != null) out.append("${n.boundsInRoot} ${tag.orEmpty()} ${text.orEmpty()}\n")
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), 0)
        return out.toString()
    }

    private fun overlayShown() = rule.onAllNodes(hasTestTag("overlay")).fetchSemanticsNodes().isNotEmpty()

    private fun cameras() = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED).flatMap {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).filterIsInstance<CameraActivity>()
    }

    private fun cameraShown() = cameras().isNotEmpty()

    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun onboardingThenAConversationThenTheNextThing() {
        val model = FakeModel.instance!!

        // 1. Onboarding: welcome, a fake key, an invented document, 小葵 reads it, confirm, names, permissions, day one.
        waitForTag("onboarding_WELCOME")
        tap("ob_next")
        waitForTag("onboarding_KEYS")
        type("key_claude", "sk-ant-invented-e2e-0001")
        tap("ob_next")
        waitForTag("onboarding_PASTE")
        type("document", "（測試用的文件）每天吃兩千大卡，早上七點簽到，晚上十一點半簽退。")
        tap("ob_next")
        waitForTag("onboarding_CONFIRM", 60_000)
        tap("ob_next")
        waitForTag("onboarding_NAME")
        type("call_name", "阿星")
        tap("ob_next")
        waitForTag("onboarding_PERMISSIONS")
        tap("ob_next")

        // 2. Home on stage 1.
        waitForTag("input", 60_000)
        rule.waitUntil(30_000) { count("第 1 階段") > 0 }
        assertTrue("the document went to the fake model with the fake key", model.seen.any { it.key == "sk-ant-invented-e2e-0001" })

        // 3. He says one sentence and sees 小葵's reply.
        val reply = "收到，先看看今天的第一堂課。"
        model.reply = reply
        type("input", "今天上什麼課")
        tap("send")
        rule.waitUntil(60_000) { count(reply) > 0 }
        assertTrue("his sentence is in the conversation", count("今天上什麼課") > 0)

        // 4. The next-thing button is shown and can be tapped (it sends its own words and 小葵 answers again).
        waitForTag("next_pill")
        val before = count(reply)
        rule.onNodeWithTag("next_pill").performClick()
        // Its words go to 小葵 (a second reply), or it opens the page that item belongs to (an overlay), or, for a meal, the in-app
        // camera screen. Either way the tap did what the plan item says.
        try {
            rule.waitUntil(60_000) { count(reply) > before || overlayShown() || cameraShown() }
        } catch (e: Throwable) {
            throw AssertionError("the next-thing tap did nothing visible ($e):\n" + outline() + " seen=" + model.seen, e)
        }
        runCatching { cameras().forEach { c -> rule.runOnUiThread { c.finish() } } }
    }
}
