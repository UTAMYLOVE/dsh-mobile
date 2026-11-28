package app.agency.core.extras

import app.agency.core.base.TestClock
import app.agency.core.llm.Fail
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Violation
import app.agency.core.store.add
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationsTest {
    private val sunday = LocalDate.of(2030, 1, 13)
    private val lastSunday = LocalDate.of(2030, 1, 27)
    private val store = MemStore()
    private val clock = TestClock().also { it.set(sunday, "14:00") }
    private val book = SongBook(store, clock)
    private val song = book.record(Kit.take(60 to 1500, 0 to 600, 64 to 800, 67 to 700), Kit.RATE, "測試歌")

    private fun evals(llm: Kit.FakeLlm? = null) = Evaluations(store, clock, Kit.rules, llm?.let { EvalSuggester(it) })

    private fun monthly(song: Long = this.song.entryId) =
        MonthlyInput(song, "vault-dance", "vault-extra", "vault-body", 70.5, 80.0, 20, 60, "這個月有認真練")

    @Test fun weeklyCanBeHandedInOnlyOnce() = runBlocking {
        val e = evals()
        assertTrue(e.weeklyDue(sunday))
        assertFalse(e.weeklyDue(sunday.minusDays(1)))
        val first = e.submitWeekly(song.entryId, "vault-dance") as EvalResult.Ok
        assertEquals(EvalType.WEEKLY, first.rec.type)
        assertEquals("2030-01-07", first.rec.period)
        assertEquals(1, store.entries(sunday, Kind.EVAL).size)
        assertFalse(e.weeklyDue(sunday))
        val second = e.submitWeekly(song.entryId, "vault-dance2") as EvalResult.Refused
        assertTrue(second.reason.contains("不能重來"))
        assertEquals(1, store.entries(sunday, Kind.EVAL).size)
        // The next Sunday is a new chance.
        clock.set(sunday.plusDays(7), "14:00")
        assertTrue(e.weeklyDue(sunday.plusDays(7)))
    }

    @Test fun weeklyRefusals() = runBlocking {
        val e = evals()
        assertTrue(e.submitWeekly(9999, "d") is EvalResult.Refused)
        assertTrue(e.submitWeekly(song.entryId, " ") is EvalResult.Refused)
        // A voice-less take is still a take: vocal score 1 comes from code.
        clock.set(sunday.minusDays(1), "14:00")
        assertTrue(e.submitWeekly(song.entryId, "d") is EvalResult.Refused)
        // Closed feature.
        clock.set(sunday, "14:00")
        val r = e.submitWeekly(song.entryId, "d", stage = Kit.stage(3)) as EvalResult.Refused
        assertTrue(r.reason.contains("還沒開放"))
        assertFalse(e.weeklyDue(sunday, Kit.stage(3)))
    }

    @Test fun codeScoresVocalAndAttitude() = runBlocking {
        // Four days checked in and out, two violations.
        (7..10).forEach { d ->
            val day = LocalDate.of(2030, 1, d)
            clock.set(day, "07:00")
            store.add(day, clock.now(), Kind.CHECK_IN, app.agency.core.store.CheckIn(4))
            store.add(day, clock.now(), Kind.CHECK_OUT, app.agency.core.store.CheckOut())
        }
        store.add(LocalDate.of(2030, 1, 8), clock.now(), Kind.VIOLATION, Violation("OVER_KCAL"))
        store.add(LocalDate.of(2030, 1, 9), clock.now(), Kind.VIOLATION, Violation("OVER_KCAL"))
        clock.set(sunday, "14:00")
        val r = evals().submitWeekly(song.entryId, "d") as EvalResult.Ok
        val attitude = r.rec.scores.first { it.dim == EvalDim.ATTITUDE }
        // 4 of 7 days = 57% -> 2, two violations -> -1.
        assertEquals(1, attitude.value)
        assertEquals(ScoreSource.CODE, attitude.source)
        val vocal = r.rec.scores.first { it.dim == EvalDim.VOCAL }
        assertEquals(book.vocalScore(book.song(song.entryId)!!), vocal.value)
        assertEquals(ScoreSource.CODE, vocal.source)
        assertEquals(2, r.rec.scores.size)
        assertTrue(r.card.lines.any { it.contains("沒有建議分數") })
    }

    @Test fun suggesterScoresAreLabelledAndSeeOnlyFacts() = runBlocking {
        val llm = Kit.FakeLlm(Kit.ok(Kit.GOOD_SUGGESTION))
        val r = evals(llm).submitWeekly(song.entryId, "vault-dance", selfReport = "我這週有練舞") as EvalResult.Ok
        assertEquals(EvalDim.values().toList(), r.rec.scores.map { it.dim })
        val dance = r.rec.scores.first { it.dim == EvalDim.DANCE }
        assertEquals(3, dance.value)
        assertEquals(ScoreSource.SUGGESTED, dance.source)
        assertEquals("動作大致跟上拍子", dance.reason)
        assertTrue(r.card.lines.any { it.startsWith("舞蹈：3 分（建議分數）") })
        val req = llm.requests.single()
        assertEquals(Purpose.JUDGE, req.purpose)
        assertTrue(req.output is Output.Json)
        val text = (req.turns.single() as Turn.User).text
        assertTrue(text.contains("我這週有練舞"))
        assertTrue(text.contains("違規 0 次"))
        assertFalse(text.contains("vault-dance"))
    }

    @Test fun suggesterFallsBackToNoSuggestion() = runBlocking {
        val bad = listOf(
            "不是 JSON",
            """{"dance":{"score":9,"reason":"太高"},"visual":{"score":4,"reason":"好"},"star":{"score":2,"reason":"差"}}""",
            """{"dance":{"score":3,"reason":""},"visual":{"score":4,"reason":"好"},"star":{"score":2,"reason":"差"}}""",
            """{"dance":{"score":3,"reason":"good job"},"visual":{"score":4,"reason":"好"},"star":{"score":2,"reason":"差"}}""",
            """{"dance":{"score":3,"reason":"好"},"visual":{"score":4,"reason":"好"}}""",
            "",
        )
        bad.forEach { text ->
            assertNull(text, EvalSuggester.parse(text))
        }
        assertNotNull(EvalSuggester.parse("```json\n${Kit.GOOD_SUGGESTION}\n```"))
        val llm = Kit.FakeLlm(Kit.ok(bad[1]))
        val r = evals(llm).submitWeekly(song.entryId, "d") as EvalResult.Ok
        assertEquals(listOf(EvalDim.VOCAL, EvalDim.ATTITUDE), r.rec.scores.map { it.dim })
        // A failed call is the same.
        val failed = Kit.FakeLlm(app.agency.core.llm.ChatResult.Failed(Fail.RATE))
        clock.set(sunday.plusDays(7), "14:00")
        val r2 = evals(failed).submitWeekly(song.entryId, "d") as EvalResult.Ok
        assertEquals(2, r2.rec.scores.size)
    }

    @Test fun monthlyOnTheLastSundayWithCompareAndPersonalTask() = runBlocking {
        val e = evals(Kit.FakeLlm(Kit.ok(Kit.GOOD_SUGGESTION)))
        assertFalse(e.monthlyDue(LocalDate.of(2030, 1, 20)))
        assertTrue(e.monthlyDue(lastSunday))
        assertFalse(e.monthlyDue(lastSunday.plusDays(1)))
        // A Sunday that is not the last one is refused.
        assertTrue(e.submitMonthly(monthly()) is EvalResult.Refused)
        // December's evaluation exists.
        val dec = EvalRec(
            EvalType.MONTHLY, "2029-12", emptyList(),
            listOf(
                EvalScore(EvalDim.VOCAL, 2, ScoreSource.CODE, ""), EvalScore(EvalDim.DANCE, 1, ScoreSource.SUGGESTED, ""),
                EvalScore(EvalDim.VISUAL, 3, ScoreSource.SUGGESTED, ""), EvalScore(EvalDim.STAR, 3, ScoreSource.SUGGESTED, ""),
                EvalScore(EvalDim.ATTITUDE, 3, ScoreSource.CODE, ""),
            ),
            body = EvalBody("p", 72.0, 82.0, 15, 50),
        )
        store.add(LocalDate.of(2029, 12, 30), clock.now(), Kind.EVAL, dec)
        clock.set(lastSunday, "14:00")
        val r = e.submitMonthly(monthly()) as EvalResult.Ok
        assertEquals("2030-01", r.rec.period)
        assertEquals(3, r.rec.items.size)
        assertEquals(e.rotatingItem(YearMonth.of(2030, 1)), r.rec.items[2].name)
        assertEquals("vault-body", r.rec.body!!.photoId)
        assertEquals(EvalDim.values().toList(), r.rec.scores.map { it.dim })
        assertFalse(e.monthlyDue(lastSunday))
        assertTrue(e.submitMonthly(monthly()) is EvalResult.Refused)
        // The body numbers are also an ordinary body record (photo timeline, weight history).
        assertEquals(listOf("vault-body"), BodyPhotos(store, clock).timeline().map { it.photoId })
        val cmp = e.compare(r.rec, dec)
        assertEquals(2, cmp.scoreDeltas[EvalDim.DANCE])
        assertEquals(-1.5, cmp.weightKg!!, 0.001)
        assertEquals(-2.0, cmp.waistCm!!, 0.001)
        assertEquals(5, cmp.pushUps)
        assertEquals(10, cmp.plankSeconds)
        assertTrue(cmp.progressed)
        assertTrue(r.card.lines.contains("和上個月比："))
        assertTrue(r.card.lines.any { it.startsWith("體重：少 1.5 公斤") })
        // Next month's task is the weakest score, ten minutes a day, and is stored once.
        val task = e.personalTask(YearMonth.of(2030, 2))!!
        assertEquals(10, task.minutes)
        assertEquals(r.rec.scores.minByOrNull { it.value }!!.dim, task.dim)
        assertEquals(task, e.peekPersonalTask(YearMonth.of(2030, 2)))
        assertTrue(r.card.lines.any { it.contains("個人任務") })
        assertNull(e.personalTask(YearMonth.of(2030, 5)))
    }

    @Test fun monthlyNeedsEverything() = runBlocking {
        clock.set(lastSunday, "14:00")
        val e = evals()
        listOf(
            monthly(9999), monthly().copy(danceRecordingId = ""), monthly().copy(rotatingRef = ""),
            monthly().copy(bodyPhotoId = ""), monthly().copy(weightKg = 0.0), monthly().copy(pushUps = -1),
        ).forEach { assertTrue(e.submitMonthly(it) is EvalResult.Refused) }
        assertTrue(store.entries(lastSunday, Kind.EVAL).isEmpty())
        assertTrue(e.submitMonthly(monthly(), Kit.stage(3)) is EvalResult.Refused)
    }

    private fun monthRec(period: String, vocal: Int, pushUps: Int = 10, plank: Int = 40) = EvalRec(
        EvalType.MONTHLY, period, emptyList(),
        listOf(EvalScore(EvalDim.VOCAL, vocal, ScoreSource.CODE, ""), EvalScore(EvalDim.ATTITUDE, 3, ScoreSource.CODE, "")),
        body = EvalBody("p", 70.0, 80.0, pushUps, plank),
    )

    @Test fun stalledAfterTwoOrThreeMonthsWithoutProgress() {
        val e = evals()
        fun put(rec: EvalRec) = store.add(LocalDate.parse(rec.period + "-28"), clock.now(), Kind.EVAL, rec)
        put(monthRec("2029-10", 3))
        put(monthRec("2029-11", 3))
        assertFalse(e.stalled(2))
        put(monthRec("2029-12", 3))
        assertTrue(e.stalled(2))
        assertFalse("needs four evaluations", e.stalled(3))
        put(monthRec("2030-01", 2))
        assertTrue(e.stalled(3))
        // Progress in the fitness test alone ends it.
        put(monthRec("2030-02", 2, pushUps = 12))
        assertFalse(e.stalled(2))
        assertFalse(e.stalled(3))
        put(monthRec("2030-03", 2, pushUps = 12))
        put(monthRec("2030-04", 2, pushUps = 12))
        assertTrue(e.stalled(2))
        assertEquals(listOf("2029-10", "2029-11", "2029-12", "2030-01", "2030-02", "2030-03", "2030-04"), e.monthlyEvals().map { it.period })
    }
}
