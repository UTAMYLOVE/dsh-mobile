package app.dshmobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.ui.fog.AvatarCrop
import app.dshmobile.ui.fog.AvatarGuess
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookController
import app.dshmobile.ui.fog.LookStore
import app.dshmobile.ui.fog.avatarSquare
import app.dshmobile.ui.theme.SakuraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LookSettingsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun show(): LookController {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        rule.setContent { SakuraTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LookSettings(c) } } }
        return c
    }

    @Test fun choosingTheKawaiiStyleShowsItsSwitchesAndAppliesAtOnce() {
        val c = show()
        rule.onNodeWithTag(LookTags.CHECK + "petals").assertDoesNotExist()
        rule.onNodeWithTag(LookTags.STYLE + "1").performClick()
        assertEquals(BackdropStyle.KAWAII, c.look.value.style)
        rule.onNodeWithTag(LookTags.CHECK + "hearts").performScrollTo().performClick()
        assertFalse(c.look.value.hearts)
    }

    @Test fun pictureSlidersShowOnlyWithAPicture() {
        show()
        rule.onNodeWithTag(LookTags.SLIDER + "six").assertDoesNotExist()
        rule.onNodeWithTag(LookTags.REMOVE).assertDoesNotExist()
        rule.onNodeWithTag(LookTags.AVATAR).assertDoesNotExist()
        rule.onNodeWithText("調整頭像").assertDoesNotExist()
        rule.onNodeWithText("選圖片").assertExists()
    }

    // ---- 調整頭像 ----

    /** An invented tall picture (a blush to aqua gradient, 180 × 320) picked before the settings show. */
    private fun showWithPicture(restoration: StateRestorationTester? = null): LookController {
        val bitmap = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 320) for (x in 0 until 180) setPixel(x, y, android.graphics.Color.rgb(254 - y / 4, 223, 225 + y / 40))
        }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        runBlocking { assertTrue(c.setPicture { ByteArrayInputStream(bytes) }) }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            SakuraTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LookSettings(c) } }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        return c
    }

    private fun openAvatar() {
        rule.onNodeWithTag(LookTags.AVATAR).performScrollTo().performClick()
        rule.onNodeWithTag(LookTags.AVATAR_DIALOG).assertExists()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(LookTags.AVATAR_PICTURE).fetchSemanticsNodes().isNotEmpty() }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun withAPictureOneAvatarRowShowsTheCurrentAvatarAndSaysItIsAutomatic() {
        val c = showWithPicture()
        rule.onNodeWithTag(LookTags.AVATAR).performScrollTo().assertExists()
        rule.onNodeWithText("調整頭像").assertExists()
        rule.onNodeWithText("自動找臉").assertExists()
        rule.onNodeWithTag(LookTags.AVATAR_PREVIEW, useUnmergedTree = true).assertExists()
        assertNull(c.look.value.avatar)
        // The settings that were there stay, in their places.
        rule.onNodeWithTag(LookTags.ALIGN + "0").assertExists()
        rule.onNodeWithTag(LookTags.SLIDER + "six").assertExists()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun movingTheFrameAndDoneKeepsTheUsersOwnSquare() {
        val c = showWithPicture()
        openAvatar()
        val guess = AvatarGuess.peek(c.pictureFile, c.look.value.picture)!!
        rule.onNodeWithTag(LookTags.AVATAR_LIVE, useUnmergedTree = true).assertExists()
        // Drag the frame to the right: the square follows the finger, and nothing is saved until 完成.
        rule.onNodeWithTag(LookTags.AVATAR_PICTURE).performTouchInput { swipe(center, center + Offset(width * .4f, 0f), 300) }
        assertNull(c.look.value.avatar)
        rule.onNodeWithTag(LookTags.AVATAR_DONE).performClick()
        val mine = c.look.value.avatar!!
        assertTrue("moved right: $guess → $mine", mine.x > guess.x + .1f)
        assertEquals(guess.y, mine.y, .02f)
        // Never past the picture's edge.
        val sq = avatarSquare(180, 320, mine)
        assertTrue(sq.left >= 0 && sq.left + sq.side <= 180 && sq.top >= 0 && sq.top + sq.side <= 320)
        rule.onNodeWithTag(LookTags.AVATAR_DIALOG).assertDoesNotExist()
        rule.onNodeWithText("已照你調的位置").assertExists()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun theSizeSliderZoomsAndAutomaticGoesBackToTheGuess() {
        val c = showWithPicture()
        openAvatar()
        rule.onNodeWithTag(LookTags.AVATAR_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(.3f) }
        rule.onNodeWithTag(LookTags.AVATAR_DONE).performClick()
        assertEquals(.3f, c.look.value.avatar!!.size, .01f)
        // 自動 drops the user's square at once (the frame shows the guess again); 完成 then keeps 自動.
        openAvatar()
        rule.onNodeWithTag(LookTags.AVATAR_AUTO).performClick()
        assertNull(c.look.value.avatar)
        rule.onNodeWithTag(LookTags.AVATAR_DONE).performClick()
        assertNull(c.look.value.avatar)
        rule.onNodeWithText("自動找臉").assertExists()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test fun onAPhoneTurnedSidewaysTheSizeAndTheLiveAvatarCanStillBeReached() {
        val c = showWithPicture()
        openAvatar()
        rule.onNodeWithTag(LookTags.AVATAR_SIZE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(LookTags.AVATAR_LIVE, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(LookTags.AVATAR_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
        rule.onNodeWithTag(LookTags.AVATAR_DONE).assertIsDisplayed().performClick()
        assertEquals(.4f, c.look.value.avatar!!.size, .01f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aHalfMovedFrameSurvivesTheScreenBeingRebuilt() {
        val restoration = StateRestorationTester(rule)
        val c = showWithPicture(restoration)
        openAvatar()
        rule.onNodeWithTag(LookTags.AVATAR_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(.3f) }
        restoration.emulateSavedInstanceStateRestore()
        // Still open, still the user's own frame, and nothing saved until 完成.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(LookTags.AVATAR_PICTURE).fetchSemanticsNodes().isNotEmpty() }
        assertNull(c.look.value.avatar)
        rule.onNodeWithTag(LookTags.AVATAR_DONE).performClick()
        assertEquals(.3f, c.look.value.avatar!!.size, .01f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun restoringDefaultsPutsTheAvatarBackToAutomatic() {
        val c = showWithPicture()
        c.update { it.copy(avatar = AvatarCrop(.3f, .3f, .3f)) }
        rule.onNodeWithTag(LookTags.RESTORE).performScrollTo().performClick()
        assertNull(c.look.value.avatar)
        assertTrue(c.look.value.hasPicture)
    }

    @Test fun theIntroIsSwitchedOnNamedAndPreviewed() {
        val c = show()
        rule.onNodeWithTag(LookTags.SWITCH + "intro").performScrollTo().performClick()
        assertTrue(c.look.value.intro.enabled)
        rule.onNodeWithTag(LookTags.FIELD + "intro-name").performScrollTo().performTextInput("小雨")
        assertEquals("小雨", c.look.value.intro.name)
        val tick = c.introTick.value
        rule.onNodeWithTag(LookTags.PREVIEW).performScrollTo().performClick()
        assertEquals(tick + 1, c.introTick.value)
    }

    @Test fun theIntroStylePickerListsEveryLookAndChoosesOne() {
        val c = show()
        // 櫻紋陣 is chosen until another look is picked; each row has its name and one plain line.
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "sigil").performScrollTo().assertIsSelected()
        for (style in IntroStyle.entries) rule.onNodeWithTag(LookTags.INTRO_STYLE + style.id).performScrollTo()
        rule.onNodeWithText("星屑迎接").assertExists()
        rule.onNodeWithText("一筆描").assertExists()
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "tunnel").performScrollTo().performClick()
        assertEquals(IntroStyle.TUNNEL, c.look.value.intro.style)
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "tunnel").assertIsSelected()
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "sigil").assertIsNotSelected()
        // The pick is right above 預覽, which plays it.
        val tick = c.introTick.value
        rule.onNodeWithTag(LookTags.PREVIEW).performScrollTo().performClick()
        assertEquals(tick + 1, c.introTick.value)
    }

    @Test fun theDshCommunityLooksAreListedAfterTheDrawnOnesAndChosenTheSameWay() {
        val c = show()
        for ((id, name) in listOf(
            "web-sakura" to "櫻花動漫風（dsh 社群作品）",
            "web-neon" to "粉色霓虹敘事（dsh 社群作品）",
            "web-wave" to "光點聚形波浪（dsh 社群作品）",
            "web-access" to "科技檔案授權（dsh 社群作品）",
        )) {
            rule.onNodeWithTag(LookTags.INTRO_STYLE + id).performScrollTo().assertIsNotSelected()
            rule.onNodeWithText(name).assertExists()
        }
        rule.onNodeWithText("照原作播放：粉白畫面，你的圖當背景和頭像，歡迎詞一個字一個字浮上來。").assertExists()
        rule.onNodeWithText("照原作播放：黑幕亮起成米白檔案頁，一步步核對你的身份，再歡迎你；最短約 6 秒。").assertExists()
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "web-access").performScrollTo().performClick()
        assertEquals(IntroStyle.WEB_ACCESS, c.look.value.intro.style)
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "web-access").assertIsSelected()
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "web-wave").performScrollTo().performClick()
        assertEquals(IntroStyle.WEB_WAVE, c.look.value.intro.style)
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "web-wave").assertIsSelected()
        rule.onNodeWithTag(LookTags.INTRO_STYLE + "one-stroke").assertIsNotSelected()
    }

    @Test fun restoreDefaultsBringsEverythingBack() {
        val c = show()
        c.update { it.copy(style = BackdropStyle.KAWAII, amount = 5, intro = it.intro.copy(enabled = true, style = IntroStyle.ONE_STROKE)) }
        rule.onNodeWithTag(LookTags.RESTORE).performScrollTo().performClick()
        assertEquals(Look(), c.look.value)
    }
}
