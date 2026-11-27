package app.dshmobile.agency.backup

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.backup.BackupCipher
import app.agency.core.backup.BackupException
import app.agency.core.metering.Provider
import app.agency.core.model.LedgerEntry
import app.agency.core.model.Meal
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Memo
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.StoredThinking
import app.agency.core.model.WeightLog
import app.agency.core.model.Who
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 加密備份 (owner 2026-11-23): an export sealed with his password holds every record and photo but never a key or the stored
 * thinking; a wrong password changes nothing; a restore after his confirm gives back exactly what was exported. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("backup").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val secret = "sk-invented-secret-key-123"

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun pw() = "invented-pass-42".toCharArray()

    @Test fun exportThenRestoreGivesBackTheSameDataAndNeverAKey() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to secret))
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        val photo = "照片的位元組".toByteArray() + ByteArray(5000) { it.toByte() }
        runBlocking {
            val s = ready().store
            s.profile.put((s.profile.get() ?: Profile()).copy(onboardingDone = true, stageName = "小雨"))
            val sealed = app.agency.mediaVault()!!.put("photo-1", photo)
            s.media.insert(Media("photo-1", MediaKind.PHOTO, MediaPurpose.MEAL, "image/jpeg", sealed.bytes, sealed.sha256, 3))
            s.meals.insert(Meal("meal-1", "2026-12-01", 4, "LUNCH", "雞腿便當", verified = true, photoMediaId = "photo-1", lateAt = 2))
            s.messages.insert(Message("m-1", 5, Who.OWNER, text = "幫我記買洗衣精"))
            s.memos.upsert(Memo("memo-1", 6, "買洗衣精", dueAt = 4_102_444_800_000)) // far ahead: no reminder fires during the test
            s.ledger.insert(LedgerEntry("l-1", "2026-12-01", 7, 120, "便當"))
            s.weights.insert(WeightLog("w-1", "2026-12-01", 8, 78.4))
            s.thinking.put(StoredThinking("m-1", 9, "DEEPSEEK", "OPENAI", "她在想的事"))
        }
        // The company may still be writing the day's first lines (入社建檔 just marked done): the backup is taken once it is quiet.
        var before = runBlocking { snapshot() }
        for (i in 0 until 20) {
            Thread.sleep(500)
            val again = runBlocking { snapshot() }
            if (again == before) break
            before = again
        }
        val out = ByteArrayOutputStream()
        val made = runBlocking { app.agency.exportBackup({ out }, pw()) }.getOrThrow()
        assertEquals(1, made.media)
        val file = out.toByteArray()

        // Inside: records and the photo; never the key, never the thinking.
        val zip = BackupCipher.decrypt(ByteArrayInputStream(file), pw()).use { it.readBytes() }
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertTrue(names.first() == Backup.MANIFEST)
        assertTrue("tables/memo.jsonl" in names && "tables/ledger_entry.jsonl" in names && "media/photo-1.bin" in names)
        assertFalse(names.any { it.contains("thinking") || it.contains("room_master") })
        val text = String(zip, Charsets.UTF_8)
        assertFalse(text.contains(secret))
        assertFalse(text.contains("她在想的事"))
        assertFalse(String(file, Charsets.UTF_8).contains("買洗衣精")) // the file itself is sealed

        // Things change after the backup.
        runBlocking {
            val s = ready().store
            s.messages.insert(Message("m-2", 10, Who.OWNER, text = "後來的話"))
            s.memos.remove("memo-1")
            s.ledger.delete("l-1")
            app.agency.mediaVault()!!.delete("photo-1")
        }
        // A wrong password: refused, nothing changes.
        val wrong = runBlocking { app.agency.restoreBackup({ ByteArrayInputStream(file) }, "not-the-password".toCharArray()) }
        assertEquals(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, (wrong.exceptionOrNull() as BackupException).reason)
        assertTrue(runBlocking { ready().store.messages.get("m-2") } != null)
        // The check reads the whole file and changes nothing.
        val summary = runBlocking { app.agency.backupSummary({ ByteArrayInputStream(file) }, pw()) }.getOrThrow()
        assertEquals(1, summary.rows["memo"])
        assertNull(runBlocking { ready().store.memos.get("memo-1") })
        // His confirm: everything as it was at the backup, and the company opens again on it.
        runBlocking { app.agency.restoreBackup({ ByteArrayInputStream(file) }, pw()) }.getOrThrow()
        rule.waitUntil(10_000) { app.agency.start.value is StartState.Ready }
        assertEquals(before, runBlocking { snapshot() })
        assertArrayEquals(photo, app.agency.mediaVault()!!.get("photo-1"))
        assertEquals(0, runBlocking { ready().store.thinking.count() })
        // The key on this phone is untouched (it was never in the file).
        assertEquals(secret, app.keyVault.get(Provider.DEEPSEEK))
    }

    private suspend fun snapshot(): List<Any?> {
        val s = ready().store
        return listOf(
            s.profile.get(), s.messages.recent(100), s.memos.all(deleted = true), s.ledger.between("0000-01-01", "9999-12-31"),
            s.weights.between("0000-01-01", "9999-12-31"), s.meals.between("0000-01-01", "9999-12-31"), s.media.all(),
        )
    }
}
