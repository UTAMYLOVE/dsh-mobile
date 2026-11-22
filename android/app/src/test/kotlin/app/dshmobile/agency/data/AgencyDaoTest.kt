package app.dshmobile.agency.data

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.AuditLog
import app.agency.core.model.CompanyRules
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.MealOrder
import app.agency.core.model.MemoryItem
import app.agency.core.model.Message
import app.agency.core.model.MessageRefs
import app.agency.core.model.Nudge
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.model.Profile
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.RoleKey
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.WaterLog
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.dshmobile.agency.data.entities.AuditLogEntity
import app.dshmobile.agency.data.entities.CompanyRulesHistoryEntity
import app.dshmobile.agency.data.entities.FoodEntity
import app.dshmobile.agency.data.entities.MealEntity
import app.dshmobile.agency.data.entities.MealItemEntity
import app.dshmobile.agency.data.entities.MealOrderEntity
import app.dshmobile.agency.data.entities.MemoryItemEntity
import app.dshmobile.agency.data.entities.MessageEntity
import app.dshmobile.agency.data.entities.NudgeEntity
import app.dshmobile.agency.data.entities.PendingLabelEntity
import app.dshmobile.agency.data.entities.ProfileEntity
import app.dshmobile.agency.data.entities.ProposalEntity
import app.dshmobile.agency.data.entities.RulesColumns
import app.dshmobile.agency.data.entities.SearchResultEntity
import app.dshmobile.agency.data.entities.WaterLogEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One test per aggregate's DAO, for what only SQL can get wrong: conflict handling, cascades, column encodings, ordering, and the
 * exported schema. In-memory Room on plain SQLite (the framework factory is injected instead of SQLCipher's). Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgencyDaoTest {
    private val db = AgencyDatabase.build(ApplicationProvider.getApplicationContext(), null, FrameworkSQLiteOpenHelperFactory())

    @After fun close() = db.close()

    private fun one(sql: String): String? = db.openHelper.readableDatabase.query(sql).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun item(name: String) = MealItem(name, 1.0, 100, 10.0, null, null, fried = false, source = KcalSource.LABEL)

    @Test fun profileIsOneRowWithJsonNames() = runBlocking {
        val dao = db.profile()
        dao.upsert(ProfileEntity.of(Profile(idolNames = listOf("BABYMONSTER"))))
        dao.upsert(ProfileEntity.of(Profile(heightCm = 170.0, idolNames = listOf("BABYMONSTER", "DESERVE"))))
        assertEquals("1", one("SELECT COUNT(*) FROM profile"))
        assertEquals("""["BABYMONSTER","DESERVE"]""", one("SELECT idol_names FROM profile"))
        assertEquals(170.0, dao.get()!!.heightCm)
    }

    @Test fun aRulesVersionCannotBeWrittenTwice() = runBlocking {
        val row = CompanyRulesHistoryEntity(RulesColumns.of(CompanyRules(version = 1, updatedAt = 5)))
        db.rules().addHistory(row)
        try {
            db.rules().addHistory(row)
            fail("a second history row for version 1 must be refused")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
        }
        assertEquals("[75.0,68.0]", one("SELECT goals_kg FROM company_rules_history"))
    }

    @Test fun mealsIgnoreADuplicateIdAndTheirItemsGoWithThem() = runBlocking {
        val dao = db.meals()
        val meal = Meal("m-1", "2026-11-23", 1, text = "雞胸便當", breaches = listOf("FRIED"))
        assertTrue(dao.insert(MealEntity.of(meal)) != -1L)
        assertEquals(-1L, dao.insert(MealEntity.of(meal.copy(text = "別的"))))
        dao.insertItems(listOf(MealItemEntity.of("m-1", 0, item("雞胸")), MealItemEntity.of("m-1", 1, item("地瓜"))))
        dao.putPending(PendingLabelEntity.of(PendingLabel("m-1", 1, "地瓜", 100, 2)))
        assertEquals("LABEL", one("SELECT source FROM meal_item WHERE idx = 0"))
        assertEquals("""["FRIED"]""", one("SELECT breaches FROM meal"))
        assertEquals("2", one("SELECT COUNT(*) FROM meal_item"))
        dao.delete("m-1")
        assertEquals("0", one("SELECT COUNT(*) FROM meal_item"))
        assertNull(one("SELECT id FROM meal"))
    }

    @Test fun foodIsOrderedByNameAndADuplicateIsIgnored() = runBlocking {
        val dao = db.food()
        val e = FoodEntry("蛋", listOf("水煮蛋"), 70, 6.0, 5.0, 0.5, fried = false, estimated = false, seeded = true, addedAt = 1)
        dao.insert(FoodEntity.of(e))
        assertEquals(-1L, dao.insert(FoodEntity.of(e.copy(kcal = 1))))
        dao.insert(FoodEntity.of(e.copy(name = "地瓜")))
        assertEquals(listOf("地瓜", "蛋"), dao.all().map { it.name })
        assertEquals("70", one("SELECT kcal FROM food WHERE name = '蛋'"))
    }

    @Test fun anOrderStatusIsStoredByName() = runBlocking {
        val dao = db.orders()
        dao.insert(MealOrderEntity.of(MealOrder("o-1", "2026-11-23", 9, "DINNER", listOf("烤雞腿"), 714, 48.0)))
        assertEquals(1, dao.setStatus("o-1", OrderStatus.CONFIRMED))
        assertEquals(0, dao.setStatus("o-2", OrderStatus.CONFIRMED))
        assertEquals("CONFIRMED", one("SELECT status FROM meal_order"))
        assertEquals("9", one("SELECT created_at FROM meal_order"))
    }

    @Test fun pruningWithNothingToKeepRemovesEveryOldResult() = runBlocking {
        val dao = db.places()
        listOf(1L, 2L, 10L).forEach { t ->
            dao.putResult(SearchResultEntity.of(SearchResult("r-$t", SearchKind.PLACE, "q", "https://example.test/$t", "店", null, null, null, null, t)))
        }
        assertEquals(2, dao.pruneResults(cutoff = 5, keep = emptyList()))
        assertEquals(listOf("r-10"), dao.results(SearchKind.PLACE).map { it.id })
    }

    @Test fun dayLogsAreOrderedByTimeThenId() = runBlocking {
        val dao = db.water()
        dao.insert(WaterLogEntity.of(WaterLog("w-b", "2026-11-23", 5, 1)))
        dao.insert(WaterLogEntity.of(WaterLog("w-a", "2026-11-23", 5, 1)))
        dao.insert(WaterLogEntity.of(WaterLog("w-0", "2026-11-23", 1, 2)))
        dao.insert(WaterLogEntity.of(WaterLog("w-x", "2026-11-24", 0, 2)))
        assertEquals(listOf("w-0", "w-a", "w-b"), dao.onDay("2026-11-23").map { it.id })
        assertEquals(4, dao.count())
    }

    @Test fun recentMessagesAreTheNewestInTimeOrderWithJsonRefs() = runBlocking {
        val dao = db.messages()
        (1..5).forEach { i -> dao.insert(MessageEntity.of(Message("m$i", i * 10L, Who.OWNER, text = "第 $i 句"))) }
        dao.insert(MessageEntity.of(Message("r", 15, Who.COMPANY, RoleKey.MANAGER, text = "好", refs = MessageRefs(proposal = "p-1"), wordedBy = WordedBy.TEMPLATE)))
        assertEquals(listOf("m4", "m5"), dao.recent(2).map { it.id })
        val refs = Json.parseToJsonElement(one("SELECT refs FROM message WHERE id = 'r'")!!).jsonObject
        assertEquals("p-1", refs["proposal"]!!.jsonPrimitive.content)
        assertEquals("MANAGER", one("SELECT role FROM message WHERE id = 'r'"))
    }

    @Test fun proposalsAreFoundByStatus() = runBlocking {
        val dao = db.proposals()
        dao.insert(ProposalEntity.of(Proposal("p-1", 1, RoleKey.BODY, ProposalKind.PROGRESSION, "TRAINING", "{}", "多一下")))
        assertEquals("PENDING", one("SELECT status FROM proposal"))
        assertEquals("{}", one("SELECT diff FROM proposal"))
    }

    @Test fun memoryItemsWithoutABlockFilterSkipOnlyDeletedOnes() = runBlocking {
        val dao = db.memory()
        dao.putItem(MemoryItemEntity.of(MemoryItem("a", "LIKES", "豆漿", 1, 1, null)))
        dao.putItem(MemoryItemEntity.of(MemoryItem("b", "HEALTH", "膝蓋", 2, 2, null)))
        dao.putItem(MemoryItemEntity.of(MemoryItem("c", "HEALTH", "舊的", 3, 3, null, deleted = true)))
        assertEquals(listOf("a", "b"), dao.items(null).map { it.id })
        assertEquals(listOf("b"), dao.items("HEALTH").map { it.id })
    }

    @Test fun aNudgeKeepsItsGapKeysAsJson() = runBlocking {
        val dao = db.nudges()
        dao.insert(NudgeEntity.of(Nudge("n", 1, listOf("WATER"), "slot", null, "喝水", WordedBy.MODEL)))
        assertEquals("""["WATER"]""", one("SELECT gap_keys FROM nudge"))
        assertEquals(1, dao.markAnswered("n", 2))
        assertEquals("2", one("SELECT answered_at FROM nudge"))
    }

    @Test fun anAuditRowWithTheSameIdIsReplaced() = runBlocking {
        val dao = db.journal()
        dao.audit(AuditLogEntity.of(AuditLog("a", 1, "FOOD_FIX", "舊")))
        dao.audit(AuditLogEntity.of(AuditLog("a", 1, "FOOD_FIX", "新")))
        assertEquals(listOf("新"), dao.audits().map { it.detail })
    }

    @Test fun theExportedSchemaV1HasEveryTableAndColumnOfTheDataModel() {
        val schema = Json.parseToJsonElement(File("schemas/app.dshmobile.agency.data.AgencyDatabase/1.json").readText()).jsonObject
        val database = schema["database"]!!.jsonObject
        assertEquals(1, database["version"]!!.jsonPrimitive.content.toInt())
        val exported = database["entities"]!!.jsonArray.associate { e ->
            val o = e.jsonObject
            o["tableName"]!!.jsonPrimitive.content to Table(
                columns = o["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet(),
                key = o["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(TABLES.keys, exported.keys)
        for ((name, table) in TABLES) assertEquals("exported $name", table, exported[name])

        val sql = db.openHelper.readableDatabase
        val live = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue("live tables: $live", live.containsAll(TABLES.keys))
        for ((name, table) in TABLES) {
            val info = sql.query("PRAGMA table_info(`$name`)").use { c ->
                val nameAt = c.getColumnIndexOrThrow("name")
                val pkAt = c.getColumnIndexOrThrow("pk")
                buildList { while (c.moveToNext()) add(c.getString(nameAt) to c.getInt(pkAt)) }
            }
            val liveTable = Table(info.map { it.first }.toSet(), info.filter { it.second > 0 }.sortedBy { it.second }.map { it.first })
            // Later schemas only add (v3: the 「沒送出」 mark); every v1 column is still there.
            assertEquals("live $name", table.copy(columns = table.columns + ADDED[name].orEmpty()), liveTable)
        }
    }

    private data class Table(val columns: Set<String>, val key: List<String>)

    private companion object {
        private fun t(key: String, columns: String) = t(listOf(key), columns)
        private fun t(key: List<String>, columns: String) = Table(columns.split(",").map { it.trim() }.toSet(), key)

        /** Columns later schemas added to a v1 table (v3, T12). */
        val ADDED = mapOf("message" to setOf("unsent"))

        /** Arch 1.2, every table with its columns and primary key (plus `id` on the one-row tables). */
        val TABLES = mapOf(
            "profile" to t(
                "id",
                "id, height_cm, start_weight_kg, stage_name, member_color, company_name, trainee_no, join_date, level, area_text, " +
                    "idol_names, day_start_hour, onboarding_done",
            ),
            "company_rules" to t("id", "id, $RULES"),
            "company_rules_history" to t("version", RULES),
            "plan_section" to t("section", "section, json, version, updated_at, changed_by"),
            "plan_section_history" to t(listOf("section", "version"), "section, json, version, updated_at, changed_by"),
            "day_override" to t("day", "day, rest_day, cheat_slot, soy_morning, declared_at"),
            "meal" to t("id", "id, day, slot_key, at, text, cheat, breaches, verified, photo_media_id, order_id, legacy"),
            "meal_item" to t(
                listOf("meal_id", "idx"),
                "meal_id, idx, name, portion, kcal, protein_g, fat_g, carbs_g, fried, processed, zero_cal, source, table_estimated, " +
                    "estimate_high",
            ),
            "food" to t(
                "name",
                "name, aliases, kcal, protein_g, fat_g, carbs_g, fried, processed, estimated, seeded, added_at, updated_at",
            ),
            "pending_label" to t(listOf("meal_id", "item_idx"), "meal_id, item_idx, name, counted_kcal, asked_at"),
            "meal_order" to t("id", "id, day, slot_key, items, kcal, protein_g, place_result_id, status, created_at"),
            "favorite_place" to t("id", "id, title, url, area, note, saved_at, source_result_id"),
            "search_result" to t("id", "id, kind, query, url, title, summary, date, authority, rerank, fetched_at"),
            "water_log" to t("id", "id, day, at, half_bottles, photo_media_id"),
            "training_log" to t("id", "id, day, at, kind, rounds, detail, reduced, verified, legacy"),
            "condition_log" to t("day", "day, at, score, sleep, energy, ill, note"),
            "sign_log" to t("id", "id, day, kind, at, on_time, review_good, review_fix"),
            "weight_log" to t("id", "id, day, at, kg, waist_cm"),
            "baseline" to t(
                "id",
                "id, height_cm, weight_kg, photo_front, photo_side, photo_back, recording, pushups_max, plank_max_sec, skipped, step, " +
                    "completed_at",
            ),
            "media" to t("id", "id, kind, purpose, mime, bytes, sha256, created_at"),
            "message" to t("id", "id, at, who, role, kind, mode, text, refs, worded_by, legacy"),
            "proposal" to t("id", "id, created_at, role, kind, target, diff, reason, status, decided_at, applied_version"),
            "memory_item" to t("id", "id, block, text, created_at, updated_at, source_message_id, deleted, due_day"),
            "memory_event" to t("id", "id, at, op, block, item_id, before, after, source_message_id, undone, before_due, after_due"),
            "nudge" to t("id", "id, at, gap_keys, channel, notification_id, text, worded_by, answered_at"),
            "migration_log" to t("id", "id, at, source, counts, ok, note"),
            "audit_log" to t("id", "id, at, what, detail"),
        )

        private const val RULES = "quota_kcal, protein_g, fat_g, carbs_g, floor_kcal, meal_cap_kcal, cheat_meals_per_week, " +
            "zero_cal_per_week, margin_pct, banned, goals_kg, version, updated_at"
    }
}
