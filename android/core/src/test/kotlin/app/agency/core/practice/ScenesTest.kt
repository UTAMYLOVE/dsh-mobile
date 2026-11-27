package app.agency.core.practice

import app.agency.core.brain.Brain
import app.agency.core.engine.Scenes
import app.agency.core.guard.Evidence
import app.agency.core.guard.GuardMode
import app.agency.core.guard.OutputGuard
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新鮮感: a scene a day, the kind turning, special days, the guard on every line, once a day. Invented data. */
class ScenesTest {
    private val monday = LocalDate.parse("2026-12-07")

    @Test fun theKindTurnsAndNeverRepeatsWithinARound() {
        val kinds = (0L until Scenes.KINDS.toLong()).map { Scenes.of(monday.plusDays(it)).first }
        assertEquals(Scenes.KINDS, kinds.toSet().size)
    }

    @Test fun everyLinePassesTheGuardAndSaysNothingAboutHim() {
        Scenes.ALL.flatMap { it.lines }.forEach { line ->
            val g = OutputGuard.check(line, Evidence(emptyList()), GuardMode.NORMAL)
            assertTrue(line, g.hard.isEmpty() && g.kept == line)
            assertTrue(line, line.none { it.isDigit() })
            listOf("想你", "寂寞", "難過", "不理我", "對不起我").forEach { w -> assertTrue(line, !line.contains(w)) }
        }
    }

    @Test fun specialDays() {
        assertTrue(Scenes.special(LocalDate.parse("2026-12-30"), "2026-12-01")!!.contains("第 30 天"))
        assertTrue(Scenes.special(LocalDate.parse("2027-03-10"), "2026-12-01")!!.contains("第 100 天"))
        assertTrue(Scenes.special(LocalDate.parse("2026-12-25"), null)!!.contains("聖誕"))
        assertNull(Scenes.special(LocalDate.parse("2026-12-08"), "2026-12-01"))
        assertTrue(Scenes.opening(monday, null, themeOpen = true).contains("這週是"))
    }

    @Test fun onceADayAtTheFirstEvent() {
        val store = InMemoryAgencyStore()
        val clock = MutableClock()
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
        clock.now = monday.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute("09:00").toLong()).toInstant()
        var n = 0
        val b = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
        runBlocking { b.tick(); b.tick() }
        assertEquals(1, runBlocking { store.messages.recent(50) }.count { it.who == Who.COMPANY && it.kind == Brain.SCENE_KIND })
    }
}
