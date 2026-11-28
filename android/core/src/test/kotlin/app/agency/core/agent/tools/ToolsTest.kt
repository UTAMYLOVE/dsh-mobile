package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.Memory
import app.agency.core.lessons.Habit
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonState
import app.agency.core.plan.ItemStatus
import app.agency.core.stage.Today
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.Memo
import app.agency.core.store.ProposalRec
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    // ---- the frame ----

    @Test fun everySchemaIsStrict() {
        for (t in Tools.all()) {
            val p = t.spec.parameters
            assertEquals(t.spec.name, "object", p.s("type"))
            assertEquals(t.spec.name, JsonPrimitive(false), p["additionalProperties"])
            val props = (p["properties"] as JsonObject).keys
            val req = (p["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
            assertEquals("${t.spec.name}: every property required", props, req)
            assertTrue(t.spec.description.isNotBlank())
            fun nested(o: JsonObject) {
                (o["items"] as? JsonObject)?.let { i ->
                    if (i.s("type") == "object") {
                        assertEquals(JsonPrimitive(false), i["additionalProperties"])
                        assertEquals((i["properties"] as JsonObject).keys, (i["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
                    }
                }
            }
            (p["properties"] as JsonObject).values.forEach { nested(it as JsonObject) }
        }
        assertEquals(Tools.all().size, Tools.all().map { it.spec.name }.toSet().size)
    }

    @Test fun theSpecToolsExistAndTheDirectOnesAreDirect() {
        val names = Tools.all().map { it.spec.name }.toSet()
        listOf(
            "check_in", "check_out", "log_meal", "fix_meal", "remove_entry", "log_water", "ride_out", "ride_back", "log_task",
            "open_timer", "set_status", "log_body", "log_mood", "log_drink", "log_money", "memo_add", "memo_list", "memo_done",
            "memo_remove", "remember", "forget", "note_today", "find_videos", "find_places", "lesson_wish", "lessons_today",
            "propose", "get_day", "get_week", "read_rules", "real_task", "open_screen", "attach_photo_to_meal", "eval_status",
            "song_summary", "habit_done",
        ).forEach { assertTrue(it, it in names) }
        listOf("check_in", "check_out", "log_water", "ride_out", "ride_back", "log_task", "log_body", "log_mood")
            .forEach { assertNotNull(it, Tools.direct(it)) }
        assertNull(Tools.direct("log_meal"))
    }

    @Test fun badArgumentsComeBackAsHelpfulErrorsNeverExceptions() {
        val r = ToolRig()
        val bad = mapOf(
            "check_in" to """{"score":9}""", "log_water" to """{"bottles":null,"ml":null}""", "log_meal" to """{"slot":"lunch","items":[]}""",
            "log_task" to """{"task":"JUGGLING"}""", "set_status" to """{"type":"TIRED","note":""}""", "log_body" to """{"kg":null,"waist_cm":null}""",
            "log_mood" to """{"score":0,"note":""}""", "log_money" to """{"amount":"abc","bucket":"living","note":""}""",
            "memo_done" to """{"memo_id":999}""", "get_day" to """{"date":"昨天"}""", "fix_meal" to """{"entry_id":42,"items":[]}""",
            "remove_entry" to """{"entry_id":42}""", "habit_done" to """{"habit_id":7}""", "propose" to """{"title":"多吃","body":"","kind":"kcal_up","value":100}""",
            "memo_add" to """{"text":"x","due_at":"下週某天"}""", "open_screen" to """{"screen":"nowhere","arg":""}""",
        )
        for ((name, json) in bad) {
            val out = r.run(name, json)
            assertFalse("$name should refuse $json", out.ok)
            assertTrue("$name error in his language: ${out.err()}", out.err().any { it in '一'..'鿿' })
        }
    }

    // ---- recording ----

    @Test fun checkInRecordsAndReportsLateness() {
        val r = ToolRig()
        r.at("07:20")
        val out = r.run("check_in", """{"score":4}""")
        assertTrue(out.ok)
        assertEquals("20", out.facts.s("late_minutes"))
        assertEquals("7:00", out.facts.s("wake_time"))
        assertEquals(1, r.store.entries(r.monday, Kind.CHECK_IN).size)
        assertTrue(r.run("check_in", """{"score":3}""").facts.s("already") == "true")
        assertEquals(1, r.store.entries(r.monday, Kind.CHECK_IN).size)
    }

    @Test fun checkInWithoutAScoreAsksWithTheDots() {
        val r = ToolRig()
        val out = r.run("check_in", """{"score":null}""")
        assertEquals(listOf(Card.Score("check_in")), out.cards)
        assertTrue(r.store.entries(r.monday, Kind.CHECK_IN).isEmpty())
    }

    @Test fun logMealResolvesTheTableAttachesThePhotoAndReturnsTodaysNumbers() {
        val r = ToolRig()
        r.at("12:10")
        val out = r.run(
            "log_meal",
            """{"slot":"first","items":[{"name":"雞腿便當","kcal":650,"protein_g":30}],"source":"estimate","attach_photo":true}""",
            photos = listOf("p1"),
        )
        assertTrue(out.ok)
        val card = out.cards.single() as Card.MealCard
        assertEquals(700, card.kcal)
        assertFalse(card.estimated)
        assertFalse(card.needsPhoto)
        assertEquals("1300", out.facts.o("today").s("kcal_left"))
        assertEquals("900", out.facts.o("today").o("next_meal_budget_kcal").s("晚餐"))
        val meal = r.store.entries(r.monday, Kind.MEAL).single().read<Meal>()
        assertEquals("p1", meal.photoId)
        assertEquals(ItemStatus.DONE, Today.plan(r.store, r.clock, r.rules).items.first { it.id == "meal:FIRST" }.status)
    }

    @Test fun anEstimateIsRaisedAndAMissingPhotoIsSaid() {
        val r = ToolRig()
        r.at("12:10")
        val out = r.run("log_meal", """{"slot":"first","items":[{"name":"拉麵","kcal":500,"protein_g":20.7}],"source":"estimate","attach_photo":false}""")
        val card = out.cards.single() as Card.MealCard
        assertEquals(550, card.kcal)
        assertTrue(card.estimated && card.needsPhoto)
        assertNotNull(out.facts.s("needs_photo"))
        assertEquals("缺照片", Today.plan(r.store, r.clock, r.rules).items.first { it.id == "meal:FIRST" }.detail)
    }

    @Test fun aBannedItemAndAnOverCapMealWriteViolationsAndWarnings() {
        val r = ToolRig()
        r.at("12:10")
        val out = r.run("log_meal", """{"slot":"first","items":[{"name":"炸雞","kcal":900,"protein_g":40}],"source":"label","attach_photo":false}""")
        assertTrue(out.ok)
        val card = out.cards.single() as Card.MealCard
        assertTrue(card.warnings.any { it.contains("禁止") })
        assertTrue("BANNED_FOOD" in r.violations())
        assertTrue(out.facts["new_violations"]!!.jsonArray.any { it.jsonPrimitive.content.contains("禁止") })
        r.run("log_meal", """{"slot":"dinner","items":[{"name":"雞腿便當","kcal":0,"protein_g":0},{"name":"豆漿","kcal":0,"protein_g":0},{"name":"雞胸","kcal":0,"protein_g":0}],"source":"estimate","attach_photo":false}""")
        assertTrue("MEAL_OVER_CAP" in r.violations())
    }

    @Test fun aClosedFeatureStillRecordsButNeverCountsOrViolates() {
        val r = ToolRig(stage = 1)
        r.at("12:10")
        val meal = r.run("log_meal", """{"slot":"first","items":[{"name":"炸雞","kcal":1500,"protein_g":40}],"source":"label","attach_photo":false}""")
        assertTrue(meal.ok)
        assertEquals("false", meal.facts.s("open"))
        assertTrue(meal.cards.isEmpty())
        val water = r.run("log_water", """{"bottles":1,"ml":null}""")
        assertEquals("false", water.facts.s("open"))
        val ride = r.run("ride_out", "{}")
        assertEquals("false", ride.facts.s("open"))
        assertTrue(r.violations().isEmpty())
        assertEquals(1, r.store.entries(r.monday, Kind.MEAL).size)
        val plan = Today.plan(r.store, r.clock, r.rules)
        assertTrue(plan.items.none { it.feature.stage > 1 })
        // Scheduling a closed feature is refused outright.
        assertFalse(r.run("open_timer", """{"task":"CORE"}""").ok)
        assertFalse(r.run("open_screen", """{"screen":"camera","arg":"first"}""").ok)
    }

    @Test fun fixAndRemoveOnlyTouchTodaysOwnEntries() {
        val r = ToolRig()
        r.at("12:10")
        val id = r.run("log_meal", """{"slot":"first","items":[{"name":"拉麵","kcal":500,"protein_g":20}],"source":"estimate","attach_photo":false}""")
            .facts.s("entry_id")!!
        val fixed = r.run("fix_meal", """{"entry_id":$id,"items":[{"name":"雞胸","kcal":1,"protein_g":1}],"source":"estimate"}""")
        assertEquals("120", fixed.facts.s("meal_kcal"))
        val checkIn = r.run("check_in", """{"score":4}""").facts.s("entry_id")!!
        assertFalse(r.run("remove_entry", """{"entry_id":$checkIn}""").ok)
        assertTrue(r.run("remove_entry", """{"entry_id":$id}""").ok)
        assertTrue(r.store.entries(r.monday, Kind.MEAL).isEmpty())
    }

    @Test fun aGalleryPhotoForALoggedMealIsMarkedLate() {
        val r = ToolRig()
        r.at("12:10")
        val id = r.run("log_meal", """{"slot":"first","items":[{"name":"雞胸","kcal":1,"protein_g":1}],"source":"estimate","attach_photo":false}""").facts.s("entry_id")!!
        val out = r.run("attach_photo_to_meal", """{"entry_id":$id}""", photos = listOf("g1"), gallery = listOf(true))
        assertTrue(out.ok)
        val meal = r.store.entry(id.toLong())!!.read<Meal>()
        assertTrue(meal.photoLate)
        assertTrue(Today.plan(r.store, r.clock, r.rules).items.first { it.id == "meal:FIRST" }.detail.contains("補交"))
    }

    @Test fun waterInBottlesOrMillilitres() {
        val r = ToolRig()
        val half = r.run("log_water", """{"bottles":0.5,"ml":null}""")
        assertEquals("500", half.facts.s("ml"))
        val more = r.run("log_water", """{"bottles":null,"ml":500}""")
        assertEquals("1000", more.facts.o("today").s("water_ml"))
        assertEquals("true", more.facts.s("reached"))
        assertEquals("✓ 已記：喝水 0.5 壺", (Tools.direct("log_water") as Noted).noteLine(half))
    }

    @Test fun rideOutAndBackWithTheWeeksIncome() {
        val r = ToolRig()
        r.at("19:20")
        assertTrue(r.run("ride_out", "{}").ok)
        assertFalse(r.run("ride_out", "{}").ok)
        r.at("22:30")
        val back = r.run("ride_back", """{"income":1200}""")
        assertEquals("1200", back.facts.o("week_income").s("income"))
        assertEquals("4800", back.facts.o("week_income").s("left"))
        assertTrue(back.facts["training_now"]!!.jsonArray.isNotEmpty())
    }

    @Test fun rideBackWithoutRideOutFillsTheGap() {
        val r = ToolRig()
        r.at("22:30")
        val back = r.run("ride_back", """{"income":null}""")
        assertNotNull(back.facts.s("out_backfilled"))
        assertEquals(ItemStatus.DONE, Today.plan(r.store, r.clock, r.rules).items.first { it.id == "ride:out" }.status)
    }

    @Test fun logTaskMarksAReductionOnlyWhenCodeAllowsIt() {
        val r = ToolRig()
        r.at("22:30")
        val tired = r.run("log_task", """{"task":"STRENGTH","rounds":2,"minutes":null,"feel":"hard","note":""}""")
        assertNotNull(tired.facts.s("short_rounds"))
        assertFalse(r.store.entries(r.monday, Kind.TASK).last().read<TaskDone>().reduced)
        r.run("set_status", """{"type":"SICK","note":"發燒"}""")
        val sick = r.run("log_task", """{"task":"STRENGTH","rounds":2,"minutes":null,"feel":null,"note":""}""")
        assertNotNull(sick.facts.s("reduced_by_code"))
        assertTrue(r.store.entries(r.monday, Kind.TASK).last().read<TaskDone>().reduced)
    }

    @Test fun setStatusSaysWhatCodeChanged() {
        val r = ToolRig()
        r.at("08:00")
        val sick = r.run("set_status", """{"type":"SICK","note":"感冒"}""")
        assertEquals("true", sick.facts.s("training_reduced_by_code"))
        assertTrue(sick.facts["adjustments"]!!.jsonArray.any { it.jsonPrimitive.content.contains("肌力少一輪") })
        assertFalse(r.run("set_status", """{"type":"SICK","note":""}""").ok)
        assertTrue(r.run("set_status", """{"type":"RECOVERED","note":""}""").ok)
        assertFalse(r.run("set_status", """{"type":"RECOVERED","note":""}""").ok)
    }

    @Test fun restDayOncePerCalendarWeek() {
        val r = ToolRig()
        r.at("10:00")
        val rest = r.run("set_status", """{"type":"REST_DAY","note":""}""")
        assertTrue(rest.facts["changed_items"]!!.jsonArray.any { it.jsonPrimitive.content.contains("出門跑單") })
        r.clock.set(r.monday.plusDays(2), "10:00")
        val again = r.run("set_status", """{"type":"REST_DAY","note":""}""")
        assertFalse(again.ok)
        assertTrue(again.err().contains("每週最多一天"))
        r.clock.set(r.monday.plusDays(7), "10:00")
        assertTrue(r.run("set_status", """{"type":"REST_DAY","note":""}""").ok)
    }

    @Test fun examWeekIsLimitedPerTerm() {
        val r = ToolRig()
        assertTrue(r.run("set_status", """{"type":"EXAM_WEEK","note":""}""").ok)
        r.clock.set(r.monday.plusDays(10), "10:00")
        assertTrue(r.run("set_status", """{"type":"EXAM_WEEK","note":""}""").ok)
        r.clock.set(r.monday.plusDays(20), "10:00")
        assertFalse(r.run("set_status", """{"type":"EXAM_WEEK","note":""}""").ok)
    }

    @Test fun drinksBodyMoodAndMoney() {
        val r = ToolRig(stage = 3)
        r.at("21:00")
        r.run("log_drink", """{"type":"caffeine"}""")
        assertTrue("CAFFEINE_LATE" in r.violations())
        val body = r.run("log_body", """{"kg":79.5,"waist_cm":80}""")
        assertEquals("79.5", body.facts.s("kg"))
        assertEquals("3", r.run("log_mood", """{"score":3,"note":"還好"}""").facts.s("score"))
        val money = r.run("log_money", """{"amount":120,"bucket":"living","note":"午餐"}""")
        assertEquals("120", money.facts.o("week").s("living"))
    }

    @Test fun memosAndMemory() {
        val r = ToolRig()
        r.at("10:00")
        val memo = r.run("memo_add", """{"text":"交作業","due_at":"18:30"}""")
        assertEquals("18:30", memo.facts.s("due_time"))
        val id = memo.facts.s("memo_id")!!
        assertEquals("1", r.run("memo_list", "{}").facts.s("count"))
        assertTrue(r.run("memo_done", """{"memo_id":$id}""").ok)
        assertTrue(r.store.entry(id.toLong())!!.read<Memo>().done)
        assertTrue(r.run("memo_remove", """{"memo_id":$id}""").ok)
        r.run("remember", """{"text":"他不吃香菜"}""")
        assertEquals(listOf("他不吃香菜"), Memory.lines(r.store))
        r.run("forget", """{"text":"香菜"}""")
        assertTrue(Memory.lines(r.store).isEmpty())
        assertTrue(r.run("note_today", """{"text":"今天很累"}""").ok)
    }

    // ---- lessons and search ----

    @Test fun findVideosProposesAtMostFourRealResults() {
        val r = ToolRig(stage = 1)
        val out = r.run("find_videos", """{"subject":"語言","query":"自我介紹"}""")
        val card = out.cards.single() as Card.Videos
        assertEquals(4, card.items.size)
        assertTrue(card.items.all { LessonBook(r.store, r.clock, r.rules).get(it.lessonId)!!.state == LessonState.PROPOSED })
        assertEquals(4, out.facts["videos"]!!.jsonArray.size)
        assertFalse(out.facts.toString().contains("https://"))
    }

    @Test fun withoutSearchNoVideoIsInvented() {
        val r = ToolRig(stage = 1, search = null)
        val out = r.run("find_videos", """{"subject":"語言","query":""}""")
        assertFalse(out.ok)
        assertTrue(out.err().contains("搜尋現在不能用"))
        assertTrue(r.store.entries(r.monday, Kind.LESSON).isEmpty())
    }

    @Test fun placesComeFromSearchOnly() {
        val r = ToolRig()
        val out = r.run("find_places", """{"query":"雞胸便當"}""")
        assertEquals("測試餐盒店", (out.cards.single() as Card.Places).items.single().name)
    }

    @Test fun lessonsTodayAndAWish() {
        val r = ToolRig(stage = 1)
        val today = r.run("lessons_today", "{}")
        assertEquals("3", today.facts.s("total"))
        assertTrue(r.run("lesson_wish", """{"topic":"吉他"}""").ok)
    }

    // ---- proposals ----

    @Test fun aHabitProposalIsRefusedWhileThePreviousOneHasNotStuck() {
        val r = ToolRig(stage = 1)
        val ok = r.run("propose", """{"title":"每天念五個單字","body":"","kind":"habit","value":null}""")
        val card = ok.cards.single() as Card.Proposal
        assertEquals("habit", r.store.entry(card.entryId)!!.read<ProposalRec>().kind)
        r.store.add(r.monday, r.clock.now(), Kind.HABIT, Habit("舊習慣", r.monday.toString()))
        val no = r.run("propose", """{"title":"再一個","body":"","kind":"habit","value":null}""")
        assertFalse(no.ok)
        assertTrue(r.run("propose", """{"title":"明天起早十分鐘出門","body":"","kind":"note","value":null}""").ok)
    }

    @Test fun habitDoneMarksTodaysHabit() {
        val r = ToolRig(stage = 1)
        val id = r.store.add(r.monday, r.clock.now(), Kind.HABIT, Habit("念單字", r.monday.toString())).id
        assertTrue(r.run("habit_done", """{"habit_id":$id}""").ok)
        assertEquals(ItemStatus.DONE, Today.plan(r.store, r.clock, r.rules).items.first { it.id == "habit:$id" }.status)
    }

    // ---- reading ----

    @Test fun getDayGetWeekAndReadRules() {
        val r = ToolRig()
        r.at("12:00")
        r.run("log_meal", """{"slot":"first","items":[{"name":"雞胸","kcal":1,"protein_g":1}],"source":"estimate","attach_photo":false}""")
        val day = r.run("get_day", """{"date":"${r.monday}"}""")
        assertEquals("120", day.facts.s("kcal"))
        assertFalse(r.run("get_day", """{"date":"${r.monday.plusDays(3)}"}""").ok)
        assertEquals(r.monday.toString(), r.run("get_week", "{}").facts.s("week_start"))
        assertFalse(r.run("read_rules", """{"keyword":"宵夜"}""").ok)
        r.store.putDoc(app.agency.core.store.Doc.RULES_TEXT, "\"第一行\\n宵夜不要吃炸的\\n第三行\"")
        assertTrue(r.run("read_rules", """{"keyword":"宵夜"}""").facts.s("excerpt")!!.contains("宵夜不要吃炸的"))
    }

    @Test fun stageFourToolsSayClosedBeforeThen() {
        val r = ToolRig(stage = 2)
        assertEquals("false", r.run("real_task", """{"text":"","done":false}""").facts.s("open"))
        assertEquals("false", r.run("eval_status", "{}").facts.s("open"))
        val r4 = ToolRig(stage = 4)
        assertTrue(r4.run("real_task", """{"text":"","done":false}""").facts.s("task")!!.isNotBlank())
        assertTrue(r4.run("eval_status", "{}").ok)
        assertFalse(r4.run("song_summary", """{"entry_id":1}""").ok)
    }

    @Test fun openTimerAndOpenScreenGiveButtons() {
        val r = ToolRig()
        val t = r.run("open_timer", """{"task":"CORE"}""")
        assertEquals(Card.Open("TIMER", "CORE", "開始計時：核心"), t.cards.single())
        val s = r.run("open_screen", """{"screen":"camera","arg":"dinner"}""")
        assertEquals("DINNER", (s.cards.single() as Card.Open).arg)
        r.run("set_status", """{"type":"INJURED","note":"膝蓋痛"}""")
        assertFalse(r.run("open_timer", """{"task":"STRENGTH"}""").ok)
    }

    @Test fun directNoteLinesSayWhatWasRecorded() {
        val r = ToolRig()
        val out = r.run("check_in", """{"score":4}""")
        assertEquals("✓ 已簽到，狀態 4 分", (Tools.direct("check_in") as Noted).noteLine(out))
        val bad = r.run("log_mood", """{"score":9,"note":""}""")
        assertTrue((Tools.direct("log_mood") as Noted).noteLine(bad).startsWith("沒記到"))
    }
}
