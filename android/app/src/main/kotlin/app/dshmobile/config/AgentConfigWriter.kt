package app.dshmobile.config

import app.dshmobile.gateway.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.system.PhantomState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Turns [AgentSettings] into dsh configuration: the agent rows of the `--patch` YAML ([patchSection]) and the Agent Teams
 * bundle entry in the web profile's `package.json` ([ensureAgentTeamsBundle]). [dshHome] is the host side of `$DSH_HOME`
 * (`AppPaths.dshHome`, bound to the guest's `/root/.dsh`). Row `config` values replace the whole row, so every row lists all the keys it needs.
 */
class AgentConfigWriter(private val dshHome: File) {
    private val packageJson get() = File(dshHome, "profiles/web/package.json")

    /**
     * `agent-default-model` (lead), `subagent` (concurrency, depth) and, when Agent Teams is on, `agent-team`. The team is never
     * bigger than the concurrency (teammates count against `maxActiveSubagents`). A Claude model without a Claude key is written as
     * the default DeepSeek model, so a patch without a Claude key never names anthropic.
     */
    fun patchSection(s: AgentSettings, hasClaudeKey: Boolean): String {
        val model = AgentModels.resolve(s.leadModel, hasClaudeKey)
        return buildString {
            append("- id: agent-default-model\n  config:\n    provider: ${model.provider}\n    model: ${model.id}\n")
            append("- id: subagent\n  config:\n    maxActiveSubagents: ${s.maxConcurrent}\n    maxDepth: ${s.maxDepth}\n")
            if (s.agentTeams) {
                append("- id: agent-team\n  config:\n    maxMembers: ${minOf(s.teamSize, s.maxConcurrent)}\n")
                append("    maxTasks: 256\n    maxMessageBytes: 65536\n    disposalTimeoutMs: 5000\n")
            }
        }
    }

    /**
     * Adds ([enabled]) or removes the Agent Teams bundle in `profiles/web/package.json` and returns true when the file now says
     * what was asked. The file is dsh's own: it is never created here (dsh makes it on its first start; false means "takes effect
     * after the next start"), and a file of an unexpected shape is left untouched (false). Only the one array entry changes;
     * every other field keeps its value and position. Written through a temporary file and an atomic rename.
     */
    @Synchronized
    fun ensureAgentTeamsBundle(enabled: Boolean): Boolean = ensureBundle(TEAM_BUNDLE, enabled)

    /** dsh's experimental Auto review layer (stage 2 §J), added to or removed from the web profile like [ensureAgentTeamsBundle]. */
    @Synchronized
    fun ensureAutoReviewBundle(enabled: Boolean): Boolean = ensureBundle(AUTO_REVIEW_BUNDLE, enabled)

    private fun ensureBundle(name: String, enabled: Boolean): Boolean {
        fun isIt(e: kotlinx.serialization.json.JsonElement) = e is JsonPrimitive && e.isString && e.content == name
        val file = packageJson
        if (!file.isFile) return false
        return try {
            val root = JSON.parseToJsonElement(file.readText()) as? JsonObject ?: return false
            val dsh = root["dsh"] as? JsonObject ?: return false
            val profile = dsh["profile"] as? JsonObject ?: return false
            val bundles = profile["bundles"] as? JsonArray ?: return false
            val present = bundles.any(::isIt)
            if (present == enabled) return true
            val changed = if (enabled) JsonArray(bundles + JsonPrimitive(name)) else JsonArray(bundles.filterNot(::isIt))
            write(file, root.with("dsh", dsh.with("profile", profile.with("bundles", changed))))
            true
        } catch (_: IOException) {
            false
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun write(file: File, root: JsonObject) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(JSON.encodeToString(JsonObject.serializer(), root) + "\n")
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** A copy with [key] set; an existing key keeps its position. */
    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        JsonObject(LinkedHashMap(this).also { it[key] = value })

    companion object {
        const val TEAM_BUNDLE = "@deepseek-ai/dsh-experimental-agent-team-profile"
        const val AUTO_REVIEW_BUNDLE = "@deepseek-ai/dsh-experimental-auto-review"
        private val JSON = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}

/**
 * The `patchProvider` of [app.dshmobile.service.DshController]: reads the keys and the saved settings at every start, makes the
 * bundle entry match the Agent Teams switch and renders the whole patch. Keys are only asked about (is there one?), never copied
 * into anything.
 */
class AgentPatchProvider(
    private val vault: KeyVault,
    private val store: AgentSettingsStore,
    private val writer: AgentConfigWriter,
    private val phantom: () -> PhantomState,
    private val pluginDir: String = DshPatch.DEFAULT_PLUGIN_DIR,
) : (String) -> String {
    override fun invoke(gatewayBase: String): String {
        val hasClaudeKey = vault.get(Provider.ANTHROPIC) != null // asked once: the section and the provider row must agree
        val settings = store.load(phantom(), hasClaudeKey)
        // dsh warns "entry not found" for an agent-team row whose bundle is not loaded: write the row only when the bundle is in place.
        val teamsActive = writer.ensureAgentTeamsBundle(settings.agentTeams)
        writer.ensureAutoReviewBundle(settings.autoReview)
        val section = writer.patchSection(settings.copy(agentTeams = settings.agentTeams && teamsActive), hasClaudeKey)
        return DshPatch.render(gatewayBase, pluginDir, section, includeAnthropic = hasClaudeKey)
    }
}
