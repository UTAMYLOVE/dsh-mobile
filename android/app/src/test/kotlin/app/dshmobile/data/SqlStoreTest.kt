package app.dshmobile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.StoreContract
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared store contract on the real tables (plain SQLite), plus what only a database can get wrong. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SqlStoreTest : StoreContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    override fun newStore(): Store = SqlStore(TestDb.open(context))

    private val day = LocalDate.of(2030, 1, 7)
    private val t0 = Instant.parse("2030-01-07T01:00:00Z")
    private fun obj(n: Int) = JsonObject(mapOf("n" to JsonPrimitive(n)))

    @Test fun aFailedTransactionRollsBackEverything() {
        val s = newStore()
        runCatching {
            s.tx {
                s.add(day, t0, Kind.WATER, obj(1))
                s.putDoc("k", "{}")
                error("boom")
            }
        }
        assertEquals(0, s.entries(day).size)
        assertNull(s.doc("k"))
        s.tx { s.add(day, t0, Kind.WATER, obj(2)) }
        assertEquals(1, s.entries(day).size)
    }

    @Test fun aDamagedPayloadDoesNotBreakTheList() {
        val db = TestDb.open(context)
        val s = SqlStore(db)
        s.add(day, t0, Kind.WATER, obj(1))
        db.execSQL("INSERT INTO entry (day, at, kind, data) VALUES ('2030-01-07', 0, 'water', 'not json')")
        assertEquals(2, s.entries(day).size)
    }

    @Test fun theIndexesExist() {
        val db = TestDb.open(context)
        val names = db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(true, "entry_day" in names && "entry_kind_day" in names)
        assertEquals(Schema.VERSION, db.version)
    }
}
