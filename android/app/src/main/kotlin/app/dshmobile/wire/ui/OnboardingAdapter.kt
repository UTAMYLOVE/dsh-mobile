package app.dshmobile.wire.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import app.agency.core.agent.CompanyAdmin
import app.agency.core.agent.CompanyApi
import app.agency.core.llm.CallChecks
import app.agency.core.metering.Provider
import app.agency.core.rules.ImportResult
import app.agency.core.rules.RulesCheck
import app.agency.core.rules.RulesText
import app.agency.core.stage.Feature
import app.dshmobile.R
import app.dshmobile.platform.Notifier
import app.dshmobile.platform.alarm.AlarmScheduler
import app.dshmobile.security.KeyVault
import app.dshmobile.ui.ImportOutcome
import app.dshmobile.ui.OnboardingUi
import app.dshmobile.ui.PermissionState
import app.dshmobile.ui.SummaryGroup
import app.dshmobile.wire.ActivityHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system pages a few screens send him to. All start from the application context, so they work from any thread. */
object SystemPages {
    /** The battery-saving page of the system (so the alarm is not held back); falls back to this app's settings page. */
    fun batteryGuide(context: Context) {
        val app = context.applicationContext
        val first = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(first) }.onFailure {
            runCatching {
                app.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Opens [url] in the browser (only http and https). */
    fun view(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        runCatching { context.applicationContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }
}

/**
 * First start: keys into the Keystore-wrapped vault, his document read by the company ([CompanyAdmin.importRules]), the
 * confirmation text from `RulesText`, the stage name and colour, the permission questions and day 1. What the company read is kept
 * here between the steps ([readDocument] → [confirm]); nothing is saved before he confirms. Plain functions may be called from the
 * main thread (the page calls them from a tap), so [finish] only reads and writes the small database.
 */
class OnboardingAdapter(
    private val context: Context,
    private val api: CompanyApi,
    private val admin: CompanyAdmin,
    private val keys: KeyVault,
    private val notifier: Notifier,
    private val alarms: AlarmScheduler,
    private val host: ActivityHost,
    private val onChanged: () -> Unit,
) : OnboardingUi {
    private class Pending(val result: ImportResult, val text: String)

    @Volatile private var pending: Pending? = null

    override fun keysSaved(): Boolean = keys.has(Provider.ANTHROPIC)

    override suspend fun saveKeys(claude: String, deepseek: String): Boolean = withContext(Dispatchers.IO) {
        if (claude.isBlank() && keys.has(Provider.ANTHROPIC)) {
            // The key an older version saved stays; only a new DeepSeek key is taken.
            CallChecks.usableKey(deepseek)?.let { keys.set(Provider.DEEPSEEK, it) }
            return@withContext true
        }
        val c = CallChecks.usableKey(claude) ?: return@withContext false
        val d = deepseek.takeIf { it.isNotBlank() }?.let { CallChecks.usableKey(it) ?: return@withContext false }
        keys.set(Provider.ANTHROPIC, c)
        if (d != null) keys.set(Provider.DEEPSEEK, d) else keys.clear(Provider.DEEPSEEK)
        keys.has(Provider.ANTHROPIC)
    }

    override suspend fun readDocument(text: String): ImportOutcome = withContext(Dispatchers.IO) {
        val r = admin.importRules(text)
        if (!r.ok) {
            pending = null
            return@withContext ImportOutcome(emptyList(), emptyList(), r.problems.firstOrNull() ?: context.getString(R.string.wire_import_failed))
        }
        pending = Pending(r, text)
        val groups = RulesText.groups(r.rules).map { (title, lines) -> SummaryGroup(title, lines) }.toMutableList()
        if (r.problems.isNotEmpty()) groups += SummaryGroup(context.getString(R.string.wire_import_problems), r.problems)
        val missing = Feature.entries.filter { it.stage > 0 }.flatMap { RulesCheck.missing(r.rules, it) }.distinct()
        ImportOutcome(groups, missing, failure = null)
    }

    override suspend fun confirm(stageName: String, memberColorHex: String, callName: String) {
        withContext(Dispatchers.IO) {
            val p = pending
            val base = p?.result?.rules ?: admin.rules()
            val rules = base.copy(stageName = stageName.trim(), memberColor = memberColorHex.trim().uppercase(), callName = callName.trim())
            admin.saveRules(rules, p?.result?.handbook.orEmpty(), p?.text.orEmpty())
            pending = null
        }
    }

    override fun permissions(): PermissionState = PermissionState(notifications = notifier.enabled(), exactAlarms = alarms.exactAllowed())

    override fun requestNotifications() {
        SystemPages.onMain {
            val activity = (context.applicationContext as? app.dshmobile.DshMobileApp)?.screens?.current() ?: return@onMain
            activity.requestPermissions(arrayOf(Notifier.PERMISSION), REQUEST_NOTIFICATIONS)
        }
    }

    override fun openAlarmSettings() {
        runCatching {
            context.applicationContext.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun openBatteryGuide() = SystemPages.batteryGuide(context)

    /** Day 1 starts: the first rollover joins him to the stages; the wake handler then arms the first alarm. */
    override fun finish() {
        api.rollover()
        onChanged()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 4711
    }
}
