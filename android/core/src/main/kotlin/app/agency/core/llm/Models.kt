package app.agency.core.llm

/** The model tier he picks in settings (聰明／均衡／省錢). */
enum class Tier { SMART, BALANCED, CHEAP }

/**
 * Which model id each tier means per provider, and the small model the judge and the summary use. Only ids already verified
 * in the clients ([AnthropicClient.MODELS], [CompatEndpoint.DEEPSEEK_MODELS]); a generic compatible service has the one model
 * he typed, so it has no tiers here (null).
 *
 * DeepSeek has two models: the larger one for [Tier.SMART]; the flash model for the others (it is also the one that takes
 * photos, see [CompatEndpoint.vision]).
 */
object Models {
    private const val OPUS = "claude-opus-5-5"
    private const val SONNET = "claude-sonnet-5-5"
    private const val HAIKU = "claude-haiku-5-5"

    fun chat(provider: ProviderChoice, tier: Tier): String? = when (provider) {
        ProviderChoice.CLAUDE -> when (tier) {
            Tier.SMART -> OPUS
            Tier.BALANCED -> SONNET
            Tier.CHEAP -> HAIKU
        }
        ProviderChoice.DEEPSEEK -> when (tier) {
            Tier.SMART -> CompatEndpoint.DEEPSEEK_PRO
            Tier.BALANCED, Tier.CHEAP -> CompatEndpoint.DEEPSEEK_FLASH
        }
        ProviderChoice.COMPAT -> null
    }

    /** The small, cheap model for second checks and summaries. */
    fun small(provider: ProviderChoice): String? = when (provider) {
        ProviderChoice.CLAUDE -> HAIKU
        ProviderChoice.DEEPSEEK -> CompatEndpoint.DEEPSEEK_FLASH
        ProviderChoice.COMPAT -> null
    }

    /** True when [model] of [provider] takes photos in the chat request. */
    fun seesImages(provider: ProviderChoice, model: String): Boolean = when (provider) {
        ProviderChoice.CLAUDE -> model in AnthropicClient.MODELS
        ProviderChoice.DEEPSEEK -> model == CompatEndpoint.DEEPSEEK_FLASH
        ProviderChoice.COMPAT -> false
    }
}
