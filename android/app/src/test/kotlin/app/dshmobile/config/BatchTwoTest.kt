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

    @Test fun modeSkillsShipWithShortDescriptionsAndTheDrawingSkillNamesOnlyPaletteColors() {
        for (name in listOf("dshm-contemplate", "dshm-research", "dshm-deliverable", "dshm-draw")) {
            val text = AppSkills.all.getValue(name)
            val description = text.lines().first { it.startsWith("description: ") }.removePrefix("description: ")
            assertTrue(name, description.length < 500)
        }
        for (mode in app.dshmobile.chat.ComposeMode.values()) mode.skill?.let { assertTrue(it, it in AppSkills.all) }
        val palette = setOf("#FEDFE1", "#DC9FB4", "#A5DEE4", "#D7C4BB", "#9E7A7A", "#5E4445", "#7D5F60", "#A98F90", "#FFFFFF")
        for (name in listOf("dshm-draw", "dshm-deliverable", "dshm-pages")) {
            val colors = Regex("#[0-9A-Fa-f]{6}").findAll(AppSkills.all.getValue(name)).map { it.value.uppercase() }.toSet()
            assertTrue("$name $colors", colors.isNotEmpty() && palette.containsAll(colors))
        }
        assertTrue(AppSkills.all.getValue("dshm-contemplate").contains("樂觀派"))
        assertTrue(AppSkills.all.getValue("dshm-research").contains("reports/"))
    }
}
