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

    /** An empty file of schema [version], built from its checked-in JSON. */
    private fun create(version: Int): SQLiteDatabase {
        val v = schema(version)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = v.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = v.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    /** The v1 file as the T02 build left it, with a profile, a message and a sign-in. */
    private fun writeV1() {
        val db = create(1)
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
            assertEquals(6, db.openHelper.readableDatabase.version) // on to the newest (v6)
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

    /** The v2 file as batch 2 (T11b) left it: a line of his and one of the manager's, and a signal. */
    private fun writeV2() {
        val db = create(2)
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy) VALUES ('m1', 5, 'OWNER', NULL, NULL, 'NORMAL', '姐姐在嗎', '{}', NULL, 0)")
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy) VALUES ('m2', 6, 'COMPANY', 'MANAGER', 'chat', 'NORMAL', '在。', '{}', 'MODEL', 0)")
        db.execSQL("INSERT INTO signal (id, day, at, type, json, depth, cause_id) VALUES ('day:2026-11-22', '2026-11-22', 7, 'day_started', '{}', 0, NULL)")
        db.close()
    }

    @Test fun aVersionTwoFileMigratesToThreeEveryLineSentAndThinkingWorks() = runBlocking {
        writeV2()
        val db = AgencyDatabase.build(context, file.path, FrameworkSQLiteOpenHelperFactory())
        try {
            val store = RoomAgencyStore(db)
            val lines = store.messages.recent(10)
            assertEquals(listOf("姐姐在嗎", "在。"), lines.map { it.text })
            assertTrue(lines.none { it.unsent })
            assertTrue(store.nerve.signal("day:2026-11-22") != null)
            store.messages.upsert(lines[0].copy(unsent = true))
            assertTrue(store.messages.get("m1")!!.unsent)
            store.thinking.put(app.agency.core.model.StoredThinking("m2", 6, "DEEPSEEK", "OPENAI", "想一下"))
            assertEquals("想一下", store.thinking.of(listOf("m2")).single().payload)
            assertEquals(6, db.openHelper.readableDatabase.version) // on to the newest (v6)
        } finally {
            db.close()
        }
    }

    @Test fun theMigrationToThreeIsWhatTheV3SchemaSays() {
        val v3 = schema(3).getJSONArray("entities")
        val byName = (0 until v3.length()).map { v3.getJSONObject(it) }.associateBy { it.getString("tableName") }
        val thinking = byName.getValue("thinking")
        val idx = thinking.getJSONArray("indices")
        val expected = listOf(thinking.getString("createSql").replace("\${TABLE_NAME}", "thinking")) +
            (0 until idx.length()).map { idx.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", "thinking") }
        assertEquals(expected, AgencyDatabase.MIGRATION_2_3_SQL.drop(1))
        val unsent = byName.getValue("message").getJSONArray("fields").let { f -> (0 until f.length()).map { f.getJSONObject(it) } }.single { it.getString("columnName") == "unsent" }
        assertEquals("0", unsent.getString("defaultValue"))
        assertTrue(unsent.getBoolean("notNull"))
        assertEquals("ALTER TABLE `message` ADD COLUMN `unsent` INTEGER NOT NULL DEFAULT 0", AgencyDatabase.MIGRATION_2_3_SQL.first())
        val v2Tables = schema(2).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tableName") } }.toSet()
        assertEquals(setOf("thinking"), byName.keys - v2Tables)
        assertTrue((v2Tables - byName.keys).isEmpty())
    }

    /** The v3 file as batches 3–5 left it: a line, a stored search result of a place, the manager's thinking. */
    private fun writeV3() {
        val db = create(3)
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy, unsent) VALUES ('m1', 5, 'OWNER', NULL, NULL, 'NORMAL', '找影片', '{}', NULL, 0, 0)")
        db.execSQL("INSERT INTO search_result (id, kind, query, url, title, summary, date, authority, rerank, fetched_at) VALUES ('r1', 'PLACE', '大安區 雞胸 推薦', 'https://a.example/1', '甲店', '摘要', NULL, 2.0, 2.0, 7)")
        db.execSQL("INSERT INTO thinking (message_id, at, provider, wire, payload) VALUES ('m1', 5, 'DEEPSEEK', 'OPENAI', '想')")
        db.close()
    }

    @Test fun aVersionThreeFileMigratesToFourAndTheLessonTablesWork() = runBlocking {
        writeV3()
        val db = AgencyDatabase.build(context, file.path, FrameworkSQLiteOpenHelperFactory())
        try {
            val store = RoomAgencyStore(db)
            assertEquals(listOf("找影片"), store.messages.recent(10).map { it.text })
            assertEquals("甲店", store.places.result("r1")!!.title)
            assertEquals(1, store.thinking.count())
            assertTrue(store.lessons.all().isEmpty())
            val l = app.agency.core.model.Lesson("l1", "r1", app.agency.core.model.LessonCategory.VOCAL, null, "發聲", "https://a.example/v", "教材", foundAt = 9)
            assertTrue(store.lessons.insert(l))
            assertEquals(l, store.lessons.get("l1"))
            store.lessons.putQuiz(app.agency.core.model.Quiz("q1", "l1", app.agency.core.model.QuizKind.MAIN, 10, emptyList(), shown = 0))
            assertEquals(listOf("q1"), store.lessons.quizzes("l1").map { it.id })
            assertEquals(6, db.openHelper.readableDatabase.version) // on to the newest (v6)
        } finally {
            db.close()
        }
    }

    @Test fun theMigrationToFourIsWhatTheV4SchemaSays() {
        val v4 = schema(4).getJSONArray("entities")
        val byName = (0 until v4.length()).map { v4.getJSONObject(it) }.associateBy { it.getString("tableName") }
        val expected = listOf("lesson", "quiz").flatMap { t ->
            val e = byName.getValue(t)
            val idx = e.getJSONArray("indices")
            listOf(e.getString("createSql").replace("\${TABLE_NAME}", t)) +
                (0 until idx.length()).map { idx.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", t) }
        }
        assertEquals(expected, AgencyDatabase.MIGRATION_3_4_SQL)
        val v3Tables = schema(3).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tableName") } }.toSet()
        assertEquals(setOf("lesson", "quiz"), byName.keys - v3Tables)
        assertTrue((v3Tables - byName.keys).isEmpty())
        // Every v3 table is unchanged in v4 (nothing else was altered).
        val v3 = schema(3).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("tableName") }
        v3.forEach { (t, e) -> assertEquals(t, e.getString("createSql"), byName.getValue(t).getString("createSql")) }
    }

    @Test fun withoutTheMigrationAVersionThreeFileDoesNotOpenAndIsKept() {
        writeV3()
        val before = file.length()
        val db = Room.databaseBuilder(context, AgencyDatabase::class.java, file.path).openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(AgencyDatabase.MIGRATION_1_2, AgencyDatabase.MIGRATION_2_3).build()
        try {
            db.openHelper.writableDatabase
            fail("v3 must not open as v4 without its migration")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
        assertTrue(file.exists() && file.length() >= before)
    }

    /** The v4 file as batch 5b left it: a line and a listed lesson. */
    private fun writeV4() {
        val db = create(4)
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy, unsent) VALUES ('m1', 5, 'OWNER', NULL, NULL, 'NORMAL', '在嗎', '{}', NULL, 0, 0)")
        db.execSQL(
            "INSERT INTO lesson (id, result_id, category, language, title, url, source, status, found_at, listed_at, opened_at, watched_at, key_points, " +
                "quiz_passed_at, note, passed_at, passed_day, score, retries, fallback, practical_media_id, review_day, review_score, reviewed_at) " +
                "VALUES ('l1', 'r1', 'VOCAL', NULL, '發聲', 'https://a.example/v', '教材', 'LISTED', 9, 10, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, NULL)",
        )
        db.close()
    }

    @Test fun aVersionFourFileMigratesToFiveAndTheIncomeTableWorks() = runBlocking {
        writeV4()
        val db = AgencyDatabase.build(context, file.path, FrameworkSQLiteOpenHelperFactory())
        try {
            val store = RoomAgencyStore(db)
            assertEquals(listOf("在嗎"), store.messages.recent(10).map { it.text })
            assertEquals("發聲", store.lessons.get("l1")!!.title)
            assertTrue(store.income.between("2026-01-01", "2026-12-31").isEmpty())
            store.income.put(app.agency.core.model.DeliveryIncome("2026-12-07", 900, 1))
            store.income.put(app.agency.core.model.DeliveryIncome("2026-12-07", 1000, 2)) // a second report replaces the first
            assertEquals(listOf(1000), store.income.between("2026-12-07", "2026-12-13").map { it.amount })
            assertEquals(6, db.openHelper.readableDatabase.version) // on to the newest (v6)
        } finally {
            db.close()
        }
    }

    @Test fun theMigrationToFiveIsWhatTheV5SchemaSays() {
        val v5 = schema(5).getJSONArray("entities")
        val byName = (0 until v5.length()).map { v5.getJSONObject(it) }.associateBy { it.getString("tableName") }
        val e = byName.getValue("delivery_income")
        assertEquals(listOf(e.getString("createSql").replace("\${TABLE_NAME}", "delivery_income")), AgencyDatabase.MIGRATION_4_5_SQL)
        val v4 = schema(4).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("tableName") }
        assertEquals(setOf("delivery_income"), byName.keys - v4.keys)
        // Every v4 table is unchanged in v5.
        v4.forEach { (t, x) -> assertEquals(t, x.getString("createSql"), byName.getValue(t).getString("createSql")) }
    }

    @Test fun withoutTheMigrationAVersionFourFileDoesNotOpenAndIsKept() {
        writeV4()
        val before = file.length()
        val db = Room.databaseBuilder(context, AgencyDatabase::class.java, file.path).openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(AgencyDatabase.MIGRATION_1_2, AgencyDatabase.MIGRATION_2_3, AgencyDatabase.MIGRATION_3_4).build()
        try {
            db.openHelper.writableDatabase
            fail("v4 must not open as v5 without its migration")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
        assertTrue(file.exists() && file.length() >= before)
    }

    /** The v5 file as batch A left it: a line, a meal and a day's income. */
    private fun writeV5() {
        val db = create(5)
        db.execSQL("INSERT INTO message (id, at, who, role, kind, mode, text, refs, worded_by, legacy, unsent) VALUES ('m1', 5, 'OWNER', NULL, NULL, 'NORMAL', '午安', '{}', NULL, 0, 0)")
        db.execSQL(
            "INSERT INTO meal (id, day, slot_key, at, text, cheat, breaches, verified, photo_media_id, order_id, legacy) " +
                "VALUES ('meal1', '2026-12-01', 'LUNCH', 7, '雞腿便當', 0, '[]', 0, NULL, NULL, 0)",
        )
        db.execSQL("INSERT INTO delivery_income (day, amount, at) VALUES ('2026-12-01', 900, 8)")
        db.close()
    }

    @Test fun aVersionFiveFileMigratesToSixAndTheNotebookTablesWork() = runBlocking {
        writeV5()
        val db = AgencyDatabase.build(context, file.path, FrameworkSQLiteOpenHelperFactory())
        try {
            val store = RoomAgencyStore(db)
            assertEquals(listOf("午安"), store.messages.recent(10).map { it.text })
            val meal = store.meals.get("meal1")!!
            assertEquals("雞腿便當", meal.text)
            assertEquals(null, meal.lateAt) // every old meal was handed in on time
            assertEquals(900, store.income.get("2026-12-01")!!.amount)
            store.memos.upsert(app.agency.core.model.Memo("memo1", 9, "買洗衣精"))
            assertEquals(listOf("買洗衣精"), store.memos.all().map { it.text })
            assertTrue(store.ledger.insert(app.agency.core.model.LedgerEntry("l1", "2026-12-01", 9, 120, "便當")))
            store.imports.upsert(app.agency.core.model.ImportBatch("b1", 9, app.agency.core.model.ImportTarget.LEDGER, "貼上", "{}", listOf("l1"), 0))
            store.photoReads.upsert(app.agency.core.model.PhotoRead("r1", 9, "m1", app.agency.core.model.PhotoReadKind.MENU, "{}"))
            store.meals.upsert(meal.copy(lateAt = 6))
            assertEquals(6L, store.meals.get("meal1")!!.lateAt)
            assertEquals(6, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun theMigrationToSixIsWhatTheV6SchemaSays() {
        val v6 = schema(6).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("tableName") }
        val v5 = schema(5).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("tableName") }
        val added = listOf("memo", "ledger_entry", "import_batch", "photo_read")
        assertEquals(added.toSet(), v6.keys - v5.keys)
        val expected = mutableListOf("ALTER TABLE `meal` ADD COLUMN `late_at` INTEGER")
        added.forEach { t ->
            val e = v6.getValue(t)
            expected += e.getString("createSql").replace("\${TABLE_NAME}", t)
            val idx = e.optJSONArray("indices")
            if (idx != null) for (j in 0 until idx.length()) expected += idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t)
        }
        assertEquals(expected, AgencyDatabase.MIGRATION_5_6_SQL)
        // Every other v5 table is unchanged; the meal gained only the one column at its end.
        v5.forEach { (t, x) -> if (t != "meal") assertEquals(t, x.getString("createSql"), v6.getValue(t).getString("createSql")) }
        assertEquals(v5.getValue("meal").getString("createSql").replace(", PRIMARY KEY(`id`))", ", `late_at` INTEGER, PRIMARY KEY(`id`))"), v6.getValue("meal").getString("createSql"))
    }

    @Test fun withoutTheMigrationAVersionFiveFileDoesNotOpenAndIsKept() {
        writeV5()
        val before = file.length()
        val db = Room.databaseBuilder(context, AgencyDatabase::class.java, file.path).openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(AgencyDatabase.MIGRATION_1_2, AgencyDatabase.MIGRATION_2_3, AgencyDatabase.MIGRATION_3_4, AgencyDatabase.MIGRATION_4_5).build()
        try {
            db.openHelper.writableDatabase
            fail("v5 must not open as v6 without its migration")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
        assertTrue(file.exists() && file.length() >= before)
    }
}
