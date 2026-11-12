package app.dshmobile.config

import app.dshmobile.system.PhantomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentSettingsTest {
    @Test fun defaultsDependOnPhantomState() {
        assertEquals(4, AgentSettings.defaults(PhantomState.DISABLED, hasClaudeKey = false).maxConcurrent)
        assertEquals(3, AgentSettings.defaults(PhantomState.ENABLED, hasClaudeKey = false).maxConcurrent)
        assertEquals(3, AgentSettings.defaults(PhantomState.UNKNOWN, hasClaudeKey = false).maxConcurrent)
    }

    @Test fun defaultsUseDeepSeekWhateverTheClaudeKey() {
        for (claude in listOf(false, true)) {
            val d = AgentSettings.defaults(PhantomState.UNKNOWN, claude)
            assertEquals("deepseek-flash", d.leadModel)
            assertEquals(2, d.maxDepth)
            assertEquals(3, d.teamSize)
            assertFalse(d.agentTeams)
        }
    }

    @Test fun rangesAreChecked() {
        fun bad(block: () -> Unit) = try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
        val ok = AgentSettings(maxConcurrent = 6, leadModel = "deepseek-flash")
        AgentSettings(maxConcurrent = 1, maxDepth = 0, teamSize = 1, leadModel = "deepseek-flash")
        AgentSettings(maxConcurrent = 32, maxDepth = 3, teamSize = 16, leadModel = "deepseek-flash")
        bad { ok.copy(maxConcurrent = 0) }
        bad { ok.copy(maxConcurrent = 33) }
        bad { ok.copy(maxDepth = -1) }
        bad { ok.copy(maxDepth = 4) }
        bad { ok.copy(teamSize = 0) }
        bad { ok.copy(teamSize = 17) }
    }

    @Test fun modelListWithoutClaudeKeyIsDeepSeekOnly() {
        val ids = AgentModels.available(hasClaudeKey = false).map { it.id }
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), ids)
        assertTrue(AgentModels.available(false).all { it.provider == "deepseek-official" })
    }

    @Test fun modelListWithClaudeKeyAddsClaudeModels() {
        val models = AgentModels.available(hasClaudeKey = true)
        assertEquals(
            listOf("deepseek-flash", "deepseek-v4-pro", "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1"),
            models.map { it.id },
        )
        assertTrue(models.filter { it.id.startsWith("claude-") }.all { it.provider == "anthropic" })
    }

    @Test fun claudeModelFallsBackToDeepSeekWithoutKey() {
        assertEquals("deepseek-flash", AgentModels.resolve("claude-opus-5-5", hasClaudeKey = false).id)
        assertEquals("claude-opus-5-5", AgentModels.resolve("claude-opus-5-5", hasClaudeKey = true).id)
        assertEquals("deepseek-v4-pro", AgentModels.resolve("deepseek-v4-pro", hasClaudeKey = false).id)
        assertEquals("deepseek-flash", AgentModels.resolve("no-such-model", hasClaudeKey = true).id)
        assertEquals("deepseek-flash", AgentModels.resolve(null, hasClaudeKey = true).id)
        assertNull(AgentModels.find("no-such-model"))
        assertFalse(AgentModels.find("deepseek-flash")!!.isClaude)
    }

    @Test fun displayNamesForEveryKnownModel() {
        assertEquals("DeepSeek Flash", AgentModels.displayName("deepseek-flash"))
        assertEquals("DeepSeek Pro", AgentModels.displayName("deepseek-v4-pro"))
        assertEquals("Claude Sonnet", AgentModels.displayName("claude-sonnet-5-5"))
        assertEquals("Claude Opus", AgentModels.displayName("claude-opus-5-5"))
        assertEquals("Claude Haiku", AgentModels.displayName("claude-haiku-5-5"))
        assertEquals("mystery-1", AgentModels.displayName("mystery-1"))
        assertNull(AgentModels.displayName(null))
    }
}
