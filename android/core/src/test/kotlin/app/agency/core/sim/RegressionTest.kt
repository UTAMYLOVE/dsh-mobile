package app.agency.core.sim

import app.agency.core.agent.Company
import app.agency.core.agent.Providers
import app.agency.core.agent.tools.ToolRig
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.LessonBook
import app.agency.core.llm.ScriptedLlm
import app.agency.core.plan.Screen
import app.agency.core.plan.Tap
import app.agency.core.plan.TaskId
import app.agency.core.remind.ReminderLog
import app.agency.core.rules.Fixtures
import app.agency.core.stage.StageRound
import app.agency.core.stage.StageState
import app.agency.core.stage.Today
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.TaskDone
import app.agency.core.store.writeDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per bug the simulation found in another package, so each stays fixed. */
class RegressionTest {
    /** remind: a deadline item (water before leaving) has no `at`, only `until`; it used to never be noticed or chased. */
    @Test fun aLateDeadlineItemIsNoticedAndChased() {
        val r = ToolRig(stage = 2)
        val company = Company(r.store, r.clock, Providers(ScriptedLlm(), ScriptedLlm()))
        r.at("07:05")
        company.direct("check_in", kotlinx.serialization.json.buildJsonObject { put("score", kotlinx.serialization.json.JsonPrimitive(4)) })
        var log = ReminderLog()
        val sent = ArrayList<String>()
        for (t in listOf("19:31", "20:02", "20:33")) {
            r.at(t)
            val d = company.reminders(log)
            log = d.log
            sent += d.notices.map { it.key }
        }
        assertEquals(3, sent.count { it == "water" })
    }

    /** plan: a slot whose video waits for his yes used to tap 「幫我找…影片」 again, piling up more proposals. */
    @Test fun aSlotWithAProposedVideoOpensTheLessonsPage() {
        val r = ToolRig(stage = 1)
        LessonBook(r.store, r.clock, r.rules).propose(app.agency.core.agent.Hit("課一", "https://video.example.test/a", "", "內容"), "語言")
        val slot = Today.plan(r.store, r.clock, r.rules).items.first { it.id == "lesson:1" }
        assertEquals(Tap.Open(Screen.LESSONS), slot.tap)
        assertEquals("等你決定", slot.detail)
    }

    /** plan: on a sick day in stage 2 the adjustment said 「體能今天免做」 although cardio does not open before stage 3. */
    @Test fun aSickDayNamesOnlyOpenFeatures() {
        val r = ToolRig(stage = 2)
        r.run("set_status", """{"type":"SICK","note":""}""")
        val adj = Today.plan(r.store, r.clock, r.rules).adjustments.map { it.what }
        assertTrue(adj.any { it.contains("肌力少一輪") })
        assertFalse(adj.any { it.contains("體能") })
        val r3 = ToolRig(stage = 3)
        r3.run("set_status", """{"type":"SICK","note":""}""")
        assertTrue(Today.plan(r3.store, r3.clock, r3.rules).adjustments.any { it.what.contains("體能今天免做") })
    }

    /** plan: before he signed in, `next` used to offer posture, dance, even riding out at dawn on a free day. */
    @Test fun signingInComesFirst() {
        val r = ToolRig(stage = 4)
        r.clock.set(TestClock.MONDAY.plusDays(5), "06:30")
        assertEquals("check_in", Today.plan(r.store, r.clock, r.rules).next?.id)
    }

    /** company: the spot check rang twice, once as its notice and once as its plan item. */
    @Test fun aSpotCheckRingsOnce() {
        val sim = Sim()
        sim.company.saveRules(Fixtures.rules(), "", "")
        sim.store.writeDoc(
            Doc.STAGE,
            StageState(joinedOn = "2029-12-10", stage = 4, startedOn = TestClock.MONDAY.toString(),
                rounds = listOf(StageRound(3, "2029-12-31", "2030-01-06", 100, 0, true)), closedThrough = "2030-01-06"),
        )
        var log = ReminderLog()
        val keys = ArrayList<String>()
        for (day in 0L..6L) {
            val d = TestClock.MONDAY.plusDays(day)
            sim.clock.set(d, "07:00")
            sim.company.direct("check_in", kotlinx.serialization.json.buildJsonObject { put("score", kotlinx.serialization.json.JsonPrimitive(4)) })
            var t = Days.at(d, java.time.LocalTime.of(16, 0), sim.clock.zone)
            while (t.isBefore(Days.at(d, java.time.LocalTime.of(19, 20), sim.clock.zone))) {
                sim.clock.instant = t
                val dec = sim.company.reminders(log)
                log = dec.log
                keys += dec.notices.map { it.key }
                t = t.plusSeconds(600)
            }
        }
        assertTrue(keys.any { it.startsWith("spot:") })
        assertFalse(keys.contains("spot"))
    }

    /** company: a grooming timer did not say what was groomed, so the same items were due again every day. */
    @Test fun groomingByTimerRecordsWhatWasDone() {
        val r = ToolRig(stage = 4)
        val company = Company(r.store, r.clock, Providers(ScriptedLlm(), ScriptedLlm()))
        r.at("08:00")
        assertTrue(company.timerDone("GROOMING", null, 10, null, 0).startsWith("✓"))
        assertEquals("指甲、修眉", r.store.entries(r.monday, Kind.TASK).last().read<TaskDone>().note)
        r.clock.set(r.monday.plusDays(1), "08:00")
        assertTrue(Today.plan(r.store, r.clock, r.rules).items.none { it.id == "task:${TaskId.GROOMING.name}" })
    }
}
