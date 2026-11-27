package app.dshmobile.agency

import android.content.SharedPreferences
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.ProviderChoice
import app.agency.core.migrate.AgencyPrefKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The provider choice and model ids, in prefs `agency_prefs` (arch 1.2: not in the database). The importer copied his coach
 * values here once (same keys, [AgencyPrefKeys]); only 設定 › 模型與金鑰 writes them afterwards. Reads are range-checked: a
 * value that is not one of the offered choices reads as the default.
 */
class AgencySettings(private val prefs: SharedPreferences) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun put(k: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
        _revision.value += 1
    }

    private fun putBool(k: String, v: Boolean) {
        prefs.edit().putBoolean(k, v).apply()
        _revision.value += 1
    }

    var provider: ProviderChoice
        get() = ProviderChoice.entries.firstOrNull { it.name == prefs.getString(AgencyPrefKeys.PROVIDER, null) } ?: ProviderChoice.DEEPSEEK
        set(v) = put(AgencyPrefKeys.PROVIDER, v.name)

    var deepseekModel: String
        get() = prefs.getString(AgencyPrefKeys.MODEL_DEEPSEEK, null)?.takeIf { id -> DEEPSEEK_MODELS.any { it.id == id } } ?: DEEPSEEK_MODELS[0].id
        set(v) = put(AgencyPrefKeys.MODEL_DEEPSEEK, v)

    var claudeModel: String
        get() = prefs.getString(AgencyPrefKeys.MODEL_CLAUDE, null)?.takeIf { id -> CLAUDE_MODELS.any { it.id == id } } ?: CLAUDE_MODELS[0].id
        set(v) = put(AgencyPrefKeys.MODEL_CLAUDE, v)

    /** His compatible service's address; https only (checked again by [CompatEndpoint.generic] before each call). */
    var compatUrl: String
        get() = prefs.getString(AgencyPrefKeys.COMPAT_URL, null).orEmpty()
        set(v) = put(AgencyPrefKeys.COMPAT_URL, v.trim().takeIf { it.isNotEmpty() })

    var compatModel: String
        get() = prefs.getString(AgencyPrefKeys.COMPAT_MODEL, null).orEmpty()
        set(v) = put(AgencyPrefKeys.COMPAT_MODEL, v.trim().takeIf { it.isNotEmpty() })

    /** 「這個服務支援結構化輸出」 (arch 1.10): off by default; then the format is only in the prompt. */
    var compatJson: Boolean
        get() = prefs.getBoolean(COMPAT_JSON, false)
        set(v) = putBool(COMPAT_JSON, v)

    /** 「先不要」 on the card's 藝名 and 代表色 setup (arch T10): it is not shown again; 設定 › 練習生資料 still sets both. */
    var idolSetupSkipped: Boolean
        get() = prefs.getBoolean(IDOL_SETUP_SKIPPED, false)
        set(v) = putBool(IDOL_SETUP_SKIPPED, v)

    /** 「這個服務支援工具」: off by default; then chat runs without tools on that service. */
    /** 新版本通知: the coach day of the last release check (at most once a day), and the newer release found (`name|page`). */
    var releaseCheckedDay: String?
        get() = prefs.getString(RELEASE_CHECKED_DAY, null)
        set(v) = put(RELEASE_CHECKED_DAY, v)

    var newRelease: String?
        get() = prefs.getString(NEW_RELEASE, null)
        set(v) = put(NEW_RELEASE, v)

    var compatTools: Boolean
        get() = prefs.getBoolean(COMPAT_TOOLS, false)
        set(v) = putBool(COMPAT_TOOLS, v)

    fun compatEndpoint(allowHttp: Boolean = false): CompatEndpoint? =
        CompatEndpoint.generic(compatUrl, compatModel, jsonMode = compatJson, tools = compatTools, allowHttp = allowHttp)

    /**
     * Ruling Q8: the compatible service has no price, so it is capped at [COMPAT_DAILY_CAP] requests a coach day. Consumes one
     * request when it returns true (the counter the coach used, carried over by the importer).
     */
    @Synchronized
    fun takeCompatRequest(today: String): Boolean {
        val count = if (prefs.getString(AgencyPrefKeys.COMPAT_DAY, null) == today) prefs.getInt(AgencyPrefKeys.COMPAT_COUNT, 0) else 0
        if (count >= COMPAT_DAILY_CAP) return false
        prefs.edit().putString(AgencyPrefKeys.COMPAT_DAY, today).putInt(AgencyPrefKeys.COMPAT_COUNT, count + 1).apply()
        return true
    }

    data class ModelChoice(val id: String, val zh: String)

    companion object {
        const val RELEASE_CHECKED_DAY = "release_checked_day"
        const val NEW_RELEASE = "new_release"
        const val COMPAT_DAILY_CAP = 300
        const val COMPAT_JSON = "compat_json"
        const val COMPAT_TOOLS = "compat_tools"
        const val IDOL_SETUP_SKIPPED = "idol_setup_skipped"

        /** The compatible service's key, in KeyVault's named slots (the coach's slot, kept). */
        const val COMPAT_KEY = "COACH_COMPAT"

        val DEEPSEEK_MODELS = listOf(
            ModelChoice(CompatEndpoint.DEEPSEEK_FLASH, "深度求索（快）"),
            ModelChoice(CompatEndpoint.DEEPSEEK_PRO, "深度求索（強）"),
        )
        val CLAUDE_MODELS = listOf(
            ModelChoice("claude-sonnet-5-5", "克勞德（平衡）"),
            ModelChoice("claude-haiku-5-5", "克勞德（最省）"),
            ModelChoice("claude-opus-5-5", "克勞德（最強）"),
        )
    }
}
