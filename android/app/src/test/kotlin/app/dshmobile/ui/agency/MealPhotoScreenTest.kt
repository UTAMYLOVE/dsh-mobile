package app.dshmobile.ui.agency

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Message
import app.agency.core.model.MessageRefs
import app.agency.core.model.PendingLabel
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.rules.CoachRules
import app.agency.core.time.AgencyClock
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.ui.agency.meal.LabelInput
import app.dshmobile.ui.agency.meal.MealTags
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 每餐拍照 (T15) on screen: 拍這餐 opens the system camera, RESULT_OK seals the photo onto the meal being reported (or waits
 * for his words), RESULT_CANCELED leaves it 「未證實」; the thumbnail is decrypted in memory and a broken one shows a
 * placeholder; deleting needs a confirm; the label form checks its numbers; a meal reminder opens the camera. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealPhotoScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("meal-photo").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready
    private fun today(): String = CoachRules.coachDay(Instant.now(), AgencyClock.TAIPEI, 5).toString()
    private fun meals() = runBlocking { ready().store.meals.onDay(today()) }

    /** The company open with 入社建檔 walked through (the quick buttons are there). */
    private fun open(intent: Intent? = null) {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = if (intent == null) ActivityScenario.launch(AgencyActivity::class.java) else ActivityScenario.launch(intent)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
        // Any line reloads the screen.
        app.agency.send("刮好了")
        rule.waitForTag(MealTags.PHOTO)
    }

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(240, 210, 180)) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Taps 拍這餐: the camera intent it started and the file it writes into. */
    private fun tapPhoto(): Pair<Intent, File> {
        // The button is disabled while the brain answers: wait until it is idle.
        rule.waitUntil(10_000) { !(app.agency.start.value as StartState.Ready).brain.busy.value }
        rule.waitForIdle()
        rule.onNodeWithTag(MealTags.PHOTO).performClick()
        var started: Intent? = null
        // The notification permission is asked once when 入社建檔 is done; its request may come first: skip it.
        rule.waitUntil(5_000) {
            scenario!!.onActivity { a ->
                shadowOf(a).nextStartedActivityForResult?.let { if (it.intent.action == MediaStore.ACTION_IMAGE_CAPTURE) started = it.intent }
            }
            started != null
        }
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started!!.action)
        val pending = app.agency.camera.pending()!!
        assertTrue(pending.meal)
        return started!! to pending.file
    }

    private fun answer(intent: Intent, code: Int) = scenario!!.onActivity { shadowOf(it).receiveResult(intent, code, null) }

    /** A meal he reported in words (and the body teacher's line pointing at it). */
    private fun reported(id: String, photo: String? = null, pending: Boolean = false): Meal {
        val m = Meal(
            id, today(), System.currentTimeMillis() - 60_000, "LUNCH", "吃了雞腿便當",
            listOf(MealItem("雞腿便當", 1.0, 715, 30.0, null, null, false, KcalSource.ESTIMATE, estimateHigh = 650)),
            verified = photo != null, photoMediaId = photo,
        )
        runBlocking {
            ready().store.meals.upsert(m)
            if (pending) ready().store.meals.putPending(PendingLabel(id, 0, "雞腿便當", 715, System.currentTimeMillis()))
            ready().store.messages.insert(
                Message("line-$id", System.currentTimeMillis(), Who.COMPANY, RoleKey.BODY, "meal", text = "記下：雞腿便當。", refs = MessageRefs(records = listOf(id)), wordedBy = WordedBy.TEMPLATE),
            )
        }
        app.agency.send("刮好了")
        rule.waitForTag(MealTags.CARD + id, 30_000)
        return m
    }

    @Test fun aPhotoGoesOnTheMealBeingReportedAndNoPlaintextIsLeft() {
        open()
        reported("m-1")
        rule.waitForTag(MealTags.UNVERIFIED + "m-1")
        val (intent, file) = tapPhoto()
        file.writeBytes(jpeg())
        answer(intent, Activity.RESULT_OK)
        rule.waitUntil(10_000) { meals().single().verified }
        val m = meals().single()
        assertEquals("m-1", m.id)
        val media = runBlocking { ready().store.media.get(m.photoMediaId!!) }!!
        assertEquals(MediaPurpose.MEAL, media.purpose)
        assertTrue(MediaVault.cameraDir(app).list().orEmpty().isEmpty())
        assertNull(app.agency.camera.pending())
        rule.waitForText("照片收到，午餐已證實。")
        rule.waitForTag(MealTags.THUMB + m.photoMediaId)
    }

    @Test fun aPhotoFirstWaitsForHisWordsAndACancelledShotLeavesNothing() {
        open()
        val (cancelled, empty) = tapPhoto()
        answer(cancelled, Activity.RESULT_CANCELED)
        rule.waitUntil(5_000) { !empty.exists() }
        assertTrue(meals().isEmpty())
        assertEquals(0, runBlocking { ready().store.media.count() })
        val (intent, file) = tapPhoto()
        file.writeBytes(jpeg())
        answer(intent, Activity.RESULT_OK)
        rule.waitUntil(10_000) { meals().isNotEmpty() }
        val waiting = meals().single()
        assertTrue(waiting.verified)
        assertTrue(waiting.items.isEmpty())
        rule.waitForText("吃了什麼、幾份打給我")
    }

    @Test fun aBrokenPhotoShowsAPlaceholderAndDeletingItNeedsAConfirm() {
        open()
        reported("m-broken", photo = "gone-0001")
        rule.waitForTag(MealTags.BROKEN + "gone-0001")
        // A real photo: open it, 刪掉照片 asks first; 取消 keeps it, 刪掉 makes the meal 「未證實」 and removes the file.
        val bytes = jpeg()
        val sealed = app.agency.mediaVault()!!.put("p-0001", bytes)
        runBlocking { ready().store.media.insert(Media("p-0001", MediaKind.PHOTO, MediaPurpose.MEAL, "image/jpeg", sealed.bytes, sealed.sha256, 1)) }
        reported("m-2", photo = "p-0001")
        rule.waitForTag(MealTags.THUMB + "p-0001")
        rule.onNodeWithTag(MealTags.THUMB + "p-0001").performClick()
        rule.waitForTag(MealTags.VIEWER)
        rule.onNodeWithTag(MealTags.DELETE).performClick()
        rule.waitForText("確定刪掉這張照片？")
        rule.onNodeWithTag(MealTags.DELETE_NO).performClick()
        assertEquals("p-0001", runBlocking { ready().store.meals.get("m-2") }!!.photoMediaId)
        rule.onNodeWithTag(MealTags.DELETE).performClick()
        rule.onNodeWithTag(MealTags.DELETE_YES).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.meals.get("m-2") }!!.photoMediaId == null }
        assertFalse(runBlocking { ready().store.meals.get("m-2") }!!.verified)
        assertNull(runBlocking { ready().store.media.get("p-0001") })
        assertFalse(File(tmp, "media/p-0001.bin").exists())
    }

    @Test fun theLabelFormChecksItsNumbersAndReplacesTheEstimate() {
        open()
        reported("m-3", pending = true)
        rule.waitForTag(MealTags.LABEL + "m-3-0")
        rule.onNodeWithTag(MealTags.LABEL + "m-3-0").performScrollTo().performClick()
        rule.waitForTag(MealTags.FORM)
        rule.onNodeWithTag(MealTags.FORM_KCAL).performTextInput("0")
        rule.onNodeWithTag(MealTags.FORM_SAVE).performClick()
        rule.waitForText("大卡要是 1 到 5000 的整數。")
        rule.onNodeWithTag(MealTags.FORM_KCAL).performTextInput("68") // 「068」
        rule.onNodeWithTag(MealTags.FORM_PROTEIN).performTextInput("35")
        rule.onNodeWithTag(MealTags.FORM_SAVE).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.meals.get("m-3") }!!.items.single().source == KcalSource.LABEL }
        assertEquals(68, runBlocking { ready().store.meals.get("m-3") }!!.items.single().kcal)
        assertEquals(68, runBlocking { ready().store.food.get("雞腿便當") }!!.kcal)
        rule.waitForText("存進食物表：雞腿便當 68 大卡、蛋白質 35 克（標示）。")
    }

    @Test fun theLabelNumbersAreCheckedByCode() {
        assertEquals(LabelInput.Ok(680, 35.0), LabelInput.parse(" 680 ", "35"))
        assertEquals(LabelInput.Ok(117, null), LabelInput.parse("117", ""))
        assertEquals(LabelInput.Ok(120, 24.5), LabelInput.parse("120", "24，5"))
        assertEquals(LabelInput.Bad(kcal = true, protein = false), LabelInput.parse("5001", ""))
        assertEquals(LabelInput.Bad(kcal = true, protein = false), LabelInput.parse("十", ""))
        assertEquals(LabelInput.Bad(kcal = false, protein = true), LabelInput.parse("300", "600"))
    }

    @Test fun aMealReminderOpensTheCamera() {
        val intent = Intent(app, AgencyActivity::class.java).putExtra(AgencyNotifier.EXTRA_SLOT, "DINNER")
        open(intent)
        var started: Intent? = null
        scenario!!.onActivity { a ->
            // Drain what was started: the camera for the meal.
            while (true) {
                val next = shadowOf(a).nextStartedActivityForResult ?: break
                started = next.intent
            }
        }
        assertNotNull(started)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started!!.action)
        assertTrue(app.agency.camera.pending()!!.meal)
        assertTrue(rule.onAllNodesWithTag(MealTags.PHOTO).fetchSemanticsNodes().isNotEmpty())
    }
}
