package app.agency.core.remind

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.LessonAway
import app.agency.core.plan.DayPlan
import app.agency.core.plan.DayType
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.Screen
import app.agency.core.plan.Tap
import app.agency.core.stage.Feature
import app.agency.core.store.Memo
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val zone = TestClock.ZONE
    private val day = TestClock.MONDAY
    private val bedtime = LocalTime.of(1, 0)

    private fun at(hhmm: String): Instant = Days.at(day, LocalTime.parse(hhmm), zone)

    private fun item(
        id: String,
        time: String?,
        status: ItemStatus = ItemStatus.PENDING,
        chase: Boolean = true,
        title: String = "吃飯",
    ) = PlanItem(
        id = id, feature = Feature.MEALS, title = title, at = time?.let { LocalTime.parse(it) }, until = null, status = status,
        counts = true, tap = Tap.Open(Screen.CAMERA), chase = chase,
    )

    private fun plan(vararg items: PlanItem, riding: Boolean = false, restDay: Boolean = false) = DayPlan(
        day = day, type = DayType.CLASS, items = items.toList(), next = null, adjustments = emptyList(), riding = riding,
        restDay = restDay, unwell = false, done = 0, total = items.size,
    )

    private fun decide(
        now: Instant,
        plan: DayPlan,
        log: ReminderLog = ReminderLog(),
        checkedIn: Boolean = true,
        checkedOut: Boolean = false,
        bed: LocalTime? = bedtime,
        memos: List<Pair<Long, Memo>> = emptyList(),
        away: LessonAway? = null,
        extra: List<Notice> = emptyList(),
    ) = Reminders.decide(now, zone, plan, checkedIn, checkedOut, bed, memos, away, log, extra)

    @Test fun anItemTurningDueGivesOneLoudNotice() {
        val p = plan(item("meal:FIRST", "12:00"))
        val d = decide(at("12:00"), p)
        assertEquals(1, d.notices.size)
        val n = d.notices.single()
        assertEquals("meal:FIRST", n.key)
        assertTrue(n.loud)
        assertTrue(n.text.isNotBlank())
        assertEquals(listOf(at("12:00")), d.log.times("meal:FIRST"))
    }

    @Test fun nothingBeforeItIsDueAndTheWakeIsItsTime() {
        val p = plan(item("meal:FIRST", "12:00"))
        val d = decide(at("11:00"), p)
        assertTrue(d.notices.isEmpty())
        assertEquals(at("12:00"), d.nextWakeAt)
    }

    @Test fun aLateItemIsChasedEveryThirtyMinutes() {
        val p = plan(item("meal:FIRST", "12:00", ItemStatus.LATE))
        var d = decide(at("12:00"), p)
        assertEquals(1, d.notices.size)
        d = decide(at("12:29"), p, d.log)
        assertTrue(d.notices.isEmpty())
        d = decide(at("12:30"), p, d.log)
        assertEquals(1, d.notices.size)
        assertEquals(2, d.log.times("meal:FIRST").size)
        d = decide(at("12:59"), p, d.log)
        assertTrue(d.notices.isEmpty())
        d = decide(at("13:30"), p, d.log)
        assertEquals(1, d.notices.size)
        assertEquals(3, d.log.times("meal:FIRST").size)
    }

    @Test fun anItemFirstSeenLateIsNoticedOnce() {
        val p = plan(item("meal:FIRST", "12:00", ItemStatus.LATE))
        val d = decide(at("14:10"), p)
        assertEquals(1, d.notices.size)
        assertTrue(decide(at("14:10"), p, d.log).notices.isEmpty())
    }

    @Test fun chasingStopsWhenDone() {
        val late = plan(item("meal:FIRST", "12:00", ItemStatus.LATE))
        val first = decide(at("12:00"), late)
        val done = plan(item("meal:FIRST", "12:00", ItemStatus.DONE))
        assertTrue(decide(at("12:30"), done, first.log).notices.isEmpty())
        val off = plan(item("meal:FIRST", "12:00", ItemStatus.OFF))
        assertTrue(decide(at("12:30"), off, first.log).notices.isEmpty())
    }

    @Test fun chasingStopsAtCheckOut() {
        val late = plan(item("meal:FIRST", "12:00", ItemStatus.LATE))
        val first = decide(at("12:00"), late)
        val d = decide(at("12:30"), late, first.log, checkedOut = true)
        assertTrue(d.notices.isEmpty())
    }

    @Test fun chasingStopsTwoHoursAfterBedtime() {
        val late = plan(item("night", "21:30", ItemStatus.LATE))
        var d = decide(at("21:30"), late)
        var now = at("21:30")
        // keep going until just before the cut: bedtime 01:00 the next calendar day, so 03:00
        val cut = Days.at(day, bedtime, zone).plus(Duration.ofHours(2))
        var count = 1
        while (true) {
            now = now.plus(Duration.ofMinutes(30))
            if (now >= cut) break
            d = decide(now, late, d.log)
            count += d.notices.size
        }
        assertEquals(11, count)
        assertTrue(decide(cut, late, d.log).notices.isEmpty())
        assertTrue(decide(cut.plusSeconds(3600), late, d.log).notices.isEmpty())
    }

    @Test fun withoutABedtimeTheDayEndIsTheLimit() {
        val late = plan(item("night", "21:30", ItemStatus.LATE))
        val d = decide(at("21:30"), late, bed = null)
        assertEquals(1, d.notices.size)
        assertEquals(1, decide(at("05:00"), late, d.log, bed = null).notices.size)
        val dayEnd = at("05:59").plusSeconds(60)
        assertTrue(decide(dayEnd, late, d.log, bed = null).notices.isEmpty())
    }

    @Test fun whileRidingOnlyTheFirstNoticeIsLoud() {
        val late = plan(item("task:CORE", "12:00", ItemStatus.LATE), riding = true)
        val first = decide(at("12:00"), late)
        assertTrue(first.notices.single().loud)
        val second = decide(at("12:30"), late, first.log)
        assertEquals(1, second.notices.size)
        assertFalse(second.notices.single().loud)
        val notRiding = plan(item("task:CORE", "12:00", ItemStatus.LATE))
        assertTrue(decide(at("12:30"), notRiding, first.log).notices.single().loud)
    }

    @Test fun nothingBeforeCheckInExceptTheWakeNoticeAndItsChase() {
        val p = plan(item("check_in", "07:00", ItemStatus.LATE, title = "簽到"), item("meal:FIRST", "07:00", ItemStatus.LATE))
        var d = decide(at("07:00"), p, checkedIn = false)
        assertEquals(listOf("check_in"), d.notices.map { it.key })
        d = decide(at("07:30"), p, d.log, checkedIn = false)
        assertEquals(listOf("check_in"), d.notices.map { it.key })
        // once he is in, the rest follows
        d = decide(at("07:40"), p, d.log, checkedIn = true)
        assertEquals(listOf("meal:FIRST"), d.notices.map { it.key })
    }

    @Test fun restDayEveningItemsAreNotChased() {
        val p = plan(item("task:WALK", "20:00", ItemStatus.LATE, chase = false), restDay = true)
        val first = decide(at("20:00"), p)
        assertEquals(1, first.notices.size)
        assertTrue(decide(at("20:30"), p, first.log).notices.isEmpty())
        assertTrue(decide(at("22:00"), p, first.log).notices.isEmpty())
    }

    @Test fun itemsWithoutATimeAreNeverNoticed() {
        val p = plan(item("task:POSTURE", null))
        val d = decide(at("15:00"), p)
        assertTrue(d.notices.isEmpty())
    }

    @Test fun aMemoDueGivesOneLoudNotice() {
        val memo = Memo("買電池", dueAt = at("15:00").toString())
        val p = plan()
        val before = decide(at("14:00"), p, memos = listOf(7L to memo))
        assertTrue(before.notices.isEmpty())
        assertEquals(at("15:00"), before.nextWakeAt)
        val d = decide(at("15:00"), p, memos = listOf(7L to memo))
        val n = d.notices.single()
        assertEquals("memo:7", n.key)
        assertTrue(n.loud)
        assertTrue(n.text.contains("買電池"))
        assertTrue(decide(at("15:05"), p, d.log, memos = listOf(7L to memo)).notices.isEmpty())
    }

    @Test fun finishedOrAlreadyNotifiedMemosAreSkipped() {
        val due = at("10:00").toString()
        val memos = listOf(
            1L to Memo("甲", dueAt = due, done = true),
            2L to Memo("乙", dueAt = due, notified = true),
            3L to Memo("丙", dueAt = null),
            4L to Memo("丁", dueAt = "不是時間"),
        )
        assertTrue(decide(at("11:00"), plan(), memos = memos).notices.isEmpty())
    }

    @Test fun aMemoStillReachesHimBeforeCheckInButNotAfterCheckOut() {
        val memo = Memo("交報告", dueAt = at("06:30").toString())
        assertEquals(1, decide(at("06:30"), plan(), checkedIn = false, memos = listOf(1L to memo)).notices.size)
        assertTrue(decide(at("06:30"), plan(), checkedOut = true, memos = listOf(1L to memo)).notices.isEmpty())
    }

    @Test fun aLessonHeDidNotReturnFromGetsOneNotice() {
        val away = LessonAway(5, "發音入門", at("14:00"), minutes = 10)
        val p = plan()
        val early = decide(at("14:14"), p, away = away)
        assertTrue(early.notices.isEmpty())
        assertEquals(at("14:15"), early.nextWakeAt)
        val d = decide(at("14:15"), p, away = away)
        assertEquals("lesson_away:5", d.notices.single().key)
        assertTrue(d.notices.single().text.contains("發音入門"))
        assertTrue(decide(at("15:00"), p, d.log, away = away).notices.isEmpty())
    }

    @Test fun anUnknownVideoLengthWaitsTwentyMinutes() {
        val away = LessonAway(5, "發音入門", at("14:00"))
        assertTrue(decide(at("14:19"), plan(), away = away).notices.isEmpty())
        assertEquals(1, decide(at("14:20"), plan(), away = away).notices.size)
    }

    @Test fun nextWakeIsTheEarliestOfItemChaseAndMemo() {
        val p = plan(item("meal:DINNER", "18:00"), item("meal:NIGHT", "21:30"))
        assertEquals(at("18:00"), decide(at("17:00"), p).nextWakeAt)
        val memo = Memo("電話", dueAt = at("17:20").toString())
        assertEquals(at("17:20"), decide(at("17:00"), p, memos = listOf(1L to memo)).nextWakeAt)
        val late = plan(item("meal:DINNER", "18:00", ItemStatus.LATE))
        val d = decide(at("18:10"), late)
        assertEquals(at("18:40"), d.nextWakeAt)
        val later = decide(at("18:20"), late, d.log)
        assertEquals(at("18:40"), later.nextWakeAt)
    }

    @Test fun nextWakeIsNeverMoreThanSixHoursAway() {
        val p = plan(item("meal:NIGHT", "23:00"))
        assertEquals(at("12:00").plus(Duration.ofHours(6)), decide(at("12:00"), p).nextWakeAt)
        assertEquals(at("12:00").plus(Duration.ofHours(6)), decide(at("12:00"), plan()).nextWakeAt)
        val memo = Memo("明年的事", dueAt = at("12:00").plus(Duration.ofDays(30)).toString())
        assertEquals(at("12:00").plus(Duration.ofHours(6)), decide(at("12:00"), plan(), memos = listOf(1L to memo)).nextWakeAt)
    }

    @Test fun nextWakeIsAlwaysInTheFuture() {
        val p = plan(item("meal:FIRST", "12:00", ItemStatus.LATE), item("night", "13:00", ItemStatus.MISSED))
        val now = at("12:00")
        val d = decide(now, p)
        assertTrue(d.nextWakeAt > now)
        val afterEnd = decide(at("12:00").plus(Duration.ofHours(30)), p, d.log)
        assertTrue(afterEnd.notices.isEmpty())
        assertEquals(at("12:00").plus(Duration.ofHours(36)), afterEnd.nextWakeAt)
    }

    @Test fun askingTwiceAtTheSameInstantSendsOnce() {
        val p = plan(item("meal:FIRST", "12:00", ItemStatus.LATE), item("task:CORE", "11:00"))
        val memo = Memo("電話", dueAt = at("11:30").toString())
        val away = LessonAway(5, "發音入門", at("10:00"), 10)
        val now = at("12:30")
        val a = decide(now, p, memos = listOf(1L to memo), away = away)
        assertEquals(4, a.notices.size)
        val b = decide(now, p, a.log, memos = listOf(1L to memo), away = away)
        assertTrue(b.notices.isEmpty())
        assertEquals(a.nextWakeAt, b.nextWakeAt)
        assertEquals(a.log, b.log)
    }

    @Test fun aLogOfAnotherDayStartsFresh() {
        val p = plan(item("meal:FIRST", "12:00"))
        val old = ReminderLog("2030-01-06", mapOf("meal:FIRST" to listOf(Instant.parse("2030-01-06T04:00:00Z").toString())))
        val d = decide(at("12:00"), p, old)
        assertEquals(1, d.notices.size)
        assertEquals(day.toString(), d.log.day)
    }

    @Test fun extraNoticesPassThroughOnceAndAreLogged() {
        val spot = Notice("spot:1", "突擊檢查", "現在，拍一張你桌上的照片。", loud = true)
        val d = decide(at("12:00"), plan(), extra = listOf(spot))
        assertEquals(listOf(spot), d.notices)
        assertTrue(decide(at("12:00"), plan(), d.log, extra = listOf(spot)).notices.isEmpty())
    }

    @Test fun linesVaryAndNeverContainLatinLetters() {
        val texts = HashSet<String>()
        for (key in listOf("meal:FIRST", "meal:DINNER", "task:CORE", "water", "task:WALK")) {
            texts += Lines.first(day, key, "吃飯")
            for (n in 1..6) texts += Lines.chase(day, key, "吃飯", n)
        }
        texts += Lines.memo(day, "memo:1", "買電池")
        texts += Lines.lessonAway(day, "lesson_away:1", "發音入門")
        assertTrue(texts.size >= 8)
        val latin = Regex("[A-Za-z]")
        assertTrue(texts.none { latin.containsMatchIn(it) })
        // consecutive chases of one item never repeat the previous line
        for (n in 1..12) assertTrue(Lines.chase(day, "meal:FIRST", "吃飯", n) != Lines.chase(day, "meal:FIRST", "吃飯", n + 1))
        // same inputs, same line
        assertEquals(Lines.first(day, "water", "喝水"), Lines.first(day, "water", "喝水"))
    }
}
