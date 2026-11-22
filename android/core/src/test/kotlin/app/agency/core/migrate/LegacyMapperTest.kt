package app.agency.core.migrate

import app.agency.core.model.Baseline
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.MealItem
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.MigrationSource
import app.agency.core.model.RoleKey
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.rules.FoodTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** LegacyMapper, kind by kind (invented records). */
class LegacyMapperTest {
    private val now = 1_795_300_000_000L

    private fun plan(
        state: LegacyState? = LegacyState(seeded = true),
        messages: List<LegacyMessage> = emptyList(),
        prefs: Map<String, Any?> = emptyMap(),
        files: List<LegacyFile> = emptyList(),
        origin: StateOrigin = StateOrigin.STATE,
        setAside: List<Pair<String, LegacyState?>> = emptyList(),
    ) = LegacyMapper.map(LegacyInput(state, origin, messages, 0, files, LegacyPrefs(prefs), setAside), now)

    private fun fixtureState(name: String) = LegacyCoachJson.parseState(javaClass.getResource("/legacy/$name")!!.readText())!!

    private fun item(name: String, kcal: Int, zero: Boolean = false) =
        MealItem(name, 1.0, kcal, null, null, null, fried = false, source = KcalSource.ESTIMATE, estimateHigh = kcal, zeroCal = zero)

    private fun meal(id: String, breaches: List<String>, offPlan: Boolean = breaches.isNotEmpty(), cheat: Boolean = false) =
        LegacyMeal(id, 10, "2026-11-20", "晚餐", listOf(item("便當", 750)), cheat, offPlan, breaches = breaches)

    private fun food(name: String, kcal: Int, protein: Double?, seeded: Boolean) =
        FoodEntry(name, emptyList(), kcal, protein, null, null, fried = false, estimated = false, seeded = seeded, addedAt = 5)

    @Test fun mealsKeepTheBreachesTheyWereRecordedWith() {
        val p = plan(LegacyState(seeded = true, meals = listOf(
            meal("a", listOf("FRIED")),
            meal("b", listOf("OVER700")),
            meal("c", listOf("FRIED", "OVER700", "ZEROCAL")),
            meal("d", emptyList(), cheat = true),
        )))
        assertEquals(
            listOf(listOf("FRIED"), listOf("MEAL_CAP_700_LEGACY"), listOf("FRIED", "MEAL_CAP_700_LEGACY", "ZEROCAL"), emptyList()),
            p.meals.map { it.breaches },
        )
        assertTrue(p.meals.all { it.legacy })
        assertTrue(p.meals[3].cheat)
        // The 750 kcal meal is under the new 800 cap, but its old label stays: history is not recomputed.
        assertEquals(750, p.meals[1].kcal)
        assertNull(p.meals[0].slotKey)
        assertNull(p.meals[0].photoMediaId)
    }

    @Test fun anOffPlanMealFromV083WithoutBreachesIsFried() {
        val s = LegacyCoachJson.parseState(
            """{"version":1,"seeded":true,"meals":[{"id":"m","at":1,"day":"2026-11-15","text":"炸雞","cheat":false,"offPlan":true,"verified":false,"items":[]}]}""",
        )
        assertEquals(listOf("FRIED"), plan(s).meals.single().breaches)
    }

    @Test fun mealItemsAndTheirNumbersComeAcrossUnchanged() {
        val items = listOf(
            MealItem("排骨便當", 1.5, 1403, 48.0, 20.5, 110.0, fried = true, source = KcalSource.ESTIMATE, estimateHigh = 850),
            MealItem("零卡可樂", 2.0, 0, 0.0, null, null, fried = false, source = KcalSource.LABEL, zeroCal = true),
            MealItem("雞胸", 1.0, 117, 24.0, null, null, fried = false, source = KcalSource.TABLE, tableEstimated = true),
        )
        val m = LegacyMeal("m", 77, "2026-11-21", "午餐", items, cheat = false, offPlan = true, verified = false, breaches = listOf("FRIED"))
        val out = plan(LegacyState(seeded = true, meals = listOf(m))).meals.single()
        assertEquals(items, out.items)
        assertEquals("m", out.id)
        assertEquals(77L, out.at)
        assertEquals("2026-11-21", out.day)
        assertEquals("午餐", out.text)
    }

    @Test fun theOldSeedIsCorrectedTo714And48WithAnAuditRow() {
        val p = plan(LegacyState(seeded = true, food = listOf(food("給力盒子 南加州烤雞腿", 629, 51.0, seeded = true))))
        val f = p.food.first { it.name == "給力盒子 南加州烤雞腿" }
        assertEquals(714, f.kcal)
        assertEquals(48.0, f.proteinG!!, 0.0)
        assertEquals(now, f.updatedAt)
        assertEquals(5L, f.addedAt)
        val audit = p.audits.single()
        assertEquals(LegacyMapper.AUDIT_SEED_CORRECTED, audit.what)
        assertEquals(now, audit.at)
        assertTrue(audit.detail, audit.detail.contains("629") && audit.detail.contains("714"))
        assertEquals(listOf("給力盒子 南加州烤雞腿"), p.note.correctedFood)
    }

    @Test fun hisOwnNumbersForThatFoodAreKept() {
        // He gave the label numbers (the coach then stores his entry, not the seed).
        val own = plan(LegacyState(seeded = true, food = listOf(food("給力盒子 南加州烤雞腿", 650, 50.0, seeded = false))))
        assertEquals(650, own.food.first { it.name == "給力盒子 南加州烤雞腿" }.kcal)
        assertTrue(own.audits.isEmpty())
        val sameNumbersButHis = plan(LegacyState(seeded = true, food = listOf(food("給力盒子 南加州烤雞腿", 629, 51.0, seeded = false))))
        assertEquals(629, sameNumbersButHis.food.first().kcal)
        assertTrue(sameNumbersButHis.audits.isEmpty())
    }

    @Test fun everyFoodEntryIsKeptAndTheTwoNewSeedsAreAdded() {
        val beef = food("牛肉麵", 720, 32.0, seeded = false)
        val p = plan(LegacyState(seeded = true, food = listOf(beef)))
        assertEquals(listOf("牛肉麵", "即食雞胸", "統一高纖豆漿"), p.food.map { it.name })
        assertEquals(beef, p.food[0])
        val chicken = p.food[1]
        assertEquals(117, chicken.kcal)
        assertTrue(chicken.estimated)
        val soy = p.food[2]
        assertEquals(193, soy.kcal)
        assertEquals(15.0, soy.proteinG!!, 0.0)
        assertTrue(soy.seeded)
        assertEquals(listOf("即食雞胸", "統一高纖豆漿"), p.note.addedSeeds)
    }

    @Test fun aSeedHeAlreadyHasIsNotAddedAgain() {
        val p = plan(LegacyState(seeded = true, food = listOf(food("高纖豆漿", 190, 14.0, seeded = false))))
        assertEquals(listOf("高纖豆漿", "即食雞胸"), p.food.map { it.name })
        assertEquals(190, p.food[0].kcal)
    }

    @Test fun anUnseededStateGetsTheTableTheCoachWouldHaveShown() {
        val p = plan(LegacyState(seeded = false))
        assertEquals(FoodTable.seed(now).map { it.name } + listOf("即食雞胸", "統一高纖豆漿"), p.food.map { it.name })
        assertEquals(714, p.food[0].kcal)
        assertEquals(1, p.audits.size)
    }

    @Test fun aRepeatedFoodNameKeepsTheNewestEntry() {
        val p = plan(LegacyState(seeded = true, food = listOf(food("牛肉麵", 700, 30.0, false), food("牛肉麵", 720, 32.0, false))))
        assertEquals(720, p.food.first { it.name == "牛肉麵" }.kcal)
        assertEquals(1, p.food.count { it.name == "牛肉麵" })
        assertEquals(mapOf("FOOD" to 1), p.note.duplicates)
    }

    @Test fun theOldDefaultGoalsBecome75Then68() {
        assertEquals(listOf(75.0, 68.0), plan(prefs = mapOf("goals" to "80.0,75.0,68.0")).rules.goalsKg)
        assertEquals(listOf(75.0, 68.0), plan(prefs = emptyMap()).rules.goalsKg)
        assertEquals(listOf(75.0, 68.0), plan(prefs = mapOf("goals" to "68, 80 ,75")).rules.goalsKg)
        assertNull(plan(prefs = mapOf("goals" to "80.0,75.0,68.0")).note.goalsKept)
    }

    @Test fun hisOwnGoalsAreKeptWithoutAnythingUnder52() {
        val p = plan(prefs = mapOf("goals" to "78.0,72.0,50.0"))
        assertEquals(listOf(78.0, 72.0), p.rules.goalsKg)
        assertEquals(listOf(78.0, 72.0), p.note.goalsKept)
        assertEquals(listOf(50.0), p.note.goalsDropped)
        assertEquals(listOf(77.0, 52.0), plan(prefs = mapOf("goals" to "77,52")).rules.goalsKg)
        val allLow = plan(prefs = mapOf("goals" to "50,45"))
        assertEquals(listOf(75.0, 68.0), allLow.rules.goalsKg)
        assertEquals(listOf(50.0, 45.0), allLow.note.goalsDropped)
    }

    @Test fun rulesTakeFatCarbsAndMarginFromHisSettingsAndTheRestFromPlanV3() {
        val r = plan(prefs = mapOf("fat" to 55, "carbs" to 160, "margin" to 15, "quota" to 2000, "protein" to 120, "cheat_day" to 1)).rules
        assertEquals(55, r.fatG)
        assertEquals(160, r.carbsG)
        assertEquals(15, r.marginPct)
        assertEquals(1700, r.quotaKcal)
        assertEquals(140, r.proteinG)
        assertEquals(800, r.mealCapKcal)
        assertEquals(1200, r.floorKcal)
        assertEquals(1, r.cheatMealsPerWeek)
        assertEquals(3, r.zeroCalPerWeek)
        val note = plan(prefs = mapOf("quota" to 2000, "protein" to 120, "cheat_day" to 1)).note
        assertEquals(2000, note.ignoredQuotaKcal)
        assertEquals(120, note.ignoredProteinG)
        assertEquals(1, note.ignoredCheatDay)
        // Out of the coach's ranges: the default, as the coach read it.
        val bad = plan(prefs = mapOf("fat" to 5, "carbs" to "160", "margin" to 90)).rules
        assertEquals(50, bad.fatG)
        assertEquals(170, bad.carbsG)
        assertEquals(10, bad.marginPct)
        val saved = plan(prefs = mapOf("quota" to 1700, "protein" to 140, "cheat_day" to 5)).note
        assertNull(saved.ignoredQuotaKcal)
        assertNull(saved.ignoredProteinG)
        assertNull(saved.ignoredCheatDay)
    }

    @Test fun theProfileComesFromHisSettings() {
        val p = plan(prefs = mapOf("height" to "172.0", "start_weight" to "81.5", "company" to " 星光娛樂 ", "join_date" to "2026-11-19", "day_start" to 4)).profile
        assertEquals(172.0, p.heightCm!!, 0.0)
        assertEquals(81.5, p.startWeightKg!!, 0.0)
        assertEquals("星光娛樂", p.companyName)
        assertEquals("2026-11-19", p.joinDate)
        assertEquals(4, p.dayStartHour)
        assertEquals("C", p.level)
        assertEquals("001", p.traineeNo)
        assertFalse(p.onboardingDone)
        val d = plan().profile
        assertNull(d.heightCm)
        assertNull(d.startWeightKg)
        assertEquals("小雨娛樂", d.companyName)
        assertEquals("2026-11-21", d.joinDate)
        assertEquals(5, d.dayStartHour)
        assertNull(plan(prefs = mapOf("join_date" to "昨天", "height" to "9")).profile.heightCm)
        assertEquals("2026-11-21", plan(prefs = mapOf("join_date" to "昨天")).profile.joinDate)
    }

    @Test fun aCustomisedSlotTimeIsListedInTheNote() {
        val n = plan(prefs = mapOf("t_lunch" to 750, "t_dinner" to 1050, "t_chicken" to 1260, "t_night" to 1470)).note
        assertEquals(mapOf("t_lunch" to "12:30", "t_night" to "24:30"), n.customisedSlots)
        assertTrue(plan().note.customisedSlots.isEmpty())
        val old = plan(prefs = mapOf("bedtime" to "00:30", "planned_meal" to "給力盒子 南加州烤雞腿", "evidence_min" to 45)).note.oldSettings
        assertEquals(mapOf("bedtime" to "00:30", "evidence_min" to "45"), old)
    }

    @Test fun providerAndModelSettingsGoToAgencyPrefs() {
        val p = plan(prefs = mapOf(
            "provider" to "CLAUDE", "model_deepseek" to "deepseek-flash", "model_claude" to "claude-haiku-5-5",
            "compat_url" to "https://llm.example.invalid/v1", "compat_model" to "local-small", "compat_day" to "2026-11-21",
            "compat_count" to 12, "open_on_coach" to true, "height" to "170.0",
        )).prefs
        assertEquals(
            mapOf(
                "provider" to PrefValue.Str("CLAUDE"), "model_deepseek" to PrefValue.Str("deepseek-flash"),
                "model_claude" to PrefValue.Str("claude-haiku-5-5"), "compat_url" to PrefValue.Str("https://llm.example.invalid/v1"),
                "compat_model" to PrefValue.Str("local-small"), "compat_day" to PrefValue.Str("2026-11-21"), "compat_count" to PrefValue.Num(12),
            ),
            p,
        )
    }

    @Test fun messagesGetTheirRoleFromTheRoleOrTheKind() {
        val p = plan(messages = listOf(
            LegacyMessage("1", 1, LegacySpeaker.OWNER, "吃了雞腿", null),
            LegacyMessage("2", 2, LegacySpeaker.COACH, "記下", "meal"),
            LegacyMessage("3", 3, LegacySpeaker.COACH, "這是藉口", "chat"),
            LegacyMessage("4", 4, LegacySpeaker.COACH, "身高多少", "baseline", LegacyRole.AGENT),
            LegacyMessage("5", 5, LegacySpeaker.COACH, "去睡", "chat", LegacyRole.BODY),
            LegacyMessage("6", 6, LegacySpeaker.COACH, "還沒交", null),
        ))
        assertEquals(listOf(null, RoleKey.BODY, RoleKey.MANAGER, RoleKey.MANAGER, RoleKey.BODY, RoleKey.MANAGER), p.messages.map { it.role })
        assertEquals(listOf(Who.OWNER, Who.COMPANY, Who.COMPANY, Who.COMPANY, Who.COMPANY, Who.COMPANY), p.messages.map { it.who })
        assertTrue(p.messages.all { it.legacy && it.wordedBy == null })
        assertEquals(listOf(null, "meal", "chat", "baseline", "chat", null), p.messages.map { it.kind })
        assertEquals("吃了雞腿", p.messages[0].text)
    }

    @Test fun everyMessageComesAcrossNotOnlyTheLast300() {
        val many = (1..1234).map { LegacyMessage("m$it", it.toLong(), if (it % 2 == 0) LegacySpeaker.COACH else LegacySpeaker.OWNER, "第 $it 句", null) }
        val p = plan(messages = many)
        assertEquals(1234, p.messages.size)
        assertEquals("m1", p.messages.first().id)
        assertEquals(1234, p.expected[ImportKind.MESSAGES])
    }

    @Test fun aRepeatedMessageIdKeepsTheFirstAndIsNoted() {
        val p = plan(messages = listOf(LegacyMessage("x", 1, LegacySpeaker.OWNER, "一", null), LegacyMessage("x", 2, LegacySpeaker.OWNER, "二", null)))
        assertEquals(listOf("一"), p.messages.map { it.text })
        assertEquals(mapOf("MESSAGES" to 1), p.note.duplicates)
    }

    @Test fun weightsPendingAndTrainingsComeAcross() {
        val p = plan(LegacyState(
            seeded = true,
            weights = listOf(LegacyWeight("w", 3, "2026-11-20", 79.6, 90.5)),
            pending = listOf(LegacyPending("m", 1, "牛肉麵", 935, 9)),
            trainings = listOf(LegacyTraining("t1", 4, "2026-11-21", 3), LegacyTraining("t2", 5, "2026-11-22", null)),
        ))
        assertEquals(79.6, p.weights.single().kg, 0.0)
        assertEquals(90.5, p.weights.single().waistCm!!, 0.0)
        assertEquals("2026-11-20", p.weights.single().day)
        val pend = p.pending.single()
        assertEquals(listOf("m", "1", "牛肉麵", "935", "9"), listOf(pend.mealId, pend.itemIdx.toString(), pend.name, pend.countedKcal.toString(), pend.askedAt.toString()))
        assertEquals(listOf(TrainingKind.STRENGTH, TrainingKind.STRENGTH), p.trainings.map { it.kind })
        assertEquals(listOf(3, null), p.trainings.map { it.rounds })
        assertTrue(p.trainings.all { it.legacy })
    }

    @Test fun theBaselineKeepsItsNumbersStepsAndFiles() {
        val b = LegacyBaseline(
            heightCm = 172.0, weightKg = 79.2, photo = "photo-1795224600000.jpg", recording = "recording-1795225200000.m4a",
            pushups = 18, plankSec = 45, skipped = setOf(LegacyBaselineStep.RECORDING, LegacyBaselineStep.BODY), step = LegacyBaselineStep.PLANK,
        )
        val files = listOf(
            LegacyFile("recording-1795225200000.m4a", 99), LegacyFile("photo-1795224600000.jpg", 98), LegacyFile("photo-1795224000000.jpg", 97),
        )
        val p = plan(LegacyState(seeded = true, baseline = b), files = files)
        val out = p.baseline!!
        assertEquals(Baseline.JOIN, out.id)
        assertEquals(172.0, out.heightCm!!, 0.0)
        assertEquals(79.2, out.weightKg!!, 0.0)
        assertEquals(18, out.pushupsMax)
        assertEquals(45, out.plankMaxSec)
        assertEquals(listOf("BODY", "RECORDING"), out.skipped)
        assertEquals("PLANK", out.step)
        assertEquals(LegacyMapper.mediaId("photo-1795224600000.jpg"), out.photoFront)
        assertNull(out.photoSide)
        assertNull(out.photoBack)
        assertEquals(LegacyMapper.mediaId("recording-1795225200000.m4a"), out.recording)
        // Every file in the folder, the older hand-in too.
        assertEquals(listOf("photo-1795224000000.jpg", "photo-1795224600000.jpg", "recording-1795225200000.m4a"), p.media.map { it.file })
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.PHOTO, MediaKind.AUDIO), p.media.map { it.kind })
        assertEquals(listOf("image/jpeg", "image/jpeg", "audio/mp4"), p.media.map { it.mime })
        assertTrue(p.media.all { it.purpose == MediaPurpose.BASELINE })
        assertEquals(listOf(1_795_224_000_000L, 1_795_224_600_000L, 1_795_225_200_000L), p.media.map { it.createdAt })
        assertTrue(p.media.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it.id) })
        assertEquals(3, p.media.map { it.id }.toSet().size)
    }

    @Test fun aBaselineFileThatIsGoneLeavesTheStepOpenAndIsNoted() {
        val p = plan(LegacyState(seeded = true, baseline = LegacyBaseline(photo = "photo-1.jpg", recording = "recording-2.m4a")), files = listOf(LegacyFile("recording-2.m4a", 1)))
        assertNull(p.baseline!!.photoFront)
        assertEquals(listOf("PHOTO"), p.note.missingMedia)
        assertEquals(1, p.media.size)
    }

    @Test fun theSourceSaysWhichBuildWroteTheFiles() {
        assertEquals(MigrationSource.V083_JSON, plan(LegacyState(version = 1, seeded = true)).source)
        assertEquals(MigrationSource.AGENCY_V2_JSON, plan(LegacyState(version = 1, seeded = true, agencyBuild = true)).source)
        assertEquals(MigrationSource.AGENCY_V2_JSON, plan(prefs = mapOf("company" to "小雨娛樂")).source)
        assertEquals(MigrationSource.AGENCY_V2_JSON, plan(messages = listOf(LegacyMessage("1", 1, LegacySpeaker.COACH, "好", null, LegacyRole.BODY))).source)
    }

    @Test fun theExpectedCountsAreTheRecordsOfThePlan() {
        val s = LegacyCoachJson.parseState(javaClass.getResource("/legacy/v083-state.json")!!.readText())
        val (msgs, _) = LegacyCoachJson.parseMessages(javaClass.getResource("/legacy/v083-messages.jsonl")!!.readText())
        val p = plan(s, msgs)
        assertEquals(
            mapOf(
                ImportKind.PROFILE to 1, ImportKind.RULES to 1, ImportKind.MEALS to 4, ImportKind.MEAL_ITEMS to 5, ImportKind.PENDING to 1,
                ImportKind.WEIGHTS to 2, ImportKind.TRAININGS to 0, ImportKind.FOOD to 8, ImportKind.AUDIT to 1, ImportKind.BASELINES to 0,
                ImportKind.MEDIA to 0, ImportKind.MESSAGES to 11,
            ),
            p.expected,
        )
        assertEquals(p, plan(s, msgs))
    }

    @Test fun theNoteRoundTripsAsJsonWithoutContent() {
        val n = plan(prefs = mapOf("goals" to "78,50", "t_lunch" to 750)).note
        assertEquals(n, ImportNote.fromJson(n.toJson()))
        assertEquals("""{"MEALS":2,"FOOD":3}""", ImportPlan.countsJson(linkedMapOf(ImportKind.MEALS to 2, ImportKind.FOOD to 3)))
    }

    @Test fun theRecordsOfASetAsideFileAreMergedIn() {
        // The coach set his file aside after a read error and seeded a fresh state.
        val fresh = LegacyState(version = 1, seeded = true, food = FoodTable.seed(9), meals = listOf(meal("new", emptyList())))
        val old = fixtureState("v083-state.json")
        val again = old.copy(meals = old.meals.take(1) + meal("other", emptyList()))
        val p = plan(fresh, origin = StateOrigin.STATE, setAside = listOf(
            "state.json.bad-1795300000001-copy" to again, "state.json.bad-1795300000000" to old, "state.json.bad-1795300000002" to null,
        ))
        // Newest file first: the copy of 1795300000001 gives its two meals, the older file the rest of its own.
        assertEquals(listOf("new", old.meals[0].id, "other") + old.meals.drop(1).map { it.id }, p.meals.map { it.id })
        assertEquals(old.weights.map { it.id }, p.weights.map { it.id })
        assertEquals(old.pending.map { it.mealId }, p.pending.map { it.mealId })
        // His own foods come in; the seeds of the old file are already in the fresh table.
        assertEquals(FoodTable.seed(9).map { it.name } + listOf("牛肉麵", "無糖綠茶 一瓶", "即食雞胸", "統一高纖豆漿"), p.food.map { it.name })
        // Newest first: the copy gives 2 meals, 2 weights, 1 pending, 2 foods; the older file only its 3 other meals.
        assertEquals(
            mapOf("state.json.bad-1795300000000" to 3, "state.json.bad-1795300000001-copy" to 7, "state.json.bad-1795300000002" to -1),
            p.note.setAsideFiles,
        )
        assertEquals(listOf("state.json.bad-1795300000000", "state.json.bad-1795300000001-copy", "state.json.bad-1795300000002"), p.note.setAsideFiles.keys.toList())
        assertTrue(p.note.duplicates.isEmpty())
        assertTrue(plan().note.setAsideFiles.isEmpty())
    }

    @Test fun aSetAsideFileKeepsHisOwnEntryOverAnUntouchedSeedAndGivesItsBaseline() {
        val fresh = LegacyState(seeded = true, food = listOf(food("給力盒子 南加州烤雞腿", 629, 51.0, seeded = true), food("牛肉麵", 700, 30.0, seeded = false)))
        val old = LegacyState(
            seeded = true,
            food = listOf(food("給力盒子  南加州烤雞腿", 650, 50.0, seeded = false), food("牛肉麵", 650, 28.0, seeded = false)),
            baseline = LegacyBaseline(heightCm = 172.0, pushups = 12),
        )
        val p = plan(fresh, setAside = listOf("state.json.bad-1" to old))
        assertEquals(650, p.food.single { FoodTable.normalize(it.name) == FoodTable.normalize(LegacyMapper.GELI_CHICKEN) }.kcal)
        assertTrue(p.audits.isEmpty())
        // His newer entry in the main table wins over the set-aside one.
        assertEquals(700, p.food.single { it.name == "牛肉麵" }.kcal)
        assertEquals(12, p.baseline!!.pushupsMax)
        assertEquals(mapOf("state.json.bad-1" to 2), p.note.setAsideFiles)
    }

    @Test fun ofTwoSetAsideFilesTheNewerLabelValueWins() {
        // Set aside at t1 with his entry, then he relabelled it in the fresh state, which was set aside again at t2.
        val fresh = LegacyState(seeded = true, food = FoodTable.seed(9))
        val t1 = LegacyState(seeded = true, food = listOf(food("巷口滷肉飯", 520, 14.0, seeded = false)))
        val t2 = LegacyState(seeded = true, food = FoodTable.seed(9) + food("巷口滷肉飯", 610, 16.0, seeded = false))
        val p = plan(fresh, setAside = listOf("state.json.bad-1795300000000" to t1, "state.json.bad-1795300009000" to t2))
        assertEquals(610, p.food.single { it.name == "巷口滷肉飯" }.kcal)
        assertEquals(mapOf("state.json.bad-1795300000000" to 0, "state.json.bad-1795300009000" to 1), p.note.setAsideFiles)
    }

    @Test fun aSetAsideFileReadOnlyUpToItsCutIsNamedInTheNote() {
        val input = LegacyInput(
            LegacyState(seeded = true), StateOrigin.STATE, emptyList(),
            setAside = listOf("state.json.bad-2" to LegacyState(seeded = true), "state.json.bad-1" to LegacyState(seeded = true), "state.json.bad-3" to null),
            setAsideSalvaged = setOf("state.json.bad-2", "state.json.bad-1"),
        )
        assertEquals(listOf("state.json.bad-1", "state.json.bad-2"), LegacyMapper.map(input, now).note.setAsideSalvaged)
        assertTrue(plan().note.setAsideSalvaged.isEmpty())
    }

    @Test fun aLaterRunLeavesOutTheRecordsAnEarlierPartialImportBrought() {
        val full = LegacyMapper.map(LegacyInput(fixtureState("v083-state.json"), StateOrigin.STATE, emptyList()), now)
        val earlier = full.copy(meals = full.meals.take(2), pending = full.pending, food = full.food.take(3), messages = emptyList(), audits = emptyList())
        val keys = earlier.recordKeys()
        assertEquals(2 + full.pending.size + 3 + full.weights.size, keys.size)
        assertEquals(2, full.weights.size)
        // Kind and a hash only: no id or name in the clear.
        assertTrue(keys.all { Regex("^[A-Z_]+:[0-9a-f]{16}$").matches(it) })
        assertTrue(keys.none { k -> full.meals.any { k.contains(it.id) } })
        val later = full.except(keys)
        assertEquals(full.meals.drop(2), later.meals)
        assertTrue(later.pending.isEmpty())
        assertEquals(full.food.drop(3), later.food)
        assertTrue(later.weights.isEmpty())
        assertEquals(full.audits, later.audits)
        assertEquals(keys.size, later.note.carriedBefore)
        assertEquals(full.expected[ImportKind.MEALS]!! - 2, later.expected[ImportKind.MEALS])
        assertEquals(full.profile, later.profile)
        assertTrue(full.except(emptySet()) === full)
        assertEquals(0, full.note.carriedBefore)
    }

    @Test fun anUnreadableOrSalvagedStateGetsNoSeeds() {
        for (origin in listOf(StateOrigin.UNREADABLE, StateOrigin.SALVAGED)) {
            val p = plan(if (origin == StateOrigin.UNREADABLE) null else LegacyState(seeded = false, food = listOf(food("牛肉麵", 720, 32.0, false))), origin = origin)
            assertEquals(origin.name, if (origin == StateOrigin.UNREADABLE) emptyList() else listOf("牛肉麵"), p.food.map { it.name })
            assertTrue(p.note.addedSeeds.isEmpty())
            assertFalse(p.stateRead)
        }
        // With no state file at all the coach would have seeded its table: so does the import.
        val none = plan(null, origin = StateOrigin.NONE)
        assertEquals(FoodTable.seed(now).map { it.name } + listOf("即食雞胸", "統一高纖豆漿"), none.food.map { it.name })
        assertTrue(none.stateRead)
    }

    @Test fun aSalvagedStateIsCountedInTheNote() {
        val s = LegacyCoachJson.salvageState(javaClass.getResource("/legacy/damaged-state.json")!!.readText())!!
        val p = LegacyMapper.map(LegacyInput(s, StateOrigin.SALVAGED, emptyList(), salvagedFrom = "state.json"), now)
        assertEquals("SALVAGED", p.note.stateOrigin)
        assertEquals("state.json", p.note.salvagedFrom)
        assertEquals(6, p.note.salvagedRecords)
        assertEquals(3, p.meals.size)
        assertEquals(0, plan().note.salvagedRecords)
        assertNull(plan().note.salvagedFrom)
    }

    @Test fun aBaselineFileOverTheLimitIsLeftOutAndNoted() {
        val big = LegacyMapper.MAX_IMPORT_MEDIA_BYTES + 1
        val files = listOf(LegacyFile("photo-1795224600000.jpg", 1, 2_000), LegacyFile("recording-1795225200000.wav", 2, big))
        val p = plan(LegacyState(seeded = true, baseline = LegacyBaseline(photo = "photo-1795224600000.jpg", recording = "recording-1795225200000.wav")), files = files)
        assertEquals(listOf("photo-1795224600000.jpg"), p.media.map { it.file })
        assertEquals(LegacyMapper.mediaId("photo-1795224600000.jpg"), p.baseline!!.photoFront)
        assertNull(p.baseline!!.recording)
        assertEquals(listOf("recording-1795225200000.wav"), p.note.mediaTooLarge)
        assertTrue(p.note.missingMedia.isEmpty())
        // Exactly at the limit is still imported.
        assertEquals(1, plan(files = listOf(LegacyFile("recording-1.wav", 1, LegacyMapper.MAX_IMPORT_MEDIA_BYTES))).media.size)
    }

    @Test fun onASmallHeapTheCapIsASixthOfIt() {
        // Review FIX-WAVE2 N3: 128 MiB heap → about 21 MiB; 192 MiB and up keep the full cap.
        val mib = 1024L * 1024
        assertEquals(128 * mib / 6, LegacyMapper.mediaCap(128 * mib))
        assertEquals(LegacyMapper.MAX_IMPORT_MEDIA_BYTES, LegacyMapper.mediaCap(192 * mib))
        assertEquals(LegacyMapper.MAX_IMPORT_MEDIA_BYTES, LegacyMapper.mediaCap(512 * mib))
        val files = listOf(LegacyFile("photo-1.jpg", 1, 2_000), LegacyFile("recording-1.wav", 2, 2_001))
        val p = LegacyMapper.map(LegacyInput(LegacyState(seeded = true), StateOrigin.STATE, emptyList(), 0, files, maxMediaBytes = 2_000), now)
        assertEquals(listOf("photo-1.jpg"), p.media.map { it.file })
        assertEquals(listOf("recording-1.wav"), p.note.mediaTooLarge)
    }

    @Test fun droppedRecordsOfEveryReadFileAreInTheNote() {
        val p = plan(
            LegacyState(seeded = true, dropped = mapOf("MEALS" to 1, "FOOD" to 2)),
            setAside = listOf("state.json.bad-1" to LegacyState(seeded = true, dropped = mapOf("MEALS" to 2, "WEIGHTS" to 1)), "state.json.bad-2" to null),
        )
        assertEquals(mapOf("MEALS" to 3, "FOOD" to 2, "WEIGHTS" to 1), p.note.droppedRecords)
        assertTrue(plan().note.droppedRecords.isEmpty())
    }

    @Test fun savedValuesAnExistingRowKeptOutAreListed() {
        val p = plan(prefs = mapOf("height" to "172.0", "fat" to 55, "goals" to "78,72", "day_start" to 5))
        assertEquals(
            setOf(LegacyMapper.PROFILE_HEIGHT, LegacyMapper.PROFILE_DAY_START, LegacyMapper.RULES_FAT, LegacyMapper.RULES_GOALS),
            p.fromPrefs,
        )
        val stored = p.profile.copy(heightCm = 170.0, companyName = "星光娛樂")
        val rules = p.rules.copy(fatG = 50, goalsKg = listOf(75.0, 68.0), quotaKcal = 1600)
        assertEquals(
            mapOf("profile.heightCm" to "172.0", "rules.fatG" to "55", "rules.goalsKg" to "[78.0, 72.0]"),
            LegacyMapper.notApplied(p, stored, rules),
        )
        // A row the import wrote itself, or one that holds his values, lists nothing.
        assertTrue(LegacyMapper.notApplied(p, null, null).isEmpty())
        assertTrue(LegacyMapper.notApplied(p, p.profile, p.rules.copy(version = 3)).isEmpty())
    }

    @Test fun slotTimesAreWrittenWithLatinDigitsWhateverThePhoneLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"))
            assertEquals(mapOf("t_lunch" to "12:30"), plan(prefs = mapOf("t_lunch" to 750)).note.customisedSlots)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun aFinishedBaselineIsMarkedComplete() {
        val files = listOf(LegacyFile("photo-1.jpg", 1, 10), LegacyFile("recording-2.m4a", 2, 10))
        val done = LegacyBaseline(heightCm = 172.0, weightKg = 79.2, photo = "photo-1.jpg", recording = "recording-2.m4a", pushups = 18, skipped = setOf(LegacyBaselineStep.PLANK))
        assertEquals(now, plan(LegacyState(seeded = true, baseline = done), files = files).baseline!!.completedAt)
        // A question still open, a step neither handed in nor skipped, or a file that is not there: not finished.
        assertNull(plan(LegacyState(seeded = true, baseline = done.copy(step = LegacyBaselineStep.PLANK)), files = files).baseline!!.completedAt)
        assertNull(plan(LegacyState(seeded = true, baseline = done.copy(skipped = emptySet())), files = files).baseline!!.completedAt)
        assertNull(plan(LegacyState(seeded = true, baseline = done.copy(weightKg = null)), files = files).baseline!!.completedAt)
        assertNull(plan(LegacyState(seeded = true, baseline = done), files = files.take(1)).baseline!!.completedAt)
        assertNull(plan(LegacyState(seeded = true, baseline = LegacyBaseline())).baseline!!.completedAt)
    }
}
