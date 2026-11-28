package app.dshmobile.wire.ui

import android.content.Context
import app.agency.core.agent.CompanyAdmin
import app.agency.core.llm.CallChecks
import app.agency.core.metering.Provider
import app.agency.core.rules.EditResult
import app.agency.core.rules.RulesEdit
import app.agency.core.store.Doc
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.dshmobile.BuildConfig
import app.dshmobile.R
import app.dshmobile.data.ModelTier
import app.dshmobile.data.ProviderMode
import app.dshmobile.data.Settings
import app.dshmobile.platform.Notifier
import app.dshmobile.security.KeyVault
import app.dshmobile.ui.LicenseView
import app.dshmobile.ui.ModelChoice
import app.dshmobile.ui.RuleField
import app.dshmobile.ui.RuleGroup
import app.dshmobile.ui.SettingsUi
import app.dshmobile.ui.SpendView
import app.dshmobile.ui.UpdateView
import app.dshmobile.wire.SpendMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything on the settings pages. 公司規章 shows the generic fields of `RulesEdit`; a save applies every changed field in turn
 * and writes the rules only when all of them were accepted (the page asks twice before it calls). Money texts are in New Taiwan
 * dollars at the fixed rate of [SpendMeter.YUAN_PER_USD]; the monthly cap is enforced by the spend meter.
 */
class SettingsAdapter(
    private val context: Context,
    private val admin: CompanyAdmin,
    private val store: Store,
    private val settings: Settings,
    private val keys: KeyVault,
    private val meter: SpendMeter,
    private val notifier: Notifier,
) : SettingsUi {

    override fun rulesGroups(): List<RuleGroup> =
        admin.ruleGroups().map { g -> RuleGroup(g.label, g.fields.map { RuleField(it.key, it.label, it.value, it.hint) }) }

    override fun saveRules(changes: Map<String, String>): String? {
        var rules = admin.rules()
        for ((key, text) in changes) {
            when (val r = RulesEdit.set(rules, key, text)) {
                is EditResult.Ok -> rules = r.rules
                is EditResult.Bad -> return r.message
            }
        }
        val handbook = store.readDoc<String>(Doc.HANDBOOK).orEmpty()
        val original = store.readDoc<String>(Doc.RULES_TEXT).orEmpty()
        admin.saveRules(rules, handbook, original)
        return null
    }

    override fun model(): ModelChoice {
        val s = settings.get()
        return ModelChoice(
            useDeepSeekForChat = s.providerMode == ProviderMode.CHAT_DEEPSEEK,
            tier = when (s.tier) {
                ModelTier.SMART -> "smart"
                ModelTier.BALANCED -> "balanced"
                ModelTier.SAVING -> "cheap"
            },
            claudeKeySet = keys.has(Provider.ANTHROPIC),
            deepSeekKeySet = keys.has(Provider.DEEPSEEK),
        )
    }

    override fun setModel(useDeepSeekForChat: Boolean, tier: String) {
        settings.update {
            it.copy(
                providerMode = if (useDeepSeekForChat) ProviderMode.CHAT_DEEPSEEK else ProviderMode.ALL_CLAUDE,
                tier = when (tier) {
                    "smart" -> ModelTier.SMART
                    "cheap" -> ModelTier.SAVING
                    else -> ModelTier.BALANCED
                },
            )
        }
    }

    override suspend fun saveKey(which: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val p = if (which == "deepseek") Provider.DEEPSEEK else Provider.ANTHROPIC
        val clean = CallChecks.usableKey(key) ?: return@withContext false
        keys.set(p, clean)
        keys.has(p)
    }

    override fun spend(): SpendView = SpendView(
        today = yuanText(meter.todayUsd()),
        month = yuanText(meter.monthUsd()),
        capYuan = meter.capUsd()?.let { SpendMeter.yuan(it) },
    )

    private fun yuanText(usd: Double): String =
        if (usd > 0 && SpendMeter.yuan(usd) < 1) context.getString(R.string.wire_under_one_yuan) else context.getString(R.string.wire_yuan, SpendMeter.yuan(usd))

    override fun setCap(yuan: Int?) {
        settings.update { it.copy(monthlyCapUsd = yuan?.takeIf { y -> y > 0 }?.let(SpendMeter::usd)) }
    }

    override fun memory(): List<String> = admin.memoryLines()

    override fun forget(line: String) {
        admin.forget(line)
    }

    override fun testNotification() {
        notifier.post("test", context.getString(R.string.wire_test_title), context.getString(R.string.wire_test_text), loud = true)
    }

    override fun openBatteryGuide() = SystemPages.batteryGuide(context)

    override fun update(): UpdateView = UpdateView(BuildConfig.VERSION_NAME, settings.get().newVersion?.versionName)

    override fun openUpdate() {
        val page = settings.get().newVersion?.page ?: return
        if (Notifier.releasePage(page)) SystemPages.view(context, page)
    }

    override fun licenses(): List<LicenseView> {
        val names = mapOf(
            "lxgw-wenkai-OFL.txt" to R.string.wire_license_font,
            "sqlcipher-BSD.txt" to R.string.wire_license_db,
            "apache-2.0.txt" to R.string.wire_license_libs,
        )
        return runCatching { context.assets.list("licenses").orEmpty().sorted() }.getOrDefault(emptyList()).map { file ->
            val text = runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
            LicenseView(names[file]?.let(context::getString) ?: file.substringBeforeLast('.'), text)
        }
    }
}
