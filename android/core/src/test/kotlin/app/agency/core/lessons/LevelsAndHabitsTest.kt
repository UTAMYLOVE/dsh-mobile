package app.agency.core.lessons

import app.agency.core.base.TestClock
import app.agency.core.store.Doc
import app.agency.core.store.writeDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectLevelsTest {
    private val day0 = TestClock.MONDAY

    /** A subject-level lesson noted on day 0 and reviewed on day 2: everything a level asks for, except time. */
    private fun earnedRig(right: Int = 3, reviewed: Boolean = true): LessonRig {
        val rig = LessonRig()
        rig.noted(1, "語言", right)
        if (reviewed) {
            val id = rig.book.reviewDue(day0.plusDays(2)).first().first
            rig.goTo(day0.plusDays(2))
            rig.book.answerReview(id, LessonRig.answersWith(3))
        }
        return rig
    }

    @Test fun startsAtTheFirstLevel() {
        val rig = LessonRig()
        assertEquals("入門", rig.levels.level("語言"))
        assertEquals(listOf("入門", "基礎", "中級", "進階", "專業"), SubjectLevels.NAMES)
    }

    @Test fun levelsUpAfterFourteenDaysWithEverythingDone() {
        val rig = earnedRig()
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(13)))
        assertEquals("基礎", rig.levels.levelUpIfEarned("語言", day0.plusDays(14)))
        assertEquals("基礎", rig.levels.level("語言"))
        assertEquals(day0.plusDays(14).toString(), rig.levels.levels().subjects["語言"]?.since)
        // the clock restarts at the new level
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(15)))
    }

    @Test fun anAverageBelowEightyPercentHoldsTheLevel() {
        val rig = earnedRig(right = 2)
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(20)))
    }

    @Test fun anOverdueReviewHoldsTheLevel() {
        val rig = earnedRig(reviewed = false)
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(20)))
    }

    @Test fun aLessonNotYetNotedHoldsTheLevel() {
        val rig = earnedRig()
        rig.approved(2, "語言")
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(20)))
    }

    @Test fun aSubjectWithoutLessonsDoesNotMove() {
        val rig = LessonRig()
        assertNull(rig.levels.levelUpIfEarned("舞蹈", day0.plusDays(100)))
        assertEquals("入門", rig.levels.level("舞蹈"))
    }

    @Test fun neverGoesDownAndStopsAtTheTop() {
        val rig = earnedRig()
        rig.levels.levelUpIfEarned("語言", day0.plusDays(14))
        // a poor result at the new level changes nothing
        rig.goTo(day0.plusDays(15))
        rig.noted(2, "語言", right = 2)
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(60)))
        assertEquals("基礎", rig.levels.level("語言"))
        rig.store.writeDoc(Doc.LEVELS, Levels(mapOf("語言" to SubjectLevel(4, "2030-01-01"))))
        assertNull(rig.levels.levelUpIfEarned("語言", day0.plusDays(200)))
        assertEquals("專業", rig.levels.level("語言"))
    }

    @Test fun newLessonsAreProposedAtTheCurrentLevel() {
        val rig = earnedRig()
        rig.levels.levelUpIfEarned("語言", day0.plusDays(14))
        rig.goTo(day0.plusDays(15))
        assertEquals("基礎", rig.lesson(rig.propose(5, "語言")).level)
    }

    @Test fun otherFieldsOfTheDocumentSurviveALevelUp() {
        val rig = earnedRig()
        rig.store.writeDoc(Doc.LEVELS, rig.levels.levels().copy(trainingOffsets = mapOf("核心" to 2)))
        rig.levels.levelUpIfEarned("語言", day0.plusDays(14))
        assertEquals(mapOf("核心" to 2), rig.levels.levels().trainingOffsets)
    }

    @Test fun searchWordsFollowTheLevel() {
        val rig = earnedRig()
        val before = rig.levels.searchWords("語言")
        assertTrue(before.isNotBlank())
        rig.levels.levelUpIfEarned("語言", day0.plusDays(14))
        assertNotEquals(before, rig.levels.searchWords("語言"))
        assertTrue(rig.levels.searchQuery("語言").startsWith("語言"))
    }
}

class HabitsTest {
    private val day0 = TestClock.MONDAY

    private fun rig() = LessonRig()

    @Test fun theFirstHabitIsAlwaysAllowed() {
        val rig = rig()
        val habits = Habits(rig.store, rig.clock)
        assertTrue(habits.canAddNew(day0))
        assertNull(habits.add(" ", day0))
        assertTrue(habits.add("睡前伸展", day0) != null)
    }

    @Test fun aNewHabitNeedsTwelveOfTheLastFourteenDays() {
        val rig = rig()
        val habits = Habits(rig.store, rig.clock)
        val id = habits.add("睡前伸展", day0)!!
        assertFalse(habits.canAddNew(day0.plusDays(1)))
        assertNull(habits.add("喝溫水", day0.plusDays(1)))
        for (i in 0 until 11) habits.markDone(id, day0.plusDays(i.toLong()))
        assertFalse(habits.canAddNew(day0.plusDays(14)))
        habits.markDone(id, day0.plusDays(11))
        assertTrue(habits.canAddNew(day0.plusDays(14)))
        assertTrue(habits.add("喝溫水", day0.plusDays(14)) != null)
        // now the newest habit is the one that counts
        assertFalse(habits.canAddNew(day0.plusDays(15)))
    }

    @Test fun oldDaysOutsideTheWindowDoNotCount() {
        val rig = rig()
        val habits = Habits(rig.store, rig.clock)
        val id = habits.add("睡前伸展", day0)!!
        for (i in 0 until 12) habits.markDone(id, day0.plusDays(i.toLong()))
        assertTrue(habits.canAddNew(day0.plusDays(14)))
        assertFalse(habits.canAddNew(day0.plusDays(20)))
    }

    @Test fun todayListsActiveHabitsWithTheirDoneFlag() {
        val rig = rig()
        val habits = Habits(rig.store, rig.clock)
        val id = habits.add("睡前伸展", day0)!!
        assertEquals(listOf(false), habits.today(day0).map { it.done })
        assertEquals("睡前伸展", habits.today(day0).single().name)
        assertTrue(habits.markDone(id, day0))
        assertTrue(habits.markDone(id, day0))
        assertEquals(listOf(true), habits.today(day0).map { it.done })
        assertEquals(listOf(false), habits.today(day0.plusDays(1)).map { it.done })
        assertTrue(habits.today(day0.minusDays(1)).isEmpty())
    }

    @Test fun markingNeedsAnActiveHabitThatHasStarted() {
        val rig = rig()
        val habits = Habits(rig.store, rig.clock)
        val id = habits.add("睡前伸展", day0)!!
        assertFalse(habits.markDone(999, day0))
        assertFalse(habits.markDone(id, day0.minusDays(1)))
        assertTrue(habits.retire(id))
        assertFalse(habits.markDone(id, day0))
        assertTrue(habits.today(day0).isEmpty())
        // a retired habit still counts as the previous one for the gate
        assertFalse(habits.canAddNew(day0.plusDays(3)))
    }
}
