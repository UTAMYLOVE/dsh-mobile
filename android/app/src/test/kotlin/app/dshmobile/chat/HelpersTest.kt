package app.dshmobile.chat

import app.dshmobile.remote.Reminder
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    private fun h(timing: HelperTiming, paused: Boolean = false, session: String = "s1") = Helper("h1", session, "晨報", "整理晨報", timing, paused, 0)
    private fun reminder(session: String, active: Boolean = true) = Reminder("t1", session, "晨報", "p", "daily", "08:00", "Asia/Taipei", null, active)
    private fun taipei(s: String) = ZonedDateTime.parse(s + "+08:00[Asia/Taipei]").toInstant().toEpochMilli()

    @Test fun timingRendersInChinese() {
        assertEquals("每天 08:00", HelperTiming.Daily("08:00").zh)
        assertEquals("每週五 15:10", HelperTiming.Weekly(5, "15:10").zh)
        assertEquals("每 4 小時", HelperTiming.Every(240).zh)
        assertEquals("每 30 分鐘", HelperTiming.Every(30).zh)
    }

    @Test fun createPromptNamesScheduleCreateAndTiming() {
        val p = Helpers.createPrompt(h(HelperTiming.Weekly(1, "07:30")))
        assertTrue(p.contains("schedule_create"))
        assertTrue(p.contains("weekdays: [1]"))
        assertTrue(p.contains("time_zone: \"Asia/Taipei\""))
        assertTrue(p.contains("notify_user"))
        assertTrue(Helpers.createPrompt(h(HelperTiming.Every(60))).contains("every_seconds：3600"))
    }

    @Test fun lostScheduleShowsRecreate() {
        assertEquals(HelperState.ACTIVE, Helpers.stateOf(h(HelperTiming.Daily("08:00")), listOf(reminder("s1"))))
        assertEquals(HelperState.LOST, Helpers.stateOf(h(HelperTiming.Daily("08:00")), listOf(reminder("other"))))
        assertEquals(HelperState.PAUSED, Helpers.stateOf(h(HelperTiming.Daily("08:00"), paused = true), emptyList()))
    }

    @Test fun catalogFilteredToHelpers() {
        val cat = listOf(reminder("s1"), reminder("mine"), reminder("s2"))
        assertEquals(listOf("s1"), Helpers.ofHelpers(cat, listOf(h(HelperTiming.Daily("08:00")))).map { it.sessionId })
    }

    @Test fun nextRunInTaiwanTime() {
        val now = taipei("2026-11-06T09:00:00")
        assertEquals(taipei("2026-11-07T08:00:00"), Helpers.nextRun(h(HelperTiming.Daily("08:00")), now))
        assertEquals(taipei("2026-11-06T10:00:00"), Helpers.nextRun(h(HelperTiming.Daily("10:00")), now))
        // 2026-11-06 is a Friday
        assertEquals(taipei("2026-11-09T07:30:00"), Helpers.nextRun(h(HelperTiming.Weekly(1, "07:30")), now))
        assertEquals(null, Helpers.nextRun(h(HelperTiming.Daily("08:00"), paused = true), now))
    }

    @Test fun pauseKeepsTheSpecAndSurvivesRestart() {
        val file = File(Files.createTempDirectory("h").toFile(), HelperStore.FILE_NAME)
        val store = HelperStore(file) { 1 }
        val a = store.add("s1", "晨報", "整理晨報", HelperTiming.Daily("07:50"))
        store.add("s2", "盤後", "整理盤後", HelperTiming.Weekly(5, "15:10"))
        store.setPaused(a.id, true)
        val again = HelperStore(file).helpers.value
        assertEquals(2, again.size)
        assertTrue(again.first { it.id == a.id }.paused)
        assertEquals(HelperTiming.Weekly(5, "15:10"), again[1].timing)
        file.writeText("""{"helpers":[{"id":"x","sessionId":"s","kind":"every","minutes":5}]}""")
        assertTrue("too frequent is dropped", HelperStore(file).helpers.value.isEmpty())
    }
}
