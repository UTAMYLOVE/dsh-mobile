package app.dshmobile.coach

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner reads only Traditional Chinese: no coach string may carry a Latin letter (format placeholders aside). */
class CoachStringsTest {
    @Test fun coachStringsHaveNoLatinLetters() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val rows = Regex("<string name=\"((?:coach_|bar_coach|bar_free_chat)[a-z_]*)\"[^>]*>(.*?)</string>").findAll(xml).toList()
        assertTrue(rows.size > 40)
        val bad = rows.filter { Regex("[A-Za-z]").containsMatchIn(it.groupValues[2].replace(Regex("%\\d\\$[ds]"), "")) }.map { it.groupValues[1] }
        assertTrue("strings with Latin letters: $bad", bad.isEmpty())
    }

    @Test fun theCoachsOwnSentencesHaveNoLatinLetters() {
        val fixed = listOf(
            CoachReplies.DOCTOR, CoachReplies.ILL_NO_QUOTA, CoachReplies.NOT_UNDERSTOOD, CoachReplies.NO_PENDING, CoachReplies.UNVERIFIED,
            CoachReplies.OFF_PLAN, CoachReplies.CHAT_DOWN, CoachReplies.FOOD_INFO_UNCLEAR,
        ) + Fail.entries.map { CoachReplies.cannotReach(it) } +
            CoachRules.overQuota(380, 1700, 75.0, 80.0, 168.0) +
            CoachReplies.weight(79.2, 88.0, CoachProfile(), 75.0) + CoachSettings.DEEPSEEK_MODELS.map { it.zh } + CoachSettings.CLAUDE_MODELS.map { it.zh }
        val bad = fixed.filter { Regex("[A-Za-z]").containsMatchIn(it) }
        assertTrue("sentences with Latin letters: $bad", bad.isEmpty())
    }
}
