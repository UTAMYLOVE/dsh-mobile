package app.dshmobile.config

import app.dshmobile.runtime.AppPaths
import app.dshmobile.runtime.GUARDED_PRESET
import app.dshmobile.runtime.PermissionLevel

/**
 * Generates the dsh `--patch` overlay (YAML list of Cordis rows). Each section is its own function so later
 * tasks can add sections (agent settings) to the same document. Row `config` values replace the whole row,
 * so every section writes all keys its row needs.
 */
object DshPatch {
    const val DEFAULT_PLUGIN_DIR = "/opt/dshm/plugins"
    private val ANTHROPIC_ROUTE = Regex("""(?im)^\s*provider:\s*"?anthropic"?\s*$""")

    /**
     * [agentSection] (see `AgentConfigWriter.patchSection`) is placed after the providers. With [includeAnthropic] false the
     * whole document must not mention anthropic (a user without a Claude key must never see or reach that route), so an
     * [agentSection] that does is refused.
     */
    fun render(
        gatewayBase: String,
        pluginDir: String = DEFAULT_PLUGIN_DIR,
        agentSection: String = "",
        includeAnthropic: Boolean = false,
        workspace: String = AppPaths.GUEST_WORKSPACE,
        shellPath: String? = null,
        permission: PermissionLevel = PermissionLevel.SMART,
    ): String {
        // Only a row that routes to the anthropic provider is refused: an MCP server whose name or URL mentions anthropic is not one
        // (it made every start fail for a DeepSeek-only user).
        require(includeAnthropic || !ANTHROPIC_ROUTE.containsMatchIn(agentSection)) { "agent section routes to anthropic but there is no Claude key" }
        return sections(gatewayBase, pluginDir, agentSection, includeAnthropic, workspace, shellPath, permission)
            .filter { it.isNotBlank() }
            .joinToString("\n", postfix = "\n") { it.trimEnd() }
    }

    /** The sections in output order; [render] joins them. */
    internal fun sections(
        gatewayBase: String, pluginDir: String, agentSection: String = "", includeAnthropic: Boolean = false,
        workspace: String = AppPaths.GUEST_WORKSPACE, shellPath: String? = null, permission: PermissionLevel = PermissionLevel.SMART,
    ): List<String> =
        listOf(
            providers(gatewayBase, includeAnthropic), agentSection, privacy(), directoryPicker(), workspaceController(workspace),
            shellPath?.let { terminalShell(it) }.orEmpty(), permission(permission), plugins(pluginDir),
        )

    /**
     * The permission presets and the one new conversations start with. dsh's own "workspace-write" needs a sandbox backend
     * (bubblewrap or Landlock) that an Android app cannot have: every command was refused until the model retried it with an
     * escalation the owner approved, and subagents (approval policy pinned to "never") could run none. [GUARDED_PRESET] is full
     * access with approvals on, so the app's guard plugin (dshm-guard) can ask before the risky kinds; "danger-full-access"
     * never asks. dsh refuses a default that matches no preset, hence the explicit table. The row's config replaces dsh's.
     */
    internal fun permission(level: PermissionLevel): String = """
        |- id: permission
        |  config:
        |    presets:
        |      $GUARDED_PRESET:
        |        sandbox: danger-full-access
        |        approval: ask
        |        name: ${q("DSH Mobile")}
        |        description: ${q("No dsh sandbox exists on Android; the app's guard asks before risky actions.")}
        |      danger-full-access:
        |        sandbox: danger-full-access
        |        approval: never
        |        name: danger-full-access
        |        description: ${q("Full file access without approval prompts.")}
        |    defaultPreset: ${level.preset}
    """.trimMargin()

    /** Native mode: Android has no /bin/bash, so dsh's shell is the bundled bash (see `NativeRunner`). */
    internal fun terminalShell(shellPath: String): String = """
        |- id: terminal-bash
        |  config:
        |    shellPath: ${q(shellPath)}
    """.trimMargin()

    /**
     * Model providers talk to the local gateway with the fake key; only env var names appear here. The pi-ai (Anthropic) row
     * exists only with [includeAnthropic], i.e. when a Claude key is stored.
     */
    internal fun providers(gatewayBase: String, includeAnthropic: Boolean = false): String {
        val deepseek = """
            |- id: llm-deepseek
            |  config:
            |    apiKeyEnv: DEEPSEEK_API_KEY
            |    baseURL: ${q("$gatewayBase/deepseek")}
            |- id: web-search-deepseek
            |  config:
            |    apiKeyEnv: DEEPSEEK_API_KEY
            |    baseURL: ${q("$gatewayBase/deepseek/v1")}
        """.trimMargin()
        if (!includeAnthropic) return deepseek
        val anthropic = """
            |- id: llm-pi-ai
            |  config:
            |    providers:
            |      anthropic:
            |        apiKeyEnv: ANTHROPIC_API_KEY
            |        baseURL: ${q("$gatewayBase/anthropic")}
            |        reasoning: high
        """.trimMargin()
        return deepseek + "\n" + anthropic
    }

    /** Telemetry off, no session-log upload, no plugin inventory upload. */
    internal fun privacy(): String = """
        |- id: session-telemetry-otel
        |  config:
        |    mode: DISABLED
        |- id: session-log-deepseek
        |  config:
        |    enabled: false
        |- id: plugin-package-inventory-deepseek
        |  config:
        |    enabled: false
    """.trimMargin()

    /**
     * There is no desktop on the phone: use the in-page browsing folder picker, both halves of it. The host row serves
     * `directoryPicker/list|createDirectory`; the client row renders the folder dialog in the page. With the host row alone
     * (v0.1.0) dsh's "choose workspace" did nothing and "add workspace" was missing.
     */
    internal fun directoryPicker(): String = """
        |- id: directory-picker
        |  disabled: true
        |- insert:
        |    - id: directory-picker-browse
        |      name: ${q("@deepseek-ai/dsh-host-directory-picker-browse")}
        |    - id: directory-picker-browse-ui
        |      name: ${q("@deepseek-ai/dsh-client-ui-directory-picker-browse")}
    """.trimMargin()

    /**
     * dsh looks up the user's Documents folder with `xdg-user-dir` on Linux; the rootfs has none, so its first-use default
     * workspace failed (`spawn xdg-user-dir ENOENT`). Pointed at the app's workspace, a default (if dsh ever makes one, i.e. when
     * the app's own registration of /root/workspace failed) lands in user data, under `deepseek-harness/default-workspace`.
     */
    internal fun workspaceController(workspace: String = AppPaths.GUEST_WORKSPACE): String = """
        |- id: workspace-controller
        |  config:
        |    documentsDirectory: ${q(workspace)}
        |    documentsLookupTimeoutMs: 10000
    """.trimMargin()

    internal fun plugins(pluginDir: String): String {
        val dir = pluginDir.trimEnd('/')
        return """
            |- insert:
            |    - id: dshm-memory
            |      name: ${q("$dir/memory/index.js")}
            |    - id: dshm-notify
            |      name: ${q("$dir/notify/index.js")}
            |    - id: dshm-ui
            |      name: ${q("$dir/ui/index.js")}
            |    - id: dshm-guard
            |      name: ${q("$dir/guard/index.js")}
        """.trimMargin()
    }

    /** YAML double-quoted scalar. */
    internal fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c == '\u007f' || c == '\u0085' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
