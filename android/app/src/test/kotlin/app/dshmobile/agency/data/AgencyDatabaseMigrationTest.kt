package app.dshmobile.agency.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.StoredSignal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema v1 → v2 (T11b): a database written by the v1 schema (built from the checked-in `schemas/…/1.json`, with invented
 * rows) opens with [AgencyDatabase.MIGRATION_1_2]; Room checks the result against v2, every v1 row is still there and the
 * nerve's tables work. Without the migration the same file does not open (it is needed, and nothing is wiped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgencyDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("agency-migration").toFile()
    private val file = File(dir, "agency.db")

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/app.dshmobile.agency.data.AgencyDatabase/$version.json").readText()).getJSONObject("database")

    /** The v1 file as the T02 build left it, with a profile, a message and a sign-in. */
    private fun writeV1() {
        val v1 = schema(1)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = v1.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = v1.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO profile (id, height_cm, start_weight_kg, stage_name, member_color, company_name, trainee_no, join_date, level, " +
                "area_text, idol_names, day_start_hour, onboarding_done) VALUES (1, 170.0, 80.0, '小雨', '#8E2440', NULL, NULL, '2026-11-01', 'C', NULL, '[]', 5, 1)",
        )
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy) VALUES ('m1', 5, 'OWNER', NULL, NULL, 'NORMAL', '早安', '{}', NULL, 0)")
        db.execSQL("INSERT INTO sign_log (id, day, at, kind, on_time, review_good, review_fix) VALUES ('s1', '2026-11-20', 6, 'IN', 1, NULL, NULL)")
        db.version = 1
        db.close()
    }

    @Test fun aVersionOneFileMigratesAndKeepsEveryRow() = runBlocking {
        writeV1()
        val db = AgencyDatabase.build(context, file.path, FrameworkSQLiteOpenHelperFactory())
        try {
            val store = RoomAgencyStore(db)
            assertEquals("小雨", store.profile.get()!!.stageName)
            assertEquals("#8E2440", store.profile.get()!!.memberColor)
            assertEquals(listOf("早安"), store.messages.recent(10).map { it.text })
            assertEquals(listOf("s1"), store.signs.onDay("2026-11-20").map { it.id })
            assertTrue(store.nerve.insertSignal(StoredSignal("sig", "2026-11-22", 1, "woke_up", "{}")))
            assertTrue(store.nerve.insertEffect(NerveEffect("e", "2026-11-22", 2, "L1", "sig", EffectKind.ADJUST, "{}", "因為…所以…", EffectStatus.ACTIVE)))
            assertEquals(1, store.nerve.effects("2026-11-22").size)
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun withoutTheMigrationTheFileDoesNotOpenAndIsKept() {
        writeV1()
        val before = file.length()
        val db = Room.databaseBuilder(context, AgencyDatabase::class.java, file.path).openHelperFactory(FrameworkSQLiteOpenHelperFactory()).build()
        try {
            db.openHelper.writableDatabase
            fail("v1 must not open as v2 without a migration")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
        assertTrue(file.exists() && file.length() >= before)
    }

    @Test fun theMigrationSqlIsWhatTheV2SchemaSays() {
        val v2 = schema(2).getJSONArray("entities")
        val expected = (0 until v2.length()).map { v2.getJSONObject(it) }.filter { it.getString("tableName") in setOf("signal", "nerve_effect") }
            .flatMap { e ->
                val t = e.getString("tableName")
                val idx = e.optJSONArray("indices")
                listOf(e.getString("createSql").replace("\${TABLE_NAME}", t)) +
                    (0 until (idx?.length() ?: 0)).map { idx!!.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", t) }
            }
        assertEquals(expected, AgencyDatabase.MIGRATION_1_2_SQL)
        // v1's 27 tables are all still in v2.
        val v1Tables = schema(1).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tableName") } }.toSet()
        val v2Tables = (0 until v2.length()).map { v2.getJSONObject(it).getString("tableName") }.toSet()
        assertTrue((v1Tables - v2Tables).isEmpty())
        assertEquals(setOf("signal", "nerve_effect"), v2Tables - v1Tables)
    }
}
