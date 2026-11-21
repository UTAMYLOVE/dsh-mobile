package app.dshmobile.ui.coach

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dshmobile.coach.CoachRig
import app.dshmobile.coach.CoachRig.Companion.item
import app.dshmobile.coach.CoachRig.Companion.parsed
import app.dshmobile.coach.FakeVault
import app.dshmobile.coach.ModelReply
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.Tags
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class CoachPageTest {
    @get:Rule val rule = createComposeRule()
    private val rig = CoachRig()
    private val nav = ShellNav().apply { coachHome = true; coach = true }

    @After fun tearDown() = rig.close()

    private fun show() = rule.setContent {
        var text by androidx.compose.runtime.remember { mutableStateOf("") }
        val ui by rig.engine.ui.collectAsState()
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            FogShell(
                // dsh is not ready: the coach works without it.
                status = MainUiState.Loading,
                connection = null,
                nav = nav,
                composer = ComposerUi(
                    text = text, onText = { text = it }, enabled = true, canSend = text.isNotBlank() && !ui.busy, running = ui.busy,
                    onSend = { val t = text; text = ""; rig.engine.send(t) }, onStop = {},
                ),
                onRetry = {},
                onExportErrorLog = {},
                coach = { CoachPage(rig.engine) },
                coachSettings = { CoachSettingsPage(rig.settings, FakeVault(), rig.store) },
            ) { Text("聊天") }
        }
    }

    @Test fun theCoachOpensWithoutDshAndRecordsAMeal() {
        rig.model.parse = { ModelReply.Ok(parsed("meal", item("給力盒子南加州烤雞腿"))) }
        show()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertExists()
        rule.onNodeWithTag(Tags.COACH_STRIP).assertTextContains("今天剩 1700 大卡", substring = true)
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).performTextInput("我吃了給力盒子南加州烤雞腿")
        rule.onNodeWithTag(Tags.SEND).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rig.store.messages.value.size == 2 }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.COACH_STRIP).assertTextContains("今天剩 1071 大卡", substring = true)
        val reply = rig.store.messages.value.last()
        rule.onNodeWithTag(Tags.COACH_MSG + reply.id).assertTextContains("629 大卡", substring = true)
    }

    @Test fun freeChatAndBackAgain() {
        show()
        rule.onNodeWithTag(Tags.BAR_FREE_CHAT).performClick()
        rule.waitForIdle()
        assertFalse(nav.coach)
        rule.onNodeWithTag(Tags.BAR_COACH).performClick()
        rule.waitForIdle()
        assertTrue(nav.coach)
    }

    @Test fun theCoachScreensShowNoLatinLetters() {
        rig.model.parse = { ModelReply.Ok(parsed("weight", weight = 79.2)) }
        show()
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).performTextInput("今天體重 79.2")
        rule.onNodeWithTag(Tags.SEND).performClick()
        rule.waitUntil(5_000) { rig.store.messages.value.size == 2 }
        rule.waitForIdle()
        assertNoLatin(rule.onNodeWithTag(Tags.COACH_PAGE).fetchSemanticsNode())
        rule.onNodeWithTag(Tags.COACH_SETTINGS_OPEN).performClick()
        rule.waitForIdle()
        assertNoLatin(rule.onNodeWithTag(Tags.COACH_SETTINGS).fetchSemanticsNode())
    }

    private fun assertNoLatin(root: SemanticsNode) {
        val texts = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { texts += it.text }
            n.children.forEach(::walk)
        }
        walk(root)
        val latin = texts.filter { Regex("[A-Za-z]").containsMatchIn(it) }
        assertTrue("Latin letters on the coach screens: $latin", latin.isEmpty())
    }
}
