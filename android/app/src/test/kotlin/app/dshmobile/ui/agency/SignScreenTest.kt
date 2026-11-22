package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.handlers.ReviewDesk
import app.agency.core.model.Baseline
import app.agency.core.model.Profile
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.sign.SignTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 簽到 and 簽退 on screen (T19): the sign-in opens the score picker (it goes once answered), the sign-out opens the bedtime
 * review sheet; its answers make the 檢討卡 and tomorrow's fix waits as a proposal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class SignScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("sign").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
        app.agency.send("刮好了")
        rule.waitForTag(SignTags.SIGN_IN, 30_000)
    }

    private fun signs() = runBlocking { ready().store.signs.between("0000-01-01", "9999-12-31") }

    @Test fun signInAsksTheScoreAndSignOutTheBedtimeReview() {
        open()
        rule.onNodeWithTag(SignTags.SIGN_IN).performScrollTo().performClick()
        rule.waitForTag(SignTags.PICKER, 10_000)
        val day = signs().single().day
        rule.onNodeWithTag(SignTags.SCORE + "3").performClick()
        rule.onNodeWithTag(SignTags.SLEEP + "1").performClick()
        rule.onNodeWithTag(SignTags.SEND).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.conditions.get(day) } != null }
        val c = runBlocking { ready().store.conditions.get(day) }!!
        assertEquals(4 to 3, c.score to c.sleep)
        assertFalse(c.ill)
        rule.waitForText("記下：今天 4 分。很好，照表走。")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(SignTags.PICKER).fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithTag(SignTags.SIGN_OUT).performScrollTo().performClick()
        rule.waitForTag(SignTags.REVIEW, 10_000)
        rule.onNodeWithTag(SignTags.GOOD).performTextInput("準時簽到")
        rule.onNodeWithTag(SignTags.FIX).performTextInput("早點睡")
        rule.onNodeWithTag(SignTags.REVIEW_SEND).performClick()
        rule.waitUntil(10_000) { signs().any { it.reviewFix == "早點睡" } }
        assertEquals("準時簽到", signs().single { it.reviewFix != null }.reviewGood)
        rule.waitForText("今天的檢討卡")
        // No key here: the template good night after the card.
        rule.waitUntil(10_000) { runBlocking { ready().store.messages.recent(3) }.any { it.text == ReviewDesk.GOOD_NIGHT } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(SignTags.REVIEW).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { ready().brain.pendingProposals() }.any { it.title == "明天要改" })
    }
}
