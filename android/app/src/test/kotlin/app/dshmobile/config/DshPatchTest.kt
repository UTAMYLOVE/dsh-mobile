package app.dshmobile.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPatchTest {
    private val base = "http://127.0.0.1:41234/tok"
    private val yaml = DshPatch.render(base, includeAnthropic = true)

    @Test fun providerRowsPointAtTheGateway() {
        assertTrue(yaml.contains("- id: llm-deepseek"))
        assertTrue(yaml.contains("baseURL: \"$base/deepseek\""))
        assertTrue(yaml.contains("- id: web-search-deepseek"))
        assertTrue(yaml.contains("baseURL: \"$base/deepseek/v1\""))
        assertTrue(yaml.contains("- id: llm-pi-ai"))
        assertTrue(yaml.contains("baseURL: \"$base/anthropic\""))
        assertEquals(2, Regex("apiKeyEnv: DEEPSEEK_API_KEY").findAll(yaml).count())
        assertEquals(1, Regex("apiKeyEnv: ANTHROPIC_API_KEY").findAll(yaml).count())
        assertEquals(3, Regex("baseURL:").findAll(yaml).count())
    }

    @Test fun anthropicRowIsOmittedWithoutAClaudeKey() {
        val plain = DshPatch.render(base, includeAnthropic = false)
        assertFalse(plain, plain.contains("anthropic", ignoreCase = true))
        assertFalse(plain.contains("llm-pi-ai"))
        assertFalse(plain.contains("ANTHROPIC_API_KEY"))
        assertTrue(plain.contains("- id: llm-deepseek"))
        assertEquals(2, Regex("baseURL:").findAll(plain).count())
        assertEquals(plain, DshPatch.render(base)) // the default is "no Claude"
    }

    @Test fun agentSectionIsPlacedAfterTheProviders() {
        val section = "- id: agent-default-model\n  config:\n    provider: deepseek-official\n    model: deepseek-flash\n"
        val withAgent = DshPatch.render(base, agentSection = section, includeAnthropic = true)
        assertTrue(withAgent.contains(section.trimEnd()))
        val order = listOf("llm-deepseek", "agent-default-model", "session-telemetry-otel", "dshm-memory").map { withAgent.indexOf("id: $it") }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertFalse(withAgent.contains("\n\n"))
    }

    @Test fun anAgentSectionThatNamesAnthropicIsRefusedWithoutAClaudeKey() {
        try {
            DshPatch.render(base, agentSection = "- id: agent-default-model\n  config:\n    provider: anthropic\n", includeAnthropic = false)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        DshPatch.render(base, agentSection = "- id: agent-default-model\n  config:\n    provider: anthropic\n", includeAnthropic = true)
    }

    @Test fun containsFullPrivacyPatch() {
        assertTrue(yaml.contains("- id: session-telemetry-otel\n  config:\n    mode: DISABLED"))
        assertTrue(yaml.contains("- id: session-log-deepseek\n  config:\n    enabled: false"))
        assertTrue(yaml.contains("- id: plugin-package-inventory-deepseek\n  config:\n    enabled: false"))
    }

    /**
     * Follow-up 1: both halves of the browse picker. With only the host row (what the first release shipped) dsh's "choose
     * workspace" did nothing and "add workspace" was missing; the browser half renders the in-page folder dialog.
     */
    @Test fun swapsInBothHalvesOfTheBrowsingDirectoryPicker() {
        assertTrue(yaml.contains("- id: directory-picker\n  disabled: true"))
        assertTrue(
            yaml.contains(
                "- insert:\n" +
                    "    - id: directory-picker-browse\n      name: \"@deepseek-ai/dsh-host-directory-picker-browse\"\n" +
                    "    - id: directory-picker-browse-ui\n      name: \"@deepseek-ai/dsh-client-ui-directory-picker-browse\"\n",
            ),
        )
    }

    /** Follow-up 1: no `xdg-user-dir` in the rootfs, so dsh's Documents lookup is pointed at the app's workspace. */
    @Test fun dshsDocumentsDirectoryIsTheAppWorkspace() {
        assertTrue(
            yaml.contains(
                "- id: workspace-controller\n  config:\n    documentsDirectory: \"/root/workspace\"\n    documentsLookupTimeoutMs: 10000\n",
            ),
        )
    }

    @Test fun insertsAllThreePluginsByAbsolutePath() {
        assertTrue(yaml.contains("- id: dshm-memory"))
        assertTrue(yaml.contains("name: \"/opt/dshm/plugins/memory/index.js\""))
        assertTrue(yaml.contains("- id: dshm-notify"))
        assertTrue(yaml.contains("name: \"/opt/dshm/plugins/notify/index.js\""))
        assertTrue(yaml.contains("- id: dshm-ui"))
        assertTrue(yaml.contains("name: \"/opt/dshm/plugins/ui/index.js\""))
        assertEquals(3, Regex("- id: dshm-").findAll(yaml).count())
    }

    @Test fun pluginDirectoryIsConfigurable() {
        val custom = DshPatch.render(base, pluginDir = "/srv/p", includeAnthropic = true)
        assertTrue(custom.contains("name: \"/srv/p/memory/index.js\""))
        assertTrue(custom.contains("name: \"/srv/p/notify/index.js\""))
        assertTrue(custom.contains("name: \"/srv/p/ui/index.js\""))
    }

    @Test fun neverContainsAnApiKey() {
        assertFalse(yaml.contains("sk-"))
        assertFalse(yaml.contains("apiKey:"))
    }

    @Test fun sectionsAppearInTheDocumentedOrder() {
        val order = listOf("llm-deepseek", "session-telemetry-otel", "directory-picker", "workspace-controller", "dshm-memory").map { yaml.indexOf("id: $it") }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test fun stringsAreEscaped() {
        val tricky = DshPatch.render("http://h/\"x\\y\nz")
        assertTrue(tricky.contains("baseURL: \"http://h/\\\"x\\\\y\\nz/deepseek\""))
        assertFalse(tricky.contains("y\nz"))
    }
}
