package app.dshmobile

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.rules.Fixtures
import app.agency.core.sim.Sim
import app.agency.core.sim.Sim14
import app.dshmobile.data.Db
import app.dshmobile.data.DbKey
import app.dshmobile.data.SqlStore
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulated phone, day 1 to day 14: the very scenario of `:core`'s `Sim14Test` (a scripted owner, a scripted model, a moving
 * clock, the invariants checked at every step) run on the device against `SqlStore` over the real encrypted database (SQLCipher,
 * key wrapped by the Android Keystore). Invented rules only; no network.
 */
class Sim14DeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "sim14-" + UUID.randomUUID()).also { it.mkdirs() }
    private val alias = "xiaoyu-test-sim-" + UUID.randomUUID()
    private var db: SupportSQLiteDatabase? = null

    @After fun cleanUp() {
        runCatching { db?.close() }
        dir.deleteRecursively()
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    @Test(timeout = 20 * 60 * 1000L) fun fourteenDaysOnTheEncryptedDatabase() {
        val opened = Db.open(context, DbKey(SecretsFile(File(dir, "secrets.json")), KeystoreWrap(alias)), File(dir, "sim.db"))
        db = opened
        val sim = Sim(Fixtures.rules(), SqlStore(opened))
        Sim14(sim).run()
        assertTrue("the simulation recorded something", sim.trace.isNotEmpty())
    }
}
