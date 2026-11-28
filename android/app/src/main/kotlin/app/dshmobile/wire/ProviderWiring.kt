package app.dshmobile.wire

import app.agency.core.agent.DeepSeekSearchPort
import app.agency.core.agent.Providers
import app.agency.core.agent.SearchPort
import app.agency.core.llm.AnthropicClient
import app.agency.core.llm.CallChecks
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.DeepSeekSearchClient
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Models
import app.agency.core.llm.OpenAiCompatClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Tier
import app.agency.core.llm.Turn
import app.agency.core.metering.CallBudget
import app.agency.core.metering.Provider
import app.agency.core.net.Transport
import app.dshmobile.data.ModelTier
import app.dshmobile.data.ProviderMode
import app.dshmobile.data.Settings
import app.dshmobile.security.KeyVault
import okhttp3.HttpUrl

/**
 * Builds the model clients of the company from the settings and the key vault. Everything goes through the one [transport] (the
 * network gate of `AppGraph`); the keys are read from [keys] at the moment of each call and are never held in a field.
 *
 * Routing, read at call time (a change in the settings applies to the next call):
 * - `ALL_CLAUDE`: chat = Claude at the chosen tier, `small` = Claude's small model;
 * - `CHAT_DEEPSEEK`: chat = DeepSeek at the chosen tier, `small` = DeepSeek's small model. A turn that carries photos goes to
 *   Claude when the DeepSeek chat model cannot see images (`Models.seesImages`) and a Claude key exists; with no Claude key it
 *   stays on DeepSeek, which then answers `NO_VISION` instead of dropping the photo.
 * Rules import and quiz writing use the chat client (`Company` does that).
 * Search exists only with a DeepSeek key (null otherwise: the tools then tell 小葵 that search is unavailable).
 */
class ProviderWiring(
    private val transport: Transport,
    private val keys: KeyVault,
    private val settings: Settings,
    private val meter: SpendMeter,
    private val claudeBase: () -> HttpUrl? = { null },
    private val deepseekBase: () -> HttpUrl? = { null },
    private val budget: CallBudget = CallBudget(clock = java.time.Clock.systemUTC()),
    /** Called each time the per-minute budget refuses a call (the app posts its quiet 「請求太頻繁」 notice). */
    onRateLimited: () -> Unit = {},
) {
    private val checks = CallChecks(meter, budget, onRateLimited)

    private fun mode(): ProviderMode = settings.get().providerMode

    private fun tier(): Tier = when (settings.get().tier) {
        ModelTier.SMART -> Tier.SMART
        ModelTier.BALANCED -> Tier.BALANCED
        ModelTier.SAVING -> Tier.CHEAP
    }

    private fun claudeKey(): String? = keys.get(Provider.ANTHROPIC)
    private fun deepseekKey(): String? = keys.get(Provider.DEEPSEEK)

    private fun dsChatModel(): String = Models.chat(ProviderChoice.DEEPSEEK, tier()) ?: CompatEndpoint.DEEPSEEK_FLASH

    private val claudeChat: LlmClient by lazy {
        AnthropicClient(transport, { Models.chat(ProviderChoice.CLAUDE, tier())!! }, ::claudeKey, checks, meter, claudeBase() ?: AnthropicClient.BASE)
    }
    private val claudeSmall: LlmClient by lazy {
        AnthropicClient(transport, { Models.small(ProviderChoice.CLAUDE)!! }, ::claudeKey, checks, meter, claudeBase() ?: AnthropicClient.BASE)
    }
    private val deepseekChat: LlmClient by lazy {
        OpenAiCompatClient(transport, { CompatEndpoint.deepseek(dsChatModel(), deepseekBase() ?: CompatEndpoint.DEEPSEEK_BASE) }, ::deepseekKey, checks, meter)
    }
    private val deepseekSmall: LlmClient by lazy {
        OpenAiCompatClient(
            transport,
            { CompatEndpoint.deepseek(Models.small(ProviderChoice.DEEPSEEK)!!, deepseekBase() ?: CompatEndpoint.DEEPSEEK_BASE) },
            ::deepseekKey, checks, meter,
        )
    }

    /** The chat client: the provider mode and tier of the settings, with photo turns sent to a model that can see them. */
    val chat: LlmClient = object : LlmClient {
        override suspend fun chat(request: ChatRequest): ChatResult = pick(request).chat(request)
    }

    val small: LlmClient = object : LlmClient {
        override suspend fun chat(request: ChatRequest): ChatResult =
            (if (effectiveMode() == ProviderMode.ALL_CLAUDE) claudeSmall else deepseekSmall).chat(request)
    }

    /** Which client answers [request] (public for tests). */
    internal fun pick(request: ChatRequest): LlmClient = when (effectiveMode()) {
        ProviderMode.ALL_CLAUDE -> claudeChat
        ProviderMode.CHAT_DEEPSEEK ->
            if (hasPhotos(request) && !Models.seesImages(ProviderChoice.DEEPSEEK, dsChatModel()) && keys.has(Provider.ANTHROPIC)) claudeChat
            else deepseekChat
    }

    /**
     * The mode that can actually answer: when the chosen provider has no key but the other one does, the other one is used
     * instead of failing every call with "no key" (he may have only one of the two keys, e.g. the one brought over from 0.9).
     */
    internal fun effectiveMode(): ProviderMode {
        val chosen = mode()
        val claude = keys.has(Provider.ANTHROPIC)
        val deepseek = keys.has(Provider.DEEPSEEK)
        return when {
            chosen == ProviderMode.ALL_CLAUDE && !claude && deepseek -> ProviderMode.CHAT_DEEPSEEK
            chosen == ProviderMode.CHAT_DEEPSEEK && !deepseek && claude -> ProviderMode.ALL_CLAUDE
            else -> chosen
        }
    }

    fun search(): SearchPort? =
        if (!keys.has(Provider.DEEPSEEK)) null
        else DeepSeekSearchPort(DeepSeekSearchClient(transport, ::deepseekKey, checks, meter, deepseekBase() ?: CompatEndpoint.DEEPSEEK_BASE))

    /**
     * The speech-to-text client of the lyric check. Its consent lock answers [consented]; with no DeepSeek key there is none.
     * Through the same network gate, spend cap and meter as every other call.
     */
    fun asr(consented: () -> Boolean): app.agency.core.singing.DeepSeekAsr? {
        if (!keys.has(Provider.DEEPSEEK)) return null
        val base = deepseekBase()
        return if (base == null) {
            app.agency.core.singing.DeepSeekAsr(transport, ::deepseekKey, checks, meter, consented)
        } else {
            app.agency.core.singing.DeepSeekAsr(
                transport, ::deepseekKey, checks, meter, consented,
                base.newBuilder().encodedPath("/v1/tools/asr").build(),
            )
        }
    }

    /** True when some key is saved (the app then has something to talk with; onboarding is over). */
    fun keyReady(): Boolean = keys.has(Provider.ANTHROPIC) || keys.has(Provider.DEEPSEEK)

    fun providers(): Providers = Providers(chat, small, search(), ::keyReady)

    private fun hasPhotos(r: ChatRequest): Boolean = r.turns.any { it is Turn.User && it.images.isNotEmpty() }
}
