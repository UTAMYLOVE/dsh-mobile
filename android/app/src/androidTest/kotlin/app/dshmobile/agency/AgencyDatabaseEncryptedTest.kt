package app.dshmobile.agency

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.model.Message
import app.agency.core.model.Who
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.DbKey
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.KeystoreWrap
import app.dshmobile.agency.secure.SecretsFile
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing on a device (arch 1.12): SQLCipher with a passphrase wrapped by a real Keystore key. The file does not start
 * with `SQLite format 3` and does not hold the words written; the same key reopens it; another passphrase is
 * [DataLockedException]. Uses its own folder and Keystore alias, removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AgencyDatabaseEncryptedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "agency-encryption-test")
    private val file = File(dir, "agency.db")

    private fun key(secrets: String) = DbKey(SecretsFile(File(dir, secrets)), KeystoreWrap(ALIAS))

    @After fun cleanUp() {
        dir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    @Test fun theDatabaseFileIsEncrypted() = runBlocking {
        dir.deleteRecursively()
        val words = "invented words that must not be readable"
        AgencyDatabase.open(context, key("a.json"), file).let { db ->
            RoomAgencyStore(db).messages.insert(Message("m-1", 1, Who.OWNER, text = words))
            db.close()
        }
        val bytes = file.readBytes()
        assertFalse(String(bytes.copyOf(16), Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("invented words"))

        AgencyDatabase.open(context, key("a.json"), file).let { db ->
            assertEquals(words, RoomAgencyStore(db).messages.get("m-1")?.text)
            db.close()
        }

        val sizeBefore = file.length()
        val other = key("b.json")
        other.passphrase(dbExists = false) // a different, valid passphrase
        try {
            AgencyDatabase.open(context, other, file).close()
            fail("another passphrase must not open the file")
        } catch (_: DataLockedException) {
        }
        assertEquals(sizeBefore, file.length()) // nothing was wiped
    }

    private companion object {
        const val ALIAS = "agency-db-instrumented-test"
    }
}
