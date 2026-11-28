package app.agency.core.extras

import app.agency.core.agent.Company
import app.agency.core.agent.Providers
import app.agency.core.base.TestClock
import app.agency.core.llm.Fail
import app.agency.core.llm.ScriptedLlm
import app.agency.core.plan.ItemStatus
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.writeDoc
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixWaveExtrasTest {
    private val monday = TestClock.MONDAY

    /** B3: the task of the calendar week counts in the round in which he finished it. */
    @Test fun theRealTaskIsJudgedByTheDayItWasDone() {
        val store = MemStore()
        val clock = TestClock().also { it.set(monday, "10:00") }
        val tasks = RealTasks(store, clock)
        tasks.current(monday)
        val thursday = monday.plusDays(3)
        clock.set(thursday, "20:00")
        assertEquals(thursday.toString(), tasks.done()!!.doneOn)
        // A round from Wednesday: the task is stored on Monday (outside it) but was finished inside it.
        val wednesday = monday.plusDays(2)
        assertTrue(tasks.doneWithin(wednesday, wednesday.plusDays(6)))
        assertFalse(tasks.doneWithin(monday.plusDays(4), monday.plusDays(10)))

        // The home item agrees with the judgement.
        val stage = Kit.stage(4, startedOn = wednesday)
        val state = Kit.stateOf(store, thursday, stage, clock)
        val item = Extras.items(thursday, clock.now(), store, Kit.rules, stage, state).first { it.id == "real_task" }
        assertEquals(ItemStatus.DONE, item.status)
        val later = Kit.stage(4, startedOn = monday.plusDays(9))
        val st2 = Kit.stateOf(store, monday.plusDays(9), later, clock)
        assertEquals(ItemStatus.PENDING, Extras.items(monday.plusDays(9), clock.now(), store, Kit.rules, later, st2).first { it.id == "real_task" }.status)
    }

    /** B12: the monthly evaluation writes the body record once. */
    @Test fun theMonthlyEvaluationWritesTheBodyOnce() = runBlocking {
        val lastSunday = LocalDate.of(2030, 1, 27)
        val store = MemStore()
        val clock = TestClock().also { it.set(lastSunday, "14:00") }
        store.writeDoc(Doc.RULES, Kit.rules)
        store.writeDoc(Doc.STAGE, Kit.stage(4))
        val company = Company(store, clock, Providers(ScriptedLlm(), Kit.FakeLlm(app.agency.core.llm.ChatResult.Failed(Fail.RATE))))
        val song = company.recordSong(Kit.take(60 to 1500, 64 to 800), Kit.RATE, "月評歌")
        val r = company.submitMonthlyEval(MonthlyInput(song.entryId, "vault-dance", "vault-extra", "vault-body", 70.5, 80.0, 20, 60, "有練"))
        assertTrue(r.toString(), r is EvalResult.Ok)
        assertEquals(1, store.entries(lastSunday, Kind.BODY).size)
    }
}
