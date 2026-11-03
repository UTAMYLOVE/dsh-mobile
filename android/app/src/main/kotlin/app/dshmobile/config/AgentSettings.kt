package app.dshmobile.config

import app.dshmobile.system.PhantomState

/**
 * What the user can tune about the agents. [maxConcurrent] is how many subagents may be alive at once; [teamSize] is the
 * size of an Agent Teams team and never exceeds [maxConcurrent] in the generated config. Subagents and teammates use the
 * lead's model: dsh 0.2.1 has no model key for them (spec 5.5's "cheap model for errands" is a known gap).
 */
data class AgentSettings(
    val maxConcurrent: Int,
    val maxDepth: Int = 2,
    val teamSize: Int = 8,
    val leadModel: String,
    val agentTeams: Boolean = true,
    /** dsh's experimental Auto review (stage 2 §J): every conversation switches to `/permission auto`; off by default. */
    val autoReview: Boolean = false,
    /** Stage 2 batch 2: pick Flash or Pro per message by how demanding it looks (only between DeepSeek models). */
    val autoRoute: Boolean = false,
    /** Remote MCP servers, one per line: `name https://…/mcp` (no headers: the app never hands dsh a secret). */
    val mcpServers: String = "",
) {
    init {
        require(maxConcurrent in MAX_CONCURRENT_RANGE) { "maxConcurrent $maxConcurrent not in $MAX_CONCURRENT_RANGE" }
        require(maxDepth in MAX_DEPTH_RANGE) { "maxDepth $maxDepth not in $MAX_DEPTH_RANGE" }
        require(teamSize in TEAM_SIZE_RANGE) { "teamSize $teamSize not in $TEAM_SIZE_RANGE" }
    }

    companion object {
        val MAX_CONCURRENT_RANGE = 1..32
        val MAX_DEPTH_RANGE = 0..3
        val TEAM_SIZE_RANGE = 1..16

        /** With the phantom-process limit off, many parallel subagents survive; with it on, a lower number avoids kills. */
        @Suppress("UNUSED_PARAMETER") // kept in the signature on purpose: the default model does not depend on it (see below)
        fun defaults(phantom: PhantomState, hasClaudeKey: Boolean): AgentSettings = AgentSettings(
            maxConcurrent = if (phantom == PhantomState.DISABLED) 16 else 6,
            // Stage 2 §I.2: with the limit off a team can be as large as the concurrency allows.
            teamSize = if (phantom == PhantomState.DISABLED) 16 else 8,
            // DeepSeek is the default whatever keys exist: a Claude key only makes the Claude models selectable.
            leadModel = AgentModels.DEFAULT_MODEL,
        )
    }
}

/** The models the lead can use, with the dsh provider id each one is served by. */
object AgentModels {
    const val DEEPSEEK_PROVIDER = "deepseek-official"
    const val ANTHROPIC_PROVIDER = "anthropic"
    const val DEFAULT_MODEL = "deepseek-flash"

    data class Model(val id: String, val provider: String) {
        val isClaude: Boolean get() = provider == ANTHROPIC_PROVIDER
    }

    private val deepseek = listOf(Model("deepseek-flash", DEEPSEEK_PROVIDER), Model("deepseek-v4-pro", DEEPSEEK_PROVIDER))
    private val claude = listOf(
        Model("claude-sonnet-5-5", ANTHROPIC_PROVIDER),
        Model("claude-opus-5-5", ANTHROPIC_PROVIDER),
        Model("claude-haiku-5-5", ANTHROPIC_PROVIDER),
    )

    /** DeepSeek models always; Claude models only when a Claude key is stored. */
    fun available(hasClaudeKey: Boolean): List<Model> = if (hasClaudeKey) deepseek + claude else deepseek

    fun find(id: String?): Model? = (deepseek + claude).firstOrNull { it.id == id }

    /** [id] when it is known and usable; otherwise [DEFAULT_MODEL] (unknown ids, or a Claude model without a Claude key). */
    fun resolve(id: String?, hasClaudeKey: Boolean): Model =
        available(hasClaudeKey).firstOrNull { it.id == id } ?: find(DEFAULT_MODEL)!!
}

/** The name shown for a model id; unknown ids are shown as they are. */
fun AgentModels.displayName(id: String?): String? = when (id) {
    null -> null
    "deepseek-flash" -> "DeepSeek Flash"
    "deepseek-v4-pro" -> "DeepSeek Pro"
    "claude-sonnet-5-5" -> "Claude Sonnet"
    "claude-opus-5-5" -> "Claude Opus"
    "claude-haiku-5-5" -> "Claude Haiku"
    else -> id
}
