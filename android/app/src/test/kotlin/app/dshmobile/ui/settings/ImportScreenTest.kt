package app.dshmobile.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.LedgerBucket
import app.agency.core.model.Profile
import app.agency.core.time.AgencyClock
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.agency.media.PhotoTarget
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.meal.MealTags
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** T15c on screen: a pasted table is previewed, saved only on 匯入 and taken back with 復原; a picked photo is 補交 by its EXIF time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h2000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("import").toFile()
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
    }

    @Test fun aPastedTableIsSavedOnlyOnImportAndUndoneInOneTap() {
        open()
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.IMPORT.name).performScrollTo().performClick()
        rule.onNodeWithTag(ImportTags.PASTE).performScrollTo().performTextInput(
            "| 日期 | 金額 | 項目 | 分類 |\n|---|---|---|---|\n| 2026/11/20 | 120 | 便當 | 餐費 |\n| 2026/11/20 | 120 | 便當 | 餐費 |\n| 2026/11/21 | 350 | 髮蠟 | 形象用品 |\n| 2026/11/22 | 很多 | 書 | 學習 |",
        )
        rule.onNodeWithTag(ImportTags.READ_PASTE).performScrollTo().performClick()
        rule.waitForText("新的 2 筆、重複略過 1 筆、看不懂 1 筆")
        assertEquals(0, runBlocking { ready().store.ledger.count() }) // the preview saved nothing
        rule.onNodeWithTag(ImportTags.GO).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.ledger.count() } == 2 }
        val rows = runBlocking { ready().store.ledger.between("2026-11-01", "2026-11-30") }
        assertEquals(listOf("便當" to LedgerBucket.LIFE, "髮蠟" to LedgerBucket.FUN), rows.map { it.item to it.bucket })
        val batch = runBlocking { ready().store.imports.all() }.single()
        rule.waitForTag(ImportTags.UNDO + batch.id)
        rule.onNodeWithTag(ImportTags.UNDO + batch.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.ledger.count() } == 0 }
        rule.waitForText("（已復原）")
    }

    @Test fun aPickedPhotoGoesOnTheMealOfItsCaptureTimeAsLate() {
        open()
        val zone = AgencyClock.TAIPEI // the company's clock reads EXIF times in its zone
        // Taken an hour ago, picked now: 補交 with its time.
        val taken = LocalDateTime.now(zone).minusHours(1).withSecond(0).withNano(0)
        val bmp = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 220, 180)) }
        val f = File(tmp, "picked.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")))
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "25/1,2/1,0/1")
            saveAttributes()
        }
        val bytes = f.readBytes().also { f.delete() }
        runBlocking { app.agency.onPickedBytes(PhotoTarget.Meal, bytes) }
        val meal = runBlocking { ready().store.meals.between("0000-01-01", "9999-12-31") }.single()
        assertEquals(taken.atZone(zone).toInstant().toEpochMilli(), meal.lateAt)
        assertEquals(meal.lateAt, meal.at)
        val stored = app.agency.mediaVault()!!.get(meal.photoMediaId!!)
        assertNull(ExifInterface(stored.inputStream()).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(ExifInterface(stored.inputStream()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        app.agency.send("刮好了") // reloads the conversation
        rule.waitForTag(MealTags.LATE + meal.id, 20_000)
        assertTrue(runBlocking { ready().store.messages.recent(20) }.any { it.text.startsWith("照片收到（補交，") })
    }
}
