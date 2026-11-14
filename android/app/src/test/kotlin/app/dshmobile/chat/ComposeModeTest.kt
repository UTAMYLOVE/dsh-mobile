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

    /** 小說模式 (2026-11-15) loads the novel skill, which keeps the book's memory in files (setting, characters, outline, progress). */
    @Test fun novelModeLoadsTheNovelSkill() {
        assertEquals("請用 dshm-novel 技能：寫一本關於霧城的小說", ComposeMode.NOVEL.apply("寫一本關於霧城的小說"))
        assertEquals("寫一本關於霧城的小說", ComposeMode.strip(ComposeMode.NOVEL.apply("寫一本關於霧城的小說")))
        val skill = app.dshmobile.config.AppSkills.all.getValue("dshm-novel")
        for (file in listOf("設定.md", "角色.md", "大綱.md", "進度.md", "章節/第N章.md")) assertTrue(file, skill.contains(file))
    }

    @Test fun onlyPlanRunsSlashPlan() {
        assertTrue(ComposeMode.PLAN.plan)
        assertEquals(listOf(ComposeMode.PLAN), ComposeMode.entries.filter { it.plan })
        assertEquals("計畫模式", ComposeMode.PLAN.apply("計畫模式"))
    }
}
