package app.dshmobile.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPatchTest {
    private val base = "http://127.0.0.1:41234/tok"
    private val yaml = DshPatch.render(base)

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

    @Test fun containsFullPrivacyPatch() {
        assertTrue(yaml.contains("- id: session-telemetry-otel\n  config:\n    mode: DISABLED"))
        assertTrue(yaml.contains("- id: session-log-deepseek\n  config:\n    enabled: false"))
        assertTrue(yaml.contains("- id: plugin-package-inventory-deepseek\n  config:\n    enabled: false"))
    }

    @Test fun swapsInTheBrowsingDirectoryPicker() {
        assertTrue(yaml.contains("- id: directory-picker\n  disabled: true"))
        assertTrue(yaml.contains("name: \"@deepseek-ai/dsh-host-directory-picker-browse\""))
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
        val custom = DshPatch.render(base, pluginDir = "/srv/p")
        assertTrue(custom.contains("name: \"/srv/p/memory/index.js\""))
        assertTrue(custom.contains("name: \"/srv/p/notify/index.js\""))
        assertTrue(custom.contains("name: \"/srv/p/ui/index.js\""))
    }

    @Test fun neverContainsAnApiKey() {
        assertFalse(yaml.contains("sk-"))
        assertFalse(yaml.contains("apiKey:"))
    }

    @Test fun sectionsAppearInTheDocumentedOrder() {
        val order = listOf("llm-deepseek", "session-telemetry-otel", "directory-picker", "dshm-memory").map { yaml.indexOf("id: $it") }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test fun stringsAreEscaped() {
        val tricky = DshPatch.render("http://h/\"x\\y\nz")
        assertTrue(tricky.contains("baseURL: \"http://h/\\\"x\\\\y\\nz/deepseek\""))
        assertFalse(tricky.contains("y\nz"))
    }
}
