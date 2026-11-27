package app.dshmobile.agency.media

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.handlers.BaselineSteps
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.Baseline
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.onboarding.OnboardingTags
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
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
 * The system camera round trip (arch T07): 拍照 starts `ACTION_IMAGE_CAPTURE` into `cache/camera/` for a result, RESULT_OK
 * seals the (downsized) shot into the vault and the plaintext is gone, RESULT_CANCELED leaves the step open and deletes the
 * empty file. The picture is drawn here, not a real photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("camera-capture").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready
    private fun join(): Baseline? = runBlocking { ready().store.baselines.get(Baseline.JOIN) }

    /** A drawn 3200 x 2400 picture as JPEG: twice the size the vault keeps. */
    private fun bigJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(250, 200, 210)) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun openOnTheFrontPhoto() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        rule.waitForTag(OnboardingTags.STEP + OnboardingStep.BODY.name)
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("身高 186 體重 94")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForTag(OnboardingTags.STEP + OnboardingStep.PHOTO_FRONT.name)
    }

    /** Taps 拍照 and returns the camera intent and the file it writes into. */
    private fun takePhoto(): Pair<org.robolectric.shadows.ShadowActivity.IntentForResult, File> {
        rule.onNodeWithTag(OnboardingTags.TAKE_PHOTO).performClick()
        var started: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        scenario!!.onActivity { started = shadowOf(it).nextStartedActivityForResult }
        assertNotNull("notice=${app.agency.mediaNotice.value} pending=${app.agency.camera.pending()} log=${app.errorLog.file().takeIf { it.isFile }?.readText()?.takeLast(400)}", started)
        val intent = started!!.intent
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertEquals("content", uri.scheme)
        val file = app.agency.camera.pending()!!.file
        assertTrue(file.path.startsWith(MediaVault.cameraDir(app).path))
        return started!! to file
    }

    @Test fun aShotIsSealedDownsizedAndThePlaintextIsGone() {
        openOnTheFrontPhoto()
        val (started, file) = takePhoto()
        file.writeBytes(bigJpeg())
        scenario!!.onActivity { shadowOf(it).receiveResult(started.intent, Activity.RESULT_OK, null) }
        rule.waitForTag(OnboardingTags.STEP + OnboardingStep.PHOTO_SIDE.name)
        val id = join()!!.photoFront!!
        val plain = app.agency.mediaVault()!!.get(id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
        assertEquals(1600, maxOf(bounds.outWidth, bounds.outHeight))
        assertFalse(file.exists())
        assertTrue(MediaVault.cameraDir(app).list().orEmpty().isEmpty())
        assertNull(app.agency.camera.pending())
        val row = runBlocking { ready().store.media.get(id) }!!
        assertEquals(app.agency.mediaVault()!!.digest(id).sha256, row.sha256)
    }

    @Test fun aCancelledShotLeavesTheStepOpen() {
        openOnTheFrontPhoto()
        val (started, file) = takePhoto()
        scenario!!.onActivity { shadowOf(it).receiveResult(started.intent, Activity.RESULT_CANCELED, null) }
        rule.waitUntil(5_000) { !file.exists() }
        assertEquals(OnboardingStep.PHOTO_FRONT, BaselineSteps.open(join()!!))
        assertNull(join()!!.photoFront)
        assertEquals(0, runBlocking { ready().store.media.count() })
        assertNull(app.agency.camera.pending())
    }

    @Test fun thePendingShotSurvivesTheStartSweep() {
        openOnTheFrontPhoto()
        val (_, file) = takePhoto()
        file.writeBytes(bigJpeg())
        File(MediaVault.cameraDir(app), "left-over.jpg").writeText("invented")
        // The process dies while the camera app is open: the next start keeps the waiting shot and sweeps the rest.
        scenario!!.close()
        runBlocking { app.agency.stopForTest() }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        assertTrue(file.exists())
        assertEquals(listOf(file.name), MediaVault.cameraDir(app).list()!!.toList())
        // The camera's answer arrives for the new activity: it still finds the pending capture.
        app.agency.onCameraResult(true)
        rule.waitUntil(5_000) { join()?.photoFront != null }
        assertNotNull(runBlocking { ready().store.media.get(join()!!.photoFront!!) })
        assertFalse(file.exists())
    }
}
