package app.dshmobile.agency.data

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.agency.core.store.AgencyStore
import app.agency.core.store.AgencyStoreContract
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared [AgencyStoreContract] on Room (in memory, plain SQLite: SQLCipher's native library cannot load on the JVM). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomAgencyStoreTest : AgencyStoreContract() {
    private val opened = mutableListOf<AgencyDatabase>()

    override fun newStore(): AgencyStore {
        val db = AgencyDatabase.build(ApplicationProvider.getApplicationContext(), null, FrameworkSQLiteOpenHelperFactory())
        opened += db
        return RoomAgencyStore(db)
    }

    @After fun close() = opened.forEach { it.close() }
}
