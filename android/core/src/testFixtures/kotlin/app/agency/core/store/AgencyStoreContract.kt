package app.agency.core.store

import app.agency.core.model.AuditLog
import app.agency.core.model.Baseline
import app.agency.core.model.CompanyRules
import app.agency.core.model.ConditionLog
import app.agency.core.model.DayOverride
import app.agency.core.model.FavoritePlace
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.MealOrder
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.MessageRefs
import app.agency.core.model.MigrationLog
import app.agency.core.model.MigrationSource
import app.agency.core.model.Nudge
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The behaviour every [AgencyStore] must have. `InMemoryAgencyStoreTest` (:core) and `RoomAgencyStoreTest` (:app) run it.
 * All data is invented.
 */
abstract class AgencyStoreContract {
    protected abstract fun newStore(): AgencyStore

    private fun test(body: suspend AgencyStore.() -> Unit) = runBlocking { newStore().body() }

    private fun item(name: String, kcal: Int, source: KcalSource = KcalSource.TABLE) = MealItem(
        name = name, portion = 1.5, kcal = kcal, proteinG = 20.5, fatG = null, carbsG = 30.0, fried = true, source = source,
        tableEstimated = true, estimateHigh = 610, zeroCal = false, processed = true,
    )

    private fun meal(id: String, day: String, at: Long, vararg items: MealItem) = Meal(
        id = id, day = day, at = at, slotKey = "LUNCH", text = "雞腿便當加青菜", items = items.toList(), cheat = false,
        breaches = listOf("FRIED", "MEAL_CAP_700_LEGACY"), verified = true, photoMediaId = "m-1", orderId = "o-1", legacy = true,
    )

    @Test fun profileIsEmptyThenRoundTrips() = test {
        assertNull(profile.get())
        val p = Profile(
            heightCm = 171.5, startWeightKg = 79.2, stageName = "小雨", memberColor = "#FFB7C5", companyName = "小雨娛樂",
            traineeNo = "0001", joinDate = "2026-11-21", level = "C", areaText = "某區", idolNames = listOf("BABYMONSTER", "DESERVE"),
            dayStartHour = 5, onboardingDone = true,
        )
        profile.put(p)
        assertEquals(p, profile.get())
        profile.put(p.copy(areaText = null, idolNames = emptyList()))
        assertEquals(p.copy(areaText = null, idolNames = emptyList()), profile.get())
    }

    @Test fun rulesGetAVersionPerWriteAndKeepTheirHistory() = test {
        assertNull(rules.current())
        val first = rules.put(CompanyRules(banned = listOf("FRIED", "PROCESSED")), at = 100)
        assertEquals(1, first.version)
        assertEquals(100, first.updatedAt)
        val second = rules.put(first.copy(quotaKcal = 1800, goalsKg = listOf(68.0)), at = 200)
        assertEquals(2, second.version)
        assertEquals(second, rules.current())
        assertEquals(listOf(first, second), rules.history())
        assertEquals(listOf(75.0, 68.0), first.goalsKg)
    }

    @Test fun planSectionsGetAVersionPerKeyAndKeepTheirHistory() = test {
        assertNull(rules.section(PlanSectionKey.MEALS))
        val m1 = rules.putSection(PlanSectionKey.MEALS, """{"a":1}""", PlanSection.BY_MIGRATION, 10)
        val w1 = rules.putSection(PlanSectionKey.WATER, """{"w":1}""", PlanSection.BY_OWNER_SETTINGS, 11)
        val m2 = rules.putSection(PlanSectionKey.MEALS, """{"a":2}""", "p-42", 12)
        assertEquals(PlanSection(PlanSectionKey.MEALS, """{"a":2}""", 2, 12, "p-42"), m2)
        assertEquals(1, w1.version)
        assertEquals(m2, rules.section(PlanSectionKey.MEALS))
        assertEquals(listOf(m2, w1), rules.sections())
        assertEquals(listOf(m1, m2), rules.sectionHistory(PlanSectionKey.MEALS))
    }

    @Test fun dayOverridesAreOnePerDay() = test {
        val d = DayOverride("2026-11-25", restDay = true, cheatSlot = "DINNER", soyMorning = true, declaredAt = 5)
        days.put(d)
        days.put(DayOverride("2026-11-27", declaredAt = 6))
        days.put(d.copy(restDay = false))
        assertEquals(d.copy(restDay = false), days.get("2026-11-25"))
        assertEquals(listOf("2026-11-25", "2026-11-27"), days.between("2026-11-24", "2026-11-30").map { it.day })
        assertTrue(days.delete("2026-11-25"))
        assertFalse(days.delete("2026-11-25"))
        assertNull(days.get("2026-11-25"))
    }

    @Test fun mealsKeepTheirItemsAndAnInsertNeverOverwrites() = test {
        val m = meal("meal-1", "2026-11-23", 1_000, item("雞腿", 600), item("燙青菜", 60, KcalSource.ESTIMATE))
        assertTrue(meals.insert(m))
        assertEquals(m, meals.get("meal-1"))
        assertFalse(meals.insert(m.copy(text = "別的", items = emptyList())))
        assertEquals(m, meals.get("meal-1"))
        assertEquals(2, meals.countItems())
        val changed = m.copy(text = "只有雞腿", items = listOf(item("雞腿", 600)), verified = false, photoMediaId = null)
        meals.upsert(changed)
        assertEquals(changed, meals.get("meal-1"))
        assertEquals(1, meals.countItems())
        assertEquals(600, meals.get("meal-1")!!.kcal)
    }

    @Test fun mealsAreListedPerDayInTimeOrder() = test {
        meals.insert(meal("b", "2026-11-23", 3_000))
        meals.insert(meal("a", "2026-11-23", 2_000))
        meals.insert(meal("c", "2026-11-24", 1_000))
        meals.insert(meal("d", "2026-11-26", 1_000))
        assertEquals(listOf("a", "b"), meals.onDay("2026-11-23").map { it.id })
        assertEquals(listOf("c", "a", "b"), meals.between("2026-11-23", "2026-11-24").map { it.id })
        assertEquals(listOf("c", "d", "a", "b"), meals.between("2026-11-23", "2026-11-26").map { it.id })
        assertTrue(meals.between("2026-11-25", "2026-11-25").isEmpty())
        assertEquals(4, meals.count())
    }

    @Test fun deletingAMealDeletesItsItemsAndPendingLabels() = test {
        meals.insert(meal("meal-1", "2026-11-23", 1_000, item("滷味", 400, KcalSource.ESTIMATE)))
        meals.insert(meal("meal-2", "2026-11-23", 2_000, item("關東煮", 300, KcalSource.ESTIMATE)))
        meals.putPending(PendingLabel("meal-1", 0, "滷味", 400, 1_100))
        meals.putPending(PendingLabel("meal-2", 0, "關東煮", 300, 2_100))
        meals.putPending(PendingLabel("meal-2", 0, "關東煮", 330, 2_100))
        assertEquals(listOf(400, 330), meals.pending().map { it.countedKcal })
        assertTrue(meals.delete("meal-1"))
        assertNull(meals.get("meal-1"))
        assertEquals(1, meals.countItems())
        assertEquals(listOf("meal-2"), meals.pending().map { it.mealId })
        assertTrue(meals.deletePending("meal-2", 0))
        assertFalse(meals.deletePending("meal-2", 0))
        assertTrue(meals.pending().isEmpty())
    }

    @Test fun foodIsKeyedByName() = test {
        val e = FoodEntry("即食雞胸", listOf("雞胸肉"), 117, 24.0, 1.5, null, fried = false, estimated = true, seeded = true,
            addedAt = 10, processed = true, updatedAt = 20)
        assertTrue(food.insert(e))
        assertFalse(food.insert(e.copy(kcal = 999)))
        assertEquals(e, food.get("即食雞胸"))
        food.upsert(e.copy(kcal = 120, updatedAt = 30))
        assertEquals(120, food.get("即食雞胸")!!.kcal)
        food.insert(FoodEntry("豆漿", emptyList(), 193, 15.0, null, null, false, false, true, 5))
        assertEquals(2, food.count())
        assertEquals(setOf("即食雞胸", "豆漿"), food.all().map { it.name }.toSet())
        assertTrue(food.delete("豆漿"))
        assertNull(food.get("豆漿"))
    }

    @Test fun ordersChangeStatus() = test {
        val o = MealOrder("o-1", "2026-11-23", 50, "DINNER", listOf("烤雞腿", "燙青菜"), 714, 48.0, placeResultId = "r-1")
        assertTrue(orders.insert(o))
        assertTrue(orders.setStatus("o-1", OrderStatus.EATEN))
        assertFalse(orders.setStatus("o-9", OrderStatus.EATEN))
        assertEquals(o.copy(status = OrderStatus.EATEN), orders.get("o-1"))
        assertEquals(listOf(o.copy(status = OrderStatus.EATEN)), orders.onDay("2026-11-23"))
    }

    @Test fun placesAndTheSearchCache() = test {
        val f = FavoritePlace("f-1", "某某便當", "https://example.test/a", "某區", "少飯", 70, sourceResultId = "r-1")
        assertTrue(places.insertFavorite(f))
        assertFalse(places.insertFavorite(f.copy(title = "x")))
        assertEquals(listOf(f), places.favorites())
        val old = SearchResult("r-1", SearchKind.PLACE, "雞胸 某區", "https://example.test/a", "某某便當", "摘要", "2026-11-01", 0.8, 0.5, 100)
        val stale = old.copy(id = "r-2", fetchedAt = 200)
        val fresh = old.copy(id = "r-3", fetchedAt = 900)
        val video = old.copy(id = "r-4", kind = SearchKind.VIDEO, fetchedAt = 50, summary = null, date = null, authority = null, rerank = null)
        listOf(old, stale, fresh, video).forEach { places.putResult(it) }
        assertEquals(video, places.result("r-4"))
        assertEquals(listOf("r-1", "r-2", "r-3"), places.results(SearchKind.PLACE).map { it.id })
        assertEquals(2, places.pruneResults(cutoff = 500, keep = setOf("r-1")))
        assertEquals(listOf("r-1", "r-3"), places.results(SearchKind.PLACE).map { it.id })
        assertNull(places.result("r-4"))
        assertTrue(places.deleteFavorite("f-1"))
        assertTrue(places.favorites().isEmpty())
    }

    @Test fun dayLogsRoundTrip() = test {
        val w = WaterLog("w-1", "2026-11-23", 10, halfBottles = 2, photoMediaId = "m-2")
        val t = TrainingLog("t-1", "2026-11-23", 20, TrainingKind.STRENGTH, rounds = 3, detailJson = """{"pushups":[15,15,14]}""",
            reduced = true, verified = true, legacy = true)
        val s = SignLog("s-1", "2026-11-23", 30, SignKind.OUT, onTime = false, reviewGood = "有喝水", reviewFix = "早點睡")
        val k = WeightLog("k-1", "2026-11-23", 40, 78.4, waistCm = 88.5)
        assertTrue(water.insert(w)); assertTrue(training.insert(t)); assertTrue(signs.insert(s)); assertTrue(weights.insert(k))
        assertFalse(water.insert(w.copy(halfBottles = 9)))
        assertEquals(listOf(w), water.onDay("2026-11-23"))
        assertEquals(listOf(t), training.between("2026-11-20", "2026-11-23"))
        assertEquals(s, signs.get("s-1"))
        assertEquals(k, weights.get("k-1"))
        weights.upsert(k.copy(kg = 78.0, waistCm = null))
        assertEquals(k.copy(kg = 78.0, waistCm = null), weights.get("k-1"))
        assertTrue(training.onDay("2026-11-24").isEmpty())
        assertTrue(signs.delete("s-1"))
        assertEquals(0, signs.count())
        assertEquals(1, water.count())
    }

    @Test fun conditionIsOnePerDay() = test {
        val c = ConditionLog("2026-11-23", 7, score = 4, sleep = 3, energy = 2, ill = true, note = "喉嚨痛")
        conditions.put(c)
        conditions.put(c.copy(score = 2, note = null))
        conditions.put(ConditionLog("2026-11-24", 8, score = 5))
        assertEquals(c.copy(score = 2, note = null), conditions.get("2026-11-23"))
        assertEquals(2, conditions.count())
        assertEquals(listOf("2026-11-24"), conditions.between("2026-11-24", "2026-11-30").map { it.day })
    }

    @Test fun baselinesAndMediaRoundTrip() = test {
        val b = Baseline(Baseline.JOIN, 171.0, 79.0, "m-f", "m-s", "m-b", "m-r", 12, 45, listOf("PLANK"), "PUSHUPS", 99)
        assertTrue(baselines.insert(b))
        assertFalse(baselines.insert(b.copy(weightKg = 1.0)))
        assertEquals(b, baselines.get("join"))
        baselines.upsert(b.copy(step = null, skipped = emptyList()))
        assertEquals(listOf(b.copy(step = null, skipped = emptyList())), baselines.all())
        val m = Media("m-f", MediaKind.PHOTO, MediaPurpose.BASELINE, "image/jpeg", 123_456, "ab".repeat(32), 77)
        assertTrue(media.insert(m))
        assertFalse(media.insert(m.copy(bytes = 1)))
        assertEquals(m, media.get("m-f"))
        assertEquals(1, media.count())
        assertTrue(media.delete("m-f"))
        assertTrue(media.all().isEmpty())
    }

    @Test fun messagesComeOldestFirstAndRecentTakesTheNewest() = test {
        val refs = MessageRefs(records = listOf("meal-1"), results = listOf("r-1"), proposal = "p-1")
        val m1 = Message("m1", 100, Who.OWNER, text = "午餐吃雞腿便當", kind = "meal")
        val m2 = Message("m2", 200, Who.COMPANY, RoleKey.BODY, "meal", MessageMode.DISCUSS, "收到。", refs, WordedBy.MODEL, legacy = true)
        val m3 = Message("m3", 300, Who.SYSTEM, text = "提醒")
        listOf(m3, m1, m2).forEach { assertTrue(messages.insert(it)) }
        assertFalse(messages.insert(m1.copy(text = "改掉")))
        assertEquals(m2, messages.get("m2"))
        assertEquals(listOf(m2, m3), messages.recent(2))
        assertEquals(listOf(m1, m2, m3), messages.recent(10))
        assertEquals(listOf(m1, m2), messages.between(100, 300))
        assertEquals(3, messages.count())
    }

    @Test fun proposalsByStatus() = test {
        val p = Proposal("p-1", 10, RoleKey.MANAGER, ProposalKind.PLAN_CHANGE, "SCHEDULE", """{"wake":"06:30"}""", "早點起床")
        assertTrue(proposals.insert(p))
        proposals.insert(p.copy(id = "p-2", createdAt = 20))
        proposals.upsert(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = 30, appliedVersion = 2))
        assertEquals(listOf("p-2"), proposals.withStatus(ProposalStatus.PENDING).map { it.id })
        assertEquals(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = 30, appliedVersion = 2), proposals.get("p-1"))
        assertEquals(2, proposals.count())
    }

    @Test fun memoryItemsHideDeletedOnesAndEventsComeNewestFirst() = test {
        val a = MemoryItem("i-1", "LIKES", "喜歡無糖豆漿", 1, 1, "m1")
        val b = MemoryItem("i-2", "HEALTH", "膝蓋舊傷", 2, 2, null)
        memory.putItem(a); memory.putItem(b); memory.putItem(MemoryItem("i-3", "LIKES", "不吃香菜", 3, 4, "m2", deleted = true))
        assertEquals(listOf(a, b), memory.items())
        assertEquals(listOf(a), memory.items("LIKES"))
        assertEquals(true, memory.item("i-3")?.deleted)
        val e1 = MemoryEvent("e-1", 1, MemoryOp.ADD, "LIKES", "i-1", null, "喜歡無糖豆漿", "m1")
        val e2 = MemoryEvent("e-2", 4, MemoryOp.DELETE, "LIKES", "i-3", "不吃香菜", null, "m2")
        memory.putEvent(e1); memory.putEvent(e2); memory.putEvent(e2.copy(undone = true))
        assertEquals(listOf(e2.copy(undone = true), e1), memory.events())
        assertEquals(e1, memory.event("e-1"))
    }

    @Test fun nudgesAreAnsweredOnce() = test {
        val n = Nudge("n-1", 1_000, listOf("WATER", "LUNCH"), "slot", 7, "記得喝水。", WordedBy.TEMPLATE)
        assertTrue(nudges.insert(n))
        assertFalse(nudges.insert(n.copy(text = "x")))
        assertTrue(nudges.markAnswered("n-1", 1_500))
        assertFalse(nudges.markAnswered("n-9", 1_500))
        assertEquals(listOf(n.copy(answeredAt = 1_500)), nudges.between(1_000, 1_001))
        assertTrue(nudges.between(0, 1_000).isEmpty())
    }

    @Test fun theJournalKeepsMigrationsAndAudits() = test {
        val m = MigrationLog("mig-1", 5, MigrationSource.V083_JSON, """{"meals":3}""", ok = false, note = "數量不符")
        journal.logMigration(m)
        journal.logMigration(MigrationLog("mig-2", 6, MigrationSource.AGENCY_V2_JSON, "{}", ok = true))
        journal.audit(AuditLog("a-1", 7, "FOOD_FIX", "629→714"))
        assertEquals(listOf(m, MigrationLog("mig-2", 6, MigrationSource.AGENCY_V2_JSON, "{}", ok = true)), journal.migrations())
        assertEquals(listOf(AuditLog("a-1", 7, "FOOD_FIX", "629→714")), journal.audits())
    }

    @Test fun aTransactionThatThrowsLeavesNothing() = test {
        meals.insert(meal("kept", "2026-11-23", 1, item("蛋", 70)))
        try {
            transaction {
                meals.insert(meal("gone", "2026-11-23", 2, item("飯", 280)))
                water.insert(WaterLog("w-gone", "2026-11-23", 3, 1))
                transaction { messages.insert(Message("msg-gone", 4, Who.OWNER, text = "嗨")) }
                error("invented failure")
            }
            fail("the transaction should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("invented failure", e.message)
        }
        assertEquals(listOf("kept"), meals.onDay("2026-11-23").map { it.id })
        assertEquals(1, meals.countItems())
        assertEquals(0, water.count())
        assertEquals(0, messages.count())
    }

    @Test fun aTransactionThatFinishesKeepsEverything() = test {
        val n = transaction {
            meals.insert(meal("m", "2026-11-23", 1, item("蛋", 70)))
            transaction { water.insert(WaterLog("w", "2026-11-23", 2, 1)) }
            meals.count()
        }
        assertEquals(1, n)
        assertEquals(1, water.count())
    }
}
