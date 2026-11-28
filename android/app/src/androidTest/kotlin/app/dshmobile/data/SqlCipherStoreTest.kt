package app.dshmobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.StoreContract
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shared [StoreContract] on the REAL database: SQLCipher, its key made once and wrapped by an Android Keystore key. Plus what
 * only a device can show: the data survives a close and reopen, and the file is not readable as plain SQLite.
 * Every store lives in its own folder with its own Keystore alias, so it never touches the app's own database.
 */
class SqlCipherStoreTest : StoreContract() {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dirs = ArrayList<File>()
    private val dbs = ArrayList<SupportSQLiteDatabase>()
    private val aliases = ArrayList<String>()

    private class Home(val dir: File, val alias: String) {
        val file get() = File(dir, "test.db")
        fun key(context: Context) = DbKey(SecretsFile(File(dir, "secrets.json")), KeystoreWrap(alias))
    }

    private fun home(): Home {
        val dir = File(context.cacheDir, "sqlcipher-" + UUID.randomUUID()).also { it.mkdirs() }
        val alias = "xiaoyu-test-" + UUID.randomUUID()
        dirs += dir
        aliases += alias
        return Home(dir, alias)
    }

    private fun open(h: Home): SupportSQLiteDatabase = Db.open(context, h.key(context), h.file).also { dbs += it }

    override fun newStore(): Store = SqlStore(open(home()))

    @After fun cleanUp() {
        dbs.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { ks.deleteEntry(it) }
        }
    }

    private val day = LocalDate.of(2030, 1, 7)
    private val at = Instant.parse("2030-01-07T01:00:00Z")

    @Test fun dataSurvivesCloseAndReopen() {
        val h = home()
        val first = open(h)
        val s1 = SqlStore(first)
        val e = s1.add(day, at, Kind.WATER, JsonObject(mapOf("n" to JsonPrimitive(7))))
        s1.putDoc("k", "{\"a\":1}")
        s1.addMessage(at, app.agency.core.store.Role.OWNER, "你好")
        first.close()

        val second = open(h)
        val s2 = SqlStore(second)
        assertEquals(e, s2.entry(e.id))
        assertEquals("{\"a\":1}", s2.doc("k"))
        assertEquals("你好", s2.messages(5).single().text)
    }

    @Test fun theFileIsNotReadableAsPlainSqlite() {
        val h = home()
        val db = open(h)
        SqlStore(db).add(day, at, Kind.WATER, JsonObject(mapOf("secret" to JsonPrimitive("很私人的內容"))))
        db.close()
        val bytes = h.file.readBytes()
        assertTrue(bytes.size > 1000)
        assertNotEquals("SQLite format 3", String(bytes, 0, 15, Charsets.ISO_8859_1))
        assertFalse("the content is not in the file", String(bytes, Charsets.ISO_8859_1).contains("secret"))
        try {
            SQLiteDatabase.openDatabase(h.file.path, null, SQLiteDatabase.OPEN_READONLY).use { plain ->
                plain.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            }
            fail("the framework SQLite opened an encrypted file")
        } catch (_: SQLiteException) {
            // expected: file is not a database
        }
    }
}
