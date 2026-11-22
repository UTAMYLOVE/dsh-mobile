package app.dshmobile.agency.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.DbKey
import app.dshmobile.agency.media.MediaVault
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The passphrase is made once and never replaced; a lost one is [DataLockedException] and nothing is wiped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DbKeyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun newWrap(): KeyWrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { k -> SecretKeyWrap { k } }
    private val wrap = newWrap()
    private val secretsFile = File(context.noBackupFilesDir, SecretsFile.NAME)

    private fun assertLocked(body: () -> Unit) {
        try {
            body()
            fail("expected DataLockedException")
        } catch (_: DataLockedException) {
        }
    }

    @Test fun everythingLivesInNoBackupStorage() {
        assertEquals(File(context.noBackupFilesDir, "agency/agency.db"), AgencyDatabase.file(context))
        assertEquals(File(context.noBackupFilesDir, "agency/media"), MediaVault.mediaDir(context))
        assertEquals(File(context.cacheDir, "camera"), MediaVault.cameraDir(context))
    }

    @Test fun thePassphraseIsMadeOnceAndKept() {
        val first = DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = false)
        val again = DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = true)
        assertArrayEquals(first, again)
        val media = WrappedSecret(DbKey.secretsFile(context), MediaVault.KEY_NAME, wrap).get(dataExists = false)
        assertFalse(first.contentEquals(media))
        assertArrayEquals(first, DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = true))
    }

    @Test fun aMissingPassphraseForAnExistingDatabaseIsLockedAndNotReplaced() {
        assertLocked { DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = true) }
        assertFalse(secretsFile.exists())
    }

    @Test fun aLostWrappingKeyIsLockedAndTheStoredSecretIsKept() {
        DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = false)
        val before = secretsFile.readBytes()
        assertLocked { DbKey(DbKey.secretsFile(context), newWrap()).passphrase(dbExists = true) }
        assertLocked { DbKey(DbKey.secretsFile(context), newWrap()).passphrase(dbExists = false) }
        assertArrayEquals(before, secretsFile.readBytes())
    }

    @Test fun aDamagedSecretsFileIsLockedAndLeftAsItIs() {
        secretsFile.parentFile!!.mkdirs()
        secretsFile.writeText("{not json")
        assertLocked { DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = false) }
        assertEquals("{not json", secretsFile.readText())
    }

    /** Widens the read → generate → write window, as a slow Keystore would. */
    private class SlowWrap(private val inner: KeyWrap) : KeyWrap {
        override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray = inner.wrap(plain, aad).also { Thread.sleep(2) }
        override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray = inner.unwrap(blob, aad)
    }

    /** Runs [body] on [threads] threads released at once, each with its own [SecretsFile] (as `DbKey.forApp` and
     *  `MediaVault.forApp` do); returns each thread's result in order, failing on any thread's exception. */
    private fun <T> race(threads: Int, body: (index: Int, file: SecretsFile) -> T): List<T> {
        val start = CountDownLatch(1)
        val results = arrayOfNulls<Any?>(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { i ->
            Thread {
                try {
                    start.await()
                    results[i] = body(i, DbKey.secretsFile(context))
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }

    @Test fun firstRunsOfDifferentSecretsAtOnceLoseNone() {
        val slow = SlowWrap(wrap)
        repeat(50) { round ->
            secretsFile.delete()
            val got = race(8) { i, file -> WrappedSecret(file, "n$i", slow).get(dataExists = false) }
            got.forEachIndexed { i, secret ->
                assertArrayEquals("round $round, n$i", secret, WrappedSecret(DbKey.secretsFile(context), "n$i", wrap).get(dataExists = true))
            }
        }
    }

    @Test fun firstRunsOfTheSameSecretAtOnceAgreeOnOne() {
        val slow = SlowWrap(wrap)
        repeat(50) { round ->
            secretsFile.delete()
            val got = race(8) { _, file -> DbKey(file, slow).passphrase(dbExists = false) }
            val kept = DbKey(DbKey.secretsFile(context), wrap).passphrase(dbExists = true)
            got.forEachIndexed { i, secret -> assertArrayEquals("round $round, thread $i", kept, secret) }
        }
    }
}
