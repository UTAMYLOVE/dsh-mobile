package app.agency.core.llm

/** The provider he picked in settings. */
enum class ProviderChoice { DEEPSEEK, CLAUDE, COMPAT }

/**
 * Sends each call to the provider he picked, read at call time. There is no failover to another provider: each one is another
 * recipient of his data, so a failure comes back as it is and the caller answers with a template (the war room shows 「連不上模型」).
 */
class ProviderRouter(
    private val choice: () -> ProviderChoice,
    private val deepseek: LlmClient,
    private val claude: LlmClient,
    private val compat: LlmClient,
) : LlmClient {
    override suspend fun chat(request: ChatRequest): ChatResult = when (choice()) {
        ProviderChoice.DEEPSEEK -> deepseek
        ProviderChoice.CLAUDE -> claude
        ProviderChoice.COMPAT -> compat
    }.chat(request)
}
