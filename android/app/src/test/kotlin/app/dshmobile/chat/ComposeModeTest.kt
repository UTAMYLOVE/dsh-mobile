package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeModeTest {
    @Test fun researchPrefixesTheSkillAndTheBubbleHidesIt() {
        val sent = ComposeMode.RESEARCH.apply("AI 伺服器供應鏈")
        assertEquals("請用 dshm-research 技能：AI 伺服器供應鏈", sent)
        assertEquals("AI 伺服器供應鏈", ComposeMode.strip(sent))
        assertEquals("一般問題", ComposeMode.NORMAL.apply("一般問題"))
        assertEquals("請用 這個字：不是前綴", ComposeMode.strip("請用 這個字：不是前綴"))
    }

    @Test fun onlyPlanRunsSlashPlan() {
        assertTrue(ComposeMode.PLAN.plan)
        assertEquals(listOf(ComposeMode.PLAN), ComposeMode.entries.filter { it.plan })
        assertEquals("計畫模式", ComposeMode.PLAN.apply("計畫模式"))
    }
}
