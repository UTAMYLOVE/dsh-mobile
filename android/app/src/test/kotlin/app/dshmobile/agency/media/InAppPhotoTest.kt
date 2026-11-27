package app.dshmobile.agency.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.metering.Provider
import app.agency.core.model.PhotoReadStatus
import app.agency.core.model.Profile
import app.agency.core.model.Baseline
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.MediaNotice
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.meal.MealTags
import app.dshmobile.ui.agency.meal.ReadTags
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * T15b on the phone side: a frame of the in-app camera goes from memory into the vault only (no file, no gallery), the copy the
 * model gets is cleaned (no EXIF, ≤ 1600 px) and goes through the gate to the fake model; the menu it reads is a card he edits
 * and confirms; the camera permission is asked the first time and a refusal says what to do. Invented pictures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InAppPhotoTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("in-app-photo").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val photoBodies = mutableListOf<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val content = if (body.contains("image_url")) {
                    synchronized(photoBodies) { photoBodies += body }
                    """{"rows":[{"name":"雞腿飯","kcal":700,"protein_g":30,"printed":true,"portion":1,"fried":false,"processed":false,"amount":null,"food":false},{"name":"燙青菜","kcal":60,"protein_g":null,"printed":false,"portion":1,"fried":false,"processed":false,"amount":null,"food":false}]}"""
                } else {
                    JSONObject().put("kind", "chat").toString()
                }
                val msg = JSONObject().put("role", "assistant").put("content", content)
                val json = JSONObject().put("id", "x").put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 900).put("completion_tokens", 40).put("prompt_cache_hit_tokens", 0).put("prompt_cache_miss_tokens", 900))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())
            }
        }
        start()
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-invented"))
        AgencyApp.testOverrides = kit.overrides(modelBase = server.url("/"), systemCamera = false)
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
    }

    /** A 3200 x 2400 drawn picture with the EXIF a phone writes (place, device, time). */
    private fun phoneJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(240, 180, 190)) }
        val f = File(tmp, "shot.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "InventedPhoneMaker")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "25/1,2/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:23 12:20:00")
            saveAttributes()
        }
        return f.readBytes().also { f.delete() }
    }

    private fun files(dir: File): Set<String> = dir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()

    @Test fun aFrameGoesToTheVaultOnlyAndTheModelGetsACleanCopy() {
        open()
        val before = files(app.cacheDir) + files(app.filesDir).filter { !it.endsWith(".log") }
        app.agency.onFrame(PhotoTarget.Menu, phoneJpeg(), 0)
        rule.waitUntil(20_000) { runBlocking { ready().store.photoReads.all() }.isNotEmpty() }
        val read = runBlocking { ready().store.photoReads.all() }.single()
        rule.waitForTag(ReadTags.CARD + read.id, 20_000)
        // Nothing was written outside the vault: no camera file, no copy in the gallery.
        val after = files(app.cacheDir) + files(app.filesDir).filter { !it.endsWith(".log") }
        assertEquals(before, after)
        assertTrue(app.agency.mediaVault()!!.has(read.mediaId))
        val stored = app.agency.mediaVault()!!.get(read.mediaId)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(stored, 0, stored.size, b)
        assertEquals(1600, maxOf(b.outWidth, b.outHeight))
        // What reached the model: one JPEG, ≤ 1600 px, with no EXIF at all.
        val sent = JSONObject(synchronized(photoBodies) { photoBodies.single() })
        val url = sent.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        val jpeg = Base64.getDecoder().decode(url.substringAfter(","))
        val exif = ExifInterface(jpeg.inputStream())
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertFalse(String(jpeg, Charsets.ISO_8859_1).contains("Exif"))
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b)
        assertTrue(maxOf(b.outWidth, b.outHeight) <= 1600)
        // A recording can never be made into what the model gets.
        assertNull(PhotoBytes.forModel("RIFF\u0000\u0000\u0000\u0000WAVEfmt invented".toByteArray()))

        // He fixes the second row's number and confirms: only now is anything a fact.
        assertNull(runBlocking { ready().store.food.get("雞腿飯") })
        rule.onNodeWithTag(ReadTags.NUMBER + 1).performScrollTo().performTextClearance()
        rule.onNodeWithTag(ReadTags.NUMBER + 1).performTextInput("80")
        rule.onNodeWithTag(ReadTags.CONFIRM + read.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.photoReads.get(read.id)!!.status } == PhotoReadStatus.CONFIRMED }
        assertEquals(700, runBlocking { ready().store.food.get("雞腿飯") }!!.kcal)
        assertFalse(runBlocking { ready().store.food.get("雞腿飯") }!!.estimated)
        assertEquals(80, runBlocking { ready().store.food.get("燙青菜") }!!.kcal)
        rule.waitForTag(ReadTags.DONE + read.id)
    }

    @Test fun theCameraPermissionIsAskedOnceNeededAndARefusalSaysWhatToDo() {
        open()
        app.agency.send("刮好了") // any line reloads the screen with the buttons
        rule.waitForTag(MealTags.MENU, 30_000)
        rule.onNodeWithTag(MealTags.MENU).performScrollTo().performClick()
        scenario!!.onActivity { a ->
            val req = shadowOf(a).lastRequestedPermission!!
            assertTrue(req.requestedPermissions.contains(Manifest.permission.CAMERA))
            a.onRequestPermissionsResult(req.requestCode, req.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        rule.waitUntil(5_000) { app.agency.mediaNotice.value == MediaNotice.NO_CAMERA_PERMISSION }
        assertNull(app.agency.inAppCamera.value)
        rule.waitForText("沒有相機權限")
        // Granted: the app's own camera opens for the menu (no camera app, no file).
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        rule.onNodeWithTag(MealTags.MENU).performScrollTo().performClick()
        rule.waitUntil(5_000) { app.agency.inAppCamera.value == PhotoTarget.Menu || app.agency.camera.pending() != null }
        assertTrue(MediaVault.cameraDir(app).list().orEmpty().isEmpty() || app.agency.camera.pending() != null)
    }
}
