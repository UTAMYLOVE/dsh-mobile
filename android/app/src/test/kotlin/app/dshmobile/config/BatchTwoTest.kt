package app.dshmobile.config

import app.dshmobile.remote.PromptPart
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTwoTest {
    @Test fun skillsAreInstalledAndOnlyTheAppsOwnAreCleanedUp() {
        val home = Files.createTempDirectory("dsh").toFile()
        File(home, "skills/dshm-old/SKILL.md").apply { parentFile.mkdirs(); writeText("old") }
        File(home, "skills/mine/SKILL.md").apply { parentFile.mkdirs(); writeText("owner's") }
        AppSkills.install(home)
        val novel = File(home, "skills/dshm-novel/SKILL.md").readText()
        assertTrue(novel.startsWith("---\nname: dshm-novel\ndescription: "))
        assertTrue(File(home, "skills/dshm-bookkeeping/SKILL.md").isFile)
        assertFalse(File(home, "skills/dshm-old").exists())
        assertEquals("owner's", File(home, "skills/mine/SKILL.md").readText())
    }

    @Test fun routerSendsShortChatToFlashAndHeavyWorkToPro() {
        assertEquals(ModelRouter.FLASH, ModelRouter.pick(listOf(PromptPart.Text("今天天氣如何"))))
        assertEquals(ModelRouter.PRO, ModelRouter.pick(listOf(PromptPart.Text("幫我分析這兩支股票"))))
        assertEquals(ModelRouter.PRO, ModelRouter.pick(listOf(PromptPart.Text("字".repeat(300)))))
        assertEquals(ModelRouter.PRO, ModelRouter.pick(listOf(PromptPart.Text("看圖"), PromptPart.Image("image/png", "", null))))
    }

    @Test fun mcpServersBecomeInsertRowsAndBadLinesAreDropped() {
        val text = "github https://example.com/mcp\nbad name https://x\nlocal http://127.0.0.1:3000/mcp\nplain http://evil.example/mcp"
        assertEquals(listOf("github", "local"), McpServers.parse(text).map { it.first })
        assertEquals(2, McpServers.invalid(text).size)
        val s = AgentSettings(maxConcurrent = 6, leadModel = "deepseek-flash", mcpServers = "github https://example.com/mcp")
        val section = AgentConfigWriter(Files.createTempDirectory("h").toFile()).patchSection(s, false)
        assertTrue(section, section.contains("- id: dshm-mcp-github\n      name: \"@deepseek-ai/dsh-mcp-client\""))
        assertTrue(section, section.contains("url: \"https://example.com/mcp\""))
    }
}
