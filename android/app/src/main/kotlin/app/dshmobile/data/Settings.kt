package app.dshmobile.data

import app.agency.core.store.Doc
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Which model answers what: everything by Claude, or chat by DeepSeek (cheaper) and the rest by Claude. */
@Serializable
enum class ProviderMode { ALL_CLAUDE, CHAT_DEEPSEEK }

/** 聰明 / 均衡 / 省錢. */
@Serializable
enum class ModelTier { SMART, BALANCED, SAVING }

/** A newer release found by the daily check: its name (`1.0.1`) and the page to open. */
@Serializable
data class NewVersion(val versionName: String, val page: String)

/**
 * Small settings that are not rules and not secrets (API keys are in the key vault, never here). One JSON document, `settings`
 * ([Doc.SETTINGS]), so the encrypted backup carries them. New fields need a default (an older document still reads).
 */
@Serializable
data class AppSettings(
    val providerMode: ProviderMode = ProviderMode.ALL_CLAUDE,
    val tier: ModelTier = ModelTier.BALANCED,
    /** Panel key → folded. A panel that is not listed is folded (everything folds away until he opens it). */
    val folded: Map<String, Boolean> = emptyMap(),
    /** The monthly spend cap in US dollars (what the model providers bill in); null = no cap. */
    val monthlyCapUsd: Double? = null,
    /** A newer version waiting to be installed, or null. */
    val newVersion: NewVersion? = null,
    /** The logical day (ISO date) the release check last ran, so it runs at most once a day. */
    val updateCheckedDay: String? = null,
) {
    fun isFolded(panel: String): Boolean = folded[panel] ?: true
}

/**
 * Reads and writes [AppSettings] through the [Store]'s `settings` document. The value is cached; [state] lets a screen follow
 * changes. Call off the main thread the first time (it reads the database).
 */
class Settings(private val store: Store) {
    private val lock = Any()
    private val flow: MutableStateFlow<AppSettings> by lazy { MutableStateFlow(store.readDoc<AppSettings>(Doc.SETTINGS) ?: AppSettings()) }

    val state: StateFlow<AppSettings> get() = flow.asStateFlow()

    fun get(): AppSettings = flow.value

    /** Applies [change] to the current settings, stores the result and returns it. */
    fun update(change: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val next = change(flow.value)
        if (next != flow.value) {
            store.writeDoc(Doc.SETTINGS, next)
            flow.value = next
        }
        next
    }

    /** Drops the cached value so the next read comes from the store (after a backup was restored). */
    fun reload() = synchronized(lock) {
        flow.value = store.readDoc<AppSettings>(Doc.SETTINGS) ?: AppSettings()
    }

    fun setFolded(panel: String, folded: Boolean) = update { it.copy(folded = it.folded + (panel to folded)) }
}
