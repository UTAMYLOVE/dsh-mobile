package app.dshmobile.config

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.system.PhantomState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentConfigWriterTest {
    private lateinit var home: File
    private lateinit var writer: AgentConfigWriter
    private val bundle = "@deepseek-ai/dsh-experimental-agent-team-profile"
    private val packageJson get() = File(home, "profiles/web/package.json")

    private val original = """
        |{
        |  "name": "dsh-profile-web",
        |  "private": true,
        |  "dependencies": {},
        |  "dsh": {
        |    "profile": {
        |      "bundles": [
        |        "@deepseek-ai/dsh-base",
        |        "@deepseek-ai/dsh-web-app"
        |      ]
        |    }
        |  }
        |}
        |""".trimMargin()

    @Before fun setUp() {
        home = Files.createTempDirectory("dshhome").toFile()
        writer = AgentConfigWriter(home)
    }

    @After fun tearDown() {
        home.deleteRecursively()
    }

    private fun profile(text: String = original) {
        packageJson.apply { parentFile!!.mkdirs() }.writeText(text)
    }

    private fun bundles(): List<String> =
        Json.parseToJsonElement(packageJson.readText()).jsonObject["dsh"]!!.jsonObject["profile"]!!.jsonObject["bundles"]!!
            .jsonArray.map { it.jsonPrimitive.content }

    private val s = AgentSettings(maxConcurrent = 6, maxDepth = 2, teamSize = 4, leadModel = "deepseek-v4-pro", agentTeams = true)

    // --- patchSection ---

    @Test fun leadModelSubagentAndTeamRows() {
        assertEquals(
            """
            |- id: agent-default-model
            |  config:
            |    provider: deepseek-official
            |    model: deepseek-v4-pro
            |- id: subagent
            |  config:
            |    maxActiveSubagents: 6
            |    maxDepth: 2
            |- id: agent-team
            |  config:
            |    maxMembers: 4
            |    maxTasks: 256
            |    maxMessageBytes: 65536
            |    disposalTimeoutMs: 5000
            """.trimMargin(),
            writer.patchSection(s, hasClaudeKey = false).trimEnd(),
        )
    }

    @Test fun teamSizeIsClampedToConcurrency() {
        val section = writer.patchSection(s.copy(maxConcurrent = 3, teamSize = 12), hasClaudeKey = false)
        assertTrue(section, section.contains("maxMembers: 3\n"))
        assertTrue(section, section.contains("maxActiveSubagents: 3\n"))
        assertFalse(section.contains("maxMembers: 12"))
        // not clamped when it already fits
        assertTrue(writer.patchSection(s.copy(maxConcurrent = 16, teamSize = 8), false).contains("maxMembers: 8\n"))
    }

    @Test fun noTeamRowWhenAgentTeamsIsOff() {
        val section = writer.patchSection(s.copy(agentTeams = false), hasClaudeKey = false)
        assertFalse(section.contains("agent-team"))
        assertTrue(section.contains("- id: subagent"))
    }

    @Test fun claudeModelUsesTheAnthropicProviderOnlyWithAKey() {
        val claude = s.copy(leadModel = "claude-opus-5-5")
        val with = writer.patchSection(claude, hasClaudeKey = true)
        assertTrue(with.contains("provider: anthropic\n    model: claude-opus-5-5\n"))
        val without = writer.patchSection(claude, hasClaudeKey = false)
        assertTrue(without.contains("provider: deepseek-official\n    model: deepseek-flash\n"))
        assertFalse(without.contains("claude", ignoreCase = true))
        assertFalse(without.contains("anthropic", ignoreCase = true))
    }

    @Test fun unknownStoredModelFallsBackToTheDefault() {
        val section = writer.patchSection(s.copy(leadModel = "evil: [model]\n- id: x"), hasClaudeKey = true)
        assertTrue(section.contains("model: deepseek-flash\n"))
        assertFalse(section.contains("evil"))
    }

    // --- agent teams bundle ---

    @Test fun agentTeamsBundleIsAddedWithoutTouchingOtherFields() {
        profile()
        assertTrue(writer.ensureAgentTeamsBundle(true))
        assertEquals(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", bundle), bundles())
        val root = Json.parseToJsonElement(packageJson.readText()).jsonObject
        assertEquals(listOf("name", "private", "dependencies", "dsh"), root.keys.toList())
        assertEquals("dsh-profile-web", root["name"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), root["dependencies"])
        // same layout as dsh's own file: two-space indent, trailing newline, nothing else changed
        assertEquals(original.replace("\"@deepseek-ai/dsh-web-app\"\n", "\"@deepseek-ai/dsh-web-app\",\n        \"$bundle\"\n"), packageJson.readText())
    }

    @Test fun addingTwiceChangesNothingTheSecondTime() {
        profile()
        assertTrue(writer.ensureAgentTeamsBundle(true))
        val once = packageJson.readText()
        assertTrue(writer.ensureAgentTeamsBundle(true))
        assertEquals(once, packageJson.readText())
        assertEquals(1, bundles().count { it == bundle })
    }

    @Test fun disablingRemovesOnlyThatEntry() {
        profile()
        writer.ensureAgentTeamsBundle(true)
        assertTrue(writer.ensureAgentTeamsBundle(false))
        assertEquals(original, packageJson.readText())
        assertTrue(writer.ensureAgentTeamsBundle(false)) // nothing left to remove
        assertEquals(original, packageJson.readText())
    }

    @Test fun otherFieldsKeepTheirValuesAndOrder() {
        profile("""{"b":[1,2],"dsh":{"x":true,"profile":{"y":"z","bundles":["a","b"]}},"a":{"n":null}}""")
        assertTrue(writer.ensureAgentTeamsBundle(true))
        val root = Json.parseToJsonElement(packageJson.readText()).jsonObject
        assertEquals(listOf("b", "dsh", "a"), root.keys.toList())
        assertEquals(listOf("x", "profile"), root["dsh"]!!.jsonObject.keys.toList())
        assertEquals(listOf("y", "bundles"), root["dsh"]!!.jsonObject["profile"]!!.jsonObject.keys.toList())
        assertEquals(listOf("a", "b", bundle), bundles())
    }

    @Test fun missingProfilePackageJsonIsNotCreated() {
        assertFalse(writer.ensureAgentTeamsBundle(true))
        assertFalse(packageJson.exists())
        assertFalse(File(home, "profiles").exists())
        assertFalse(writer.ensureAgentTeamsBundle(false))
        assertFalse(File(home, "profiles").exists())
    }

    @Test fun unusableProfileFilesAreLeftAlone() {
        for (text in listOf("not json", "[]", """{"dsh":{}}""", """{"dsh":{"profile":{"bundles":"x"}}}""", "")) {
            profile(text)
            assertFalse(text, writer.ensureAgentTeamsBundle(true))
            assertEquals(text, packageJson.readText())
        }
    }

    @Test fun noTemporaryFileIsLeftBehind() {
        profile()
        writer.ensureAgentTeamsBundle(true)
        assertEquals(listOf("package.json"), packageJson.parentFile!!.list()!!.toList())
    }

    // --- the patch dsh gets ---

    private class Vault(val deepseek: String? = "sk-real-deepseek-key", val claude: String? = null) : KeyVault {
        override fun get(p: Provider): String? = if (p == Provider.DEEPSEEK) deepseek else claude
        override fun put(p: Provider, key: String) = Unit
        override fun clear(p: Provider) = Unit
    }

    private val base = "http://127.0.0.1:41234/tok"
    private fun provider(vault: KeyVault, store: AgentSettingsStore = AgentSettingsStore(InMemorySharedPreferences()), phantom: PhantomState = PhantomState.ENABLED) =
        AgentPatchProvider(vault, store, writer, { phantom })

    @Test fun noClaudeKeyMeansNoAnthropicReferences() {
        profile()
        val store = AgentSettingsStore(InMemorySharedPreferences())
        store.save(AgentSettings(maxConcurrent = 6, leadModel = "claude-opus-5-5")) // chosen while a key existed
        val yaml = provider(Vault(claude = null), store)(base)
        assertFalse(yaml, yaml.contains("anthropic", ignoreCase = true))
        assertFalse(yaml, yaml.contains("claude", ignoreCase = true))
        assertTrue(yaml.contains("model: deepseek-flash"))
    }

    @Test fun withAClaudeKeyTheAnthropicRouteAndModelAreWritten() {
        profile()
        val store = AgentSettingsStore(InMemorySharedPreferences())
        store.save(AgentSettings(maxConcurrent = 6, leadModel = "claude-sonnet-5-5"))
        val yaml = provider(Vault(claude = "sk-ant-real-claude-key"), store)(base)
        assertTrue(yaml.contains("apiKeyEnv: ANTHROPIC_API_KEY"))
        assertTrue(yaml.contains("provider: anthropic\n    model: claude-sonnet-5-5"))
    }

    @Test fun configNeverContainsRealKey() {
        profile()
        val vault = Vault(deepseek = "sk-real", claude = "sk-real-claude")
        val yaml = provider(vault)(base)
        assertFalse(yaml.contains("sk-real"))
        assertTrue(yaml.contains("apiKeyEnv: DEEPSEEK_API_KEY"))
        assertTrue(yaml.contains("apiKeyEnv: ANTHROPIC_API_KEY"))
        assertFalse(yaml.contains("apiKey:"))
    }

    @Test fun baseUrlsPointAtGateway() {
        profile()
        val yaml = provider(Vault(claude = "sk-ant-real-claude-key"))(base)
        assertTrue(yaml.contains("baseURL: \"$base/deepseek\""))
        assertTrue(yaml.contains("baseURL: \"$base/deepseek/v1\""))
        assertTrue(yaml.contains("baseURL: \"$base/anthropic\""))
        val urls = Regex("baseURL: \"([^\"]*)\"").findAll(yaml).map { it.groupValues[1] }.toList()
        assertEquals(3, urls.size)
        assertTrue(urls.all { it.startsWith("http://127.0.0.1:41234/tok/") })
    }

    @Test fun settingsReachThePatchAndPhantomPicksTheDefaultConcurrency() {
        profile()
        assertTrue(provider(Vault(), phantom = PhantomState.DISABLED)(base).contains("maxActiveSubagents: 4\n"))
        assertTrue(provider(Vault(), phantom = PhantomState.ENABLED)(base).contains("maxActiveSubagents: 3\n"))
    }

    @Test fun launchAddsTheBundleAndTheTeamRowOnlyOnceTheBundleCanLoad() {
        // First start: dsh has not created its profile yet, so a row for a bundle that is not there would only make dsh
        // warn "entry not found": no team row, no file.
        val store = AgentSettingsStore(InMemorySharedPreferences())
        store.save(AgentSettings.defaults(PhantomState.ENABLED, false).copy(agentTeams = true)) // off by default since 0.6.0
        val first = provider(Vault(), store)(base)
        assertFalse(first.contains("agent-team"))
        assertFalse(packageJson.exists())
        // Next start: the file exists, the bundle is added and the row follows.
        profile()
        val second = provider(Vault(), store)(base)
        assertTrue(second.contains("- id: agent-team"))
        assertTrue(bundles().contains(bundle))
    }

    @Test fun launchWithAgentTeamsOffRemovesTheBundleAndWritesNoRow() {
        profile()
        writer.ensureAgentTeamsBundle(true)
        val store = AgentSettingsStore(InMemorySharedPreferences())
        store.save(AgentSettings(maxConcurrent = 6, leadModel = "deepseek-flash", agentTeams = false))
        val yaml = provider(Vault(), store)(base)
        assertFalse(yaml.contains("agent-team"))
        assertFalse(bundles().contains(bundle))
    }

    @Test fun autoReviewBundleIsAddedAndRemovedAlongsideTheTeamBundle() {
        profile()
        val auto = AgentConfigWriter.AUTO_REVIEW_BUNDLE
        assertTrue(writer.ensureAgentTeamsBundle(true))
        assertTrue(writer.ensureAutoReviewBundle(true))
        assertEquals(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", bundle, auto), bundles())
        assertTrue(writer.ensureAutoReviewBundle(false))
        assertEquals(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", bundle), bundles())
    }
}
