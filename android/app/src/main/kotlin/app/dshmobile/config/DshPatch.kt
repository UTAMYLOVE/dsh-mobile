package app.dshmobile.config

/**
 * Generates the dsh `--patch` overlay (YAML list of Cordis rows). Each section is its own function so later
 * tasks can add sections (agent settings) to the same document. Row `config` values replace the whole row,
 * so every section writes all keys its row needs.
 */
object DshPatch {
    const val DEFAULT_PLUGIN_DIR = "/opt/dshm/plugins"

    fun render(gatewayBase: String, pluginDir: String = DEFAULT_PLUGIN_DIR): String =
        sections(gatewayBase, pluginDir).joinToString("\n", postfix = "\n") { it.trimEnd() }

    /** The sections in output order; [render] joins them. */
    internal fun sections(gatewayBase: String, pluginDir: String): List<String> =
        listOf(providers(gatewayBase), privacy(), directoryPicker(), plugins(pluginDir))

    /** Model providers talk to the local gateway with the fake key; only env var names appear here. */
    internal fun providers(gatewayBase: String): String = """
        |- id: llm-deepseek
        |  config:
        |    apiKeyEnv: DEEPSEEK_API_KEY
        |    baseURL: ${q("$gatewayBase/deepseek")}
        |- id: web-search-deepseek
        |  config:
        |    apiKeyEnv: DEEPSEEK_API_KEY
        |    baseURL: ${q("$gatewayBase/deepseek/v1")}
        |- id: llm-pi-ai
        |  config:
        |    providers:
        |      anthropic:
        |        apiKeyEnv: ANTHROPIC_API_KEY
        |        baseURL: ${q("$gatewayBase/anthropic")}
    """.trimMargin()

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

    /** There is no desktop on the phone: use the in-page browsing folder picker. */
    internal fun directoryPicker(): String = """
        |- id: directory-picker
        |  disabled: true
        |- insert:
        |    - id: directory-picker-browse
        |      name: ${q("@deepseek-ai/dsh-host-directory-picker-browse")}
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
