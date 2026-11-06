package app.dshmobile.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dshmobile.notify.Notifier
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class LockTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lockedPageAsksTheSystemFirst() {
        var asked = 0
        rule.setContent { FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) { LockedPage(onUnlock = { asked++ }) } }
        rule.onNodeWithText("這個對話已鎖定").assertExists()
        rule.onNodeWithTag(LockTags.UNLOCK).performClick()
        assertEquals(1, asked)
    }

    @Test fun backgroundRelocks() {
        UnlockedChats.relock()
        UnlockedChats.add("s1")
        assertTrue("s1" in UnlockedChats.ids.value)
        UnlockedChats.relock()
        assertTrue(UnlockedChats.ids.value.isEmpty())
    }

    @Test fun lockedNoticesSayNothing() {
        assertEquals("已鎖定的對話", Notifier.LOCKED_TITLE)
        assertTrue(Notifier.LOCKED_BODY.contains("解鎖"))
    }
}
