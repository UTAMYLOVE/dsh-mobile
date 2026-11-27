package app.agency.core.practice

import app.agency.core.brain.Brain
import app.agency.core.engine.Levels
import app.agency.core.lessons.LessonPlan
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.LevelSpec
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SubjectLevel
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每科等級 (T19e): the level-up rule's edges, never down, the search words and the quiz line, the card, the ceremony. Invented data. */
class LevelsTest {
    private val start = LocalDate.parse("2026-12-01")

    private fun lesson(i: Int, day: LocalDate, score: Int? = 3, cat: LessonCategory = LessonCategory.ETIQUETTE, review: Int? = 3, practical: String? = null) = Lesson(
        "l$i", "r$i", cat, title = "課 $i", url = "https://example.invalid/$i", source = "教材", status = LessonStatus.LISTED, foundAt = 0, listedAt = 0,
        passedAt = 1, passedDay = day.toString(), score = score, reviewDay = day.plusDays(2).toString(), reviewScore = review, practicalMediaId = practical,
    )

    private val three = listOf(lesson(1, start), lesson(2, start.plusDays(3)), lesson(3, start.plusDays(6), score = 2))

    @Test fun theRuleEdges() {
        val at = SubjectLevel()
        val cat = LessonCategory.ETIQUETTE
        // 3, 3, 2 of 3: 88.9 %, two weeks from the first: up.
        assertTrue(Levels.ready(cat, at, three, start.plusDays(14)))
        assertFalse(Levels.ready(cat, at, three, start.plusDays(13))) // not two weeks yet
        assertFalse(Levels.ready(cat, at, three.take(2), start.plusDays(20))) // too few lessons
        val low = three.map { it.copy(score = 2) } // 66.7 %
        assertFalse(Levels.ready(cat, at, low, start.plusDays(20)))
        val failedReview = three.mapIndexed { i, l -> if (i == 0) l.copy(reviewScore = 1) else l }
        assertFalse(Levels.ready(cat, at, failedReview, start.plusDays(20)))
        // A subject with a 實作 needs every one handed in.
        val vocal = three.map { it.copy(category = LessonCategory.VOCAL) }
        assertFalse(Levels.ready(LessonCategory.VOCAL, at, vocal, start.plusDays(20)))
        assertTrue(Levels.ready(LessonCategory.VOCAL, at, vocal.map { it.copy(practicalMediaId = "m") }, start.plusDays(20)))
        // A week of the 面談 does not count.
        val monday = LocalDate.parse("2026-11-30")
        assertFalse(Levels.ready(cat, at, three, start.plusDays(20), blockedWeeks = setOf(monday)))
        // 專業 is the top; the level never goes down.
        assertFalse(Levels.ready(cat, SubjectLevel(Levels.TOP, start.toString()), three, start.plusDays(40)))
        val spec = LevelSpec(mapOf(cat.name to SubjectLevel(3, "2026-01-01")))
        assertEquals(3, Levels.apply(spec, mapOf(cat to 1), start).of(cat.name).level)
        // At the new level, lessons before it no longer count.
        assertFalse(Levels.ready(cat, SubjectLevel(1, start.plusDays(14).toString()), three, start.plusDays(40)))
    }

    @Test fun theSearchAndTheQuizFollowTheLevel() {
        assertTrue(LessonPlan.query(LessonCategory.VOCAL, level = 0).contains("入門"))
        assertTrue(LessonPlan.query(LessonCategory.VOCAL, level = 3).contains("進階"))
        assertTrue(Levels.quizLine(4).contains("專業"))
    }

    @Test fun aDayStartRaisesTheLevelWithACeremonyAndTheCardShowsIt() {
        val store = InMemoryAgencyStore()
        val clock = MutableClock()
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            PlanStore(store).seedIfMissing(0, app.agency.core.schedule.RolloutSpec(week = 1))
            three.forEach { store.lessons.insert(it) }
        }
        val day = start.plusDays(15)
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute("10:00").toLong()).toInstant()
        var n = 0
        val b = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
        val said = runBlocking { b.onText("理論課") }.filter { it.who == Who.COMPANY }.joinToString { it.text }
        assertTrue(said, said.contains("升級典禮：禮儀升到「基礎」"))
        assertEquals(listOf("禮儀" to "基礎"), runBlocking { b.card() }.levels)
        assertEquals(1, runBlocking { b.levelCards() }.size)
    }
}
