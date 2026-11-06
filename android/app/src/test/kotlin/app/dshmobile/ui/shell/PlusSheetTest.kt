package app.dshmobile.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dshmobile.chat.ComposeMode
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.5.0, mockup 2: the ＋ sheet at a large font scale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h873dp-port", fontScale = 1.3f)
class PlusSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sheetShowsAttachmentsFiveModesAndAssistants() {
        val picked = mutableListOf<String>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                PlusSheet(
                    ComposeMode.NORMAL, listOf(AssistantChip("p1", "台股研究員")),
                    onImage = { picked += "image" }, onCamera = { picked += "camera" }, onFile = { picked += "file" },
                    onMode = { picked += it.name }, onAssistant = { picked += "a:$it" }, onNewAssistant = { picked += "new" },
                )
            }
        }
        for (tag in listOf(Tags.ATTACH_IMAGE, Tags.ATTACH_CAMERA, Tags.ATTACH_FILE)) rule.onNodeWithTag(tag).assertIsDisplayed()
        ComposeMode.entries.forEach { rule.onNodeWithTag(Tags.MODE + it.name).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithTag(Tags.ATTACH_CAMERA).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.MODE + "RESEARCH").performScrollTo().performClick()
        rule.onNodeWithTag(Tags.ASSISTANT + "p1").performScrollTo().performClick()
        assertEquals(listOf("camera", "RESEARCH", "a:p1"), picked)
    }
}
