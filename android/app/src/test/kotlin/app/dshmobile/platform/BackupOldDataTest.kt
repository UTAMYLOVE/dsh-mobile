package app.dshmobile.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.agency.core.agent.Card
import app.agency.core.backup.BackupException
import app.agency.core.store.Kind
import app.agency.core.store.Role
import app.dshmobile.data.SqlStore
import app.dshmobile.data.TestDb
import app.dshmobile.platform.media.MediaVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupOldDataTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pass = "invented passphrase".toCharArray()
    private val rounds = 100_000
    private val at = Instant.parse("2030-02-01T00:00:00Z")

    private class Side(val db: androidx.sqlite.db.SupportSQLiteDatabase, val vault: MediaVault) {
        val store = SqlStore(db)
        var restored = 0
        val backup = Backup({ db }, vault, onRestored = { restored++ })
    }

    private fun side(name: String): Side {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Side(TestDb.open(context), MediaVault(tmp.newFolder(name)) { key })
    }

    private fun fill(s: Side) {
        val d1 = LocalDate.of(2030, 1, 7)
        s.store.add(d1, at, Kind.WATER, JsonObject(mapOf("ml" to JsonPrimitive(500))))
        s.store.add(d1.plusDays(3), at, Kind.MEAL, JsonObject(mapOf("n" to JsonPrimitive(1))))
        s.store.putDoc("settings", """{"tier":"SAVING"}""")
        s.store.addMessage(at, Role.MANAGER, "你好", listOf(Card.Score("check_in", 3)))
        s.vault.put("photo-1", ByteArray(3000) { (it % 200).toByte() })
        s.vault.put("rec-1", ByteArray(200_000) { (it % 13).toByte() })
    }

    private fun export(s: Side): ByteArray = ByteArrayOutputStream().also { s.backup.export(it, pass, at, rounds) }.toByteArray()

    @Test fun exportThenImportRestoresEverythingAndReplacesWhatWasThere() {
        val a = side("a")
        fill(a)
        val file = export(a)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("check_in")) // sealed, not readable

        val b = side("b")
        b.store.add(LocalDate.of(2031, 5, 5), at, Kind.WATER, JsonObject(emptyMap())) // to be replaced
        b.vault.put("stray", ByteArray(10))
        val preview = b.backup.preview(ByteArrayInputStream(file), pass)
        assertEquals(mapOf("entry" to 2, "doc" to 1, "message" to 1), preview.rows)
        assertEquals(2, preview.media)
        assertEquals("2030-01-07", preview.firstDay)
        assertEquals("2030-01-10", preview.lastDay)
        assertEquals(1, b.store.entries(LocalDate.of(2031, 5, 5)).size) // a preview changes nothing

        b.backup.restore(ByteArrayInputStream(file), pass)
        assertEquals(1, b.restored)
        assertEquals(0, b.store.entries(LocalDate.of(2031, 5, 5)).size)
        assertEquals(a.store.entries(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31)), b.store.entries(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31)))
        assertEquals("""{"tier":"SAVING"}""", b.store.doc("settings"))
        assertEquals(Card.Score("check_in", 3), b.store.messages(5).single().cards.single())
        assertArrayEquals(a.vault.get("rec-1"), b.vault.get("rec-1"))
        assertEquals(setOf("photo-1", "rec-1"), b.vault.ids().toSet())
        // new rows continue after the restored ids
        val next = b.store.add(LocalDate.of(2030, 1, 8), at, Kind.WATER, JsonObject(emptyMap()))
        assertTrue(next.id > 2)
    }

    @Test fun aWrongPassphraseFailsAndChangesNothing() {
        val a = side("a")
        fill(a)
        val file = export(a)
        val b = side("b")
        b.store.putDoc("keep", "1")
        for (restore in listOf(false, true)) {
            try {
                val wrong = "another passphrase".toCharArray()
                if (restore) b.backup.restore(ByteArrayInputStream(file), wrong) else b.backup.preview(ByteArrayInputStream(file), wrong)
                fail("expected BackupException")
            } catch (e: BackupException) {
                assertEquals(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, e.reason)
                assertTrue(Backup.reasonText(context, e).contains("密碼"))
            }
        }
        assertEquals("1", b.store.doc("keep"))
        assertEquals(0, b.restored)
    }

    @Test fun aCutOffFileRollsBackTheRestore() {
        val a = side("a")
        fill(a)
        val file = export(a)
        val b = side("b")
        b.store.putDoc("keep", "1")
        try {
            b.backup.restore(ByteArrayInputStream(file.copyOf(file.size - 40)), pass)
            fail("expected BackupException")
        } catch (_: BackupException) {
        }
        assertEquals("1", b.store.doc("keep"))
        assertNull(b.store.doc("settings"))
        assertEquals(0, b.restored)
    }

    @Test fun aShortPassphraseIsRefused() {
        val a = side("a")
        try {
            a.backup.export(ByteArrayOutputStream(), "short".toCharArray(), at, rounds)
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.WEAK_PASSWORD, e.reason)
        }
    }

    // ---- 舊版資料 ----

    private class PlainOldDb(private val db: SQLiteDatabase) : OldDb {
        override fun query(sql: String, args: Array<String>): Cursor = db.rawQuery(sql, args)
        override fun close() = db.close()
    }

    private fun oldRoot(withDb: Boolean): File {
        val root = tmp.newFolder("nobackup")
        if (withDb) {
            val dir = File(root, "agency").apply { mkdirs() }
            val db = SQLiteDatabase.openOrCreateDatabase(File(dir, "agency.db"), null)
            db.execSQL(
                "CREATE TABLE message (id TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL, who TEXT NOT NULL, role TEXT, kind TEXT, " +
                    "mode TEXT NOT NULL, text TEXT NOT NULL, refs TEXT NOT NULL, worded_by TEXT, legacy INTEGER NOT NULL, unsent INTEGER NOT NULL DEFAULT 0)",
            )
            (1..7).forEach { i ->
                db.insert(
                    "message", null,
                    ContentValues().apply {
                        put("id", "m$i"); put("at", 1_000L * (if (i == 5) 4 else i)); put("who", if (i % 2 == 0) "COMPANY" else "OWNER")
                        put("mode", "NORMAL"); put("text", "第${i}句"); put("refs", "{}"); put("legacy", 0)
                    },
                )
            }
            db.close()
            File(dir, "media").mkdirs()
            File(dir, "media/p.bin").writeText("x")
            File(root, "agency_secrets.json").writeText("{}")
        }
        return root
    }

    private fun old(root: File) = OldData(root) { f -> PlainOldDb(SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)) }

    @Test fun theOldConversationPagesBackwardsWithoutChangingAnything() {
        val root = oldRoot(true)
        val before = File(root, "agency/agency.db").readBytes()
        val o = old(root)
        assertTrue(o.exists())
        assertEquals(7, o.count())
        val newest = o.page(3)
        assertEquals(listOf("第6句", "第7句").size + 1, newest.size)
        assertEquals(listOf("m6", "m7"), newest.map { it.id }.takeLast(2))
        assertEquals(OldWho.OWNER, newest.last().who)
        assertEquals(OldWho.COMPANY, newest[newest.size - 2].who)
        val older = o.page(3, before = newest.first())
        val oldest = o.page(10, before = older.first())
        assertEquals(7, newest.size + older.size + oldest.size)
        assertEquals((newest + older + oldest).map { it.id }.toSet().size, 7)
        assertTrue(o.page(5, before = oldest.first()).isEmpty())
        o.close()
        assertArrayEquals(before, File(root, "agency/agency.db").readBytes())
    }

    @Test fun deleteAllRemovesTheOldDatabaseMediaAndSecrets() {
        val root = oldRoot(true)
        val o = old(root)
        o.count()
        assertTrue(o.deleteAll())
        assertFalse(o.exists())
        assertFalse(File(root, "agency").exists())
        assertFalse(File(root, "agency_secrets.json").exists())
    }

    @Test fun withoutAnOldFileEverythingIsNone() {
        val o = OldData(oldRoot(false)) { error("must not open") }
        assertFalse(o.exists())
        assertEquals(0, o.count())
        assertTrue(o.page(5).isEmpty())
        assertTrue(o.deleteAll())
    }
}
