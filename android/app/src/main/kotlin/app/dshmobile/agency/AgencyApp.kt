package app.dshmobile.agency

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.agency.core.brain.Brain
import app.agency.core.llm.AnthropicClient
import app.agency.core.llm.CallChecks
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.OpenAiCompatClient
import app.agency.core.llm.ProviderRouter
import app.agency.core.metering.CallBudget
import app.agency.core.metering.Provider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.ImportKind
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.Transport
import app.agency.core.rules.CoachRules
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.agency.metering.CostMeter
import app.dshmobile.agency.migrate.ImportReport
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.agency.secure.TransientDataException
import app.dshmobile.log.ErrorLog
import app.dshmobile.security.KeyVault
import java.io.File
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl

/**
 * Seams the instrumentation runner and Robolectric tests set before the company first opens (always null in production).
 * [modelBase]: every model request goes to this base (a fake model on 127.0.0.1) and the network gate allows only its origin.
 */
class TestOverrides(
    val modelBase: HttpUrl? = null,
    val openDatabase: ((Context) -> AgencyDatabase)? = null,
    val mediaVault: ((Context) -> MediaVault)? = null,
    /** Wraps the store the importer and the brain use (a test makes it fail on purpose). */
    val store: ((AgencyStore) -> AgencyStore)? = null,
)

/** Where the company stands at start. [Ready] carries what the screens use. */
sealed interface StartState {
    /** Opening the encrypted database. */
    data object Opening : StartState

    /** The first import of his coach records is running (「正在搬資料」). */
    data object Importing : StartState

    data class Ready(val store: AgencyStore, val brain: Brain) : StartState

    /** The database does not open with its key, or cannot be opened at all (「資料鎖打不開」); nothing was deleted. */
    data object Locked : StartState

    /** The database could not be opened for a passing reason (disk full, busy): he can try again. Nothing was deleted. */
    data object Unavailable : StartState
}

/**
 * A line on the home screen after the import. [PARTIAL] and [MEDIA_PENDING] are shown once (kept in prefs until he taps
 * 知道了, so a process that dies first does not lose them); [FAILED] comes back at each start that fails again.
 */
enum class ImportNotice { PARTIAL, MEDIA_PENDING, FAILED }

/** What 設定 › 舊資料 shows, read from the outcome row `import-legacy` only (never from the failed-attempt row). */
data class LegacyStatus(
    val kind: Kind,
    val counts: Map<ImportKind, Int> = emptyMap(),
    val note: ImportNote? = null,
    /** The folder the legacy files are in now: `coach` while partial, `coach.migrated-<ts>` after the import. */
    val folder: File? = null,
) {
    enum class Kind { NOTHING, DONE, PARTIAL, NOT_YET }
}

/**
 * The agency's singletons (arch 1.1 `AgencyApp`): the encrypted store, the media vault, the importer, the provider clients
 * behind the network gate, the brain and the notifier stub. Held by [app.dshmobile.DshMobileApp]; nothing runs until [start].
 *
 * [start] opens the database and runs the first import, never on the main thread. Every failure is caught and nothing is ever
 * deleted: a lost key or an unopenable file is [StartState.Locked], a passing failure [StartState.Unavailable] (try again), and
 * an import that fails, is partial or throws still ends in [StartState.Ready] (the import tries again at the next start; a
 * partial one when its files change or he taps 「再試一次」). 「正在搬資料」 shows only while a first import runs, never while
 * `agency_prefs.migrated == 2`.
 */
class AgencyApp(
    private val context: Context,
    val keyVault: KeyVault,
    val costMeter: CostMeter,
    private val errorLog: ErrorLog,
    private val overrides: TestOverrides? = testOverrides,
    private val clock: AgencyClock = AgencyClock.system(),
) {
    private val agencyPrefs = context.getSharedPreferences(AgencyPrefKeys.NAME, Context.MODE_PRIVATE)
    private val importPrefs = context.getSharedPreferences(AgencyPrefKeys.IMPORT_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LegacyPrefs.NAME, Context.MODE_PRIVATE)
    val settings = AgencySettings(agencyPrefs)
    val notifier = AgencyNotifier(context)
    /** Anything thrown outside the start's own catches is logged and ends on a try-again screen, never a crash at start. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            errorLog.append(TAG, "start threw (${e.javaClass.simpleName})")
            if (_start.value !is StartState.Ready) _start.value = StartState.Unavailable
        },
    )

    /** His coach-day start hour (profile), for the compatible service's daily cap; read at open and after each message. */
    @Volatile private var dayStartHour = DEFAULT_DAY_START

    private val _start = MutableStateFlow<StartState>(StartState.Opening)
    val start: StateFlow<StartState> = _start.asStateFlow()

    private val _notice = MutableStateFlow<ImportNotice?>(null)
    val notice: StateFlow<ImportNotice?> = _notice.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var db: AgencyDatabase? = null
    @Volatile private var importer: LegacyImporter? = null

    /**
     * Opens the company once, and again after [StartState.Unavailable] or [StartState.Locked] (再試一次: opening deletes
     * nothing, and a Keystore that was only busy may answer now). A call while it opens or once it is open does nothing.
     */
    fun start() {
        synchronized(this) {
            if (job?.isActive == true) return
            if (_start.value is StartState.Ready) return
            job = scope.launch { open() }
        }
    }

    private suspend fun open() {
        _start.value = StartState.Opening
        runCatching { MediaVault.sweepCameraCache(MediaVault.cameraDir(context)) }
        // Copies he shared from 設定 › 舊資料 are plaintext: they live only until the next start.
        runCatching { File(context.cacheDir, EXPORT_DIR).listFiles()?.forEach { it.deleteRecursively() } }
        storedNotice()?.let { _notice.value = it }
        val opened = try {
            overrides?.openDatabase?.invoke(context) ?: AgencyDatabase.open(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientDataException) {
            errorLog.append(TAG, "database not available now (${e.javaClass.simpleName})")
            _start.value = StartState.Unavailable
            return
        } catch (e: Throwable) {
            // DataLockedException, Room's IllegalStateException, a Keystore runtime error, UnsatisfiedLinkError: never a crash
            // loop and never a wipe.
            errorLog.append(TAG, "database locked (${e.javaClass.simpleName})")
            _start.value = StartState.Locked
            return
        }
        db = opened
        val store = RoomAgencyStore(opened).let { overrides?.store?.invoke(it) ?: it }
        val vault = overrides?.mediaVault?.invoke(context) ?: MediaVault.forApp(context)
        val imp = LegacyImporter(context.filesDir, legacyPrefs, agencyPrefs, importPrefs, store, vault)
        importer = imp
        if (agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == 0 && legacyPresent()) _start.value = StartState.Importing
        importAndSeal(imp)
        refreshDayStart(store)
        _start.value = StartState.Ready(store, brain(store))
    }

    /** The import (first run, a changed partial, or the retry of a failed one), then any pending media; sets the notice. */
    private suspend fun importAndSeal(imp: LegacyImporter) {
        val report = try {
            imp.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "import threw (${e.javaClass.simpleName})")
            null
        }
        logImport(report)
        when {
            report == null -> _notice.value = ImportNotice.FAILED
            report.partialNotice -> setNotice(ImportNotice.PARTIAL)
            !report.ok && !report.skipped && !report.partial -> _notice.value = ImportNotice.FAILED
            report.ok && !report.skipped && report.note?.mediaPending.orEmpty().isNotEmpty() -> setNotice(ImportNotice.MEDIA_PENDING)
            report.ok && _notice.value == ImportNotice.FAILED -> _notice.value = null
        }
        val sealed = try {
            imp.sealPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "pending media threw (${e.javaClass.simpleName})")
            0
        }
        if (sealed > 0) {
            errorLog.append(TAG, "pending media sealed=$sealed")
            // Everything is in now: the line about pending photos is no longer true.
            if (_notice.value == ImportNotice.MEDIA_PENDING && runCatching { imp.pendingMedia() }.getOrNull()?.isEmpty() == true) dismissNotice()
        }
    }

    private suspend fun refreshDayStart(store: AgencyStore) {
        dayStartHour = runCatching { store.profile.get()?.dayStartHour }.getOrNull() ?: DEFAULT_DAY_START
    }

    /** The coach day the compatible service's daily cap counts in (his day start, not a fixed hour). */
    internal fun quotaDay(): String = CoachRules.coachDay(clock.now(), clock.zone, dayStartHour).toString()

    private fun storedNotice(): ImportNotice? =
        agencyPrefs.getString(NOTICE_KEY, null)?.let { n -> ImportNotice.entries.firstOrNull { it.name == n } }

    private fun setNotice(n: ImportNotice) {
        agencyPrefs.edit().putString(NOTICE_KEY, n.name).commit()
        _notice.value = n
    }

    private fun legacyPresent(): Boolean = File(context.filesDir, LegacyImporter.LEGACY_DIR).isDirectory || legacyPrefs.all.isNotEmpty()

    /** Counts and flags only, never content. */
    private fun logImport(r: ImportReport?) {
        if (r == null || r.skipped) return
        errorLog.append(TAG, "import ok=${r.ok} partial=${r.partial} inserted=${r.inserted.values.sum()} planned=${r.counts.values.sum()}")
    }

    fun dismissNotice() {
        agencyPrefs.edit().remove(NOTICE_KEY).commit()
        _notice.value = null
    }

    /**
     * 設定 › 舊資料 「再試一次」 after a failed first import (NOT_YET): runs the import now instead of at the next process
     * start, and the pending media after it.
     */
    suspend fun retryFirstImport() {
        val imp = importer ?: return
        importAndSeal(imp)
    }

    /** Sends his message to the brain, off the main thread; a failure is logged, never thrown at the screen. */
    fun send(text: String) {
        val ready = _start.value as? StartState.Ready ?: return
        scope.launch {
            try {
                ready.brain.onText(text)
                refreshDayStart(ready.store)
                if (costMeter.consumeAlert()) notifier.costAlert(costMeter.todayUsd())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog.append(TAG, "message not answered (${e.javaClass.simpleName})")
            }
        }
    }

    /** 設定 › 舊資料: the import's state, from the `import-legacy` row. */
    suspend fun legacyStatus(): LegacyStatus {
        val store = (_start.value as? StartState.Ready)?.store ?: return LegacyStatus(LegacyStatus.Kind.NOT_YET)
        val row = store.journal.migrations().firstOrNull { it.id == LegacyImporter.LOG_ID }
        if (row == null) {
            val done = agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == AgencyPrefKeys.MIGRATED_DONE
            return LegacyStatus(if (done) LegacyStatus.Kind.NOTHING else LegacyStatus.Kind.NOT_YET)
        }
        val counts = runCatching {
            Json.parseToJsonElement(row.countsJson).jsonObject.mapNotNull { (k, v) ->
                ImportKind.entries.firstOrNull { it.name == k }?.let { it to v.jsonPrimitive.int }
            }.toMap()
        }.getOrDefault(emptyMap())
        val note = row.note?.let { runCatching { ImportNote.fromJson(it) }.getOrNull() }
        val folder = if (row.ok) {
            context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith(LegacyImporter.LEGACY_DIR + ".migrated-") }?.maxByOrNull { it.name }
        } else {
            File(context.filesDir, LegacyImporter.LEGACY_DIR).takeIf { it.isDirectory }
        }
        return LegacyStatus(if (row.ok) LegacyStatus.Kind.DONE else LegacyStatus.Kind.PARTIAL, counts, note, folder)
    }

    /** 設定 › 舊資料 「再試一次」 after a partial import. */
    suspend fun retryImport(): ImportReport? {
        val imp = importer ?: return null
        return try {
            imp.retry().also { logImport(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "retry threw (${e.javaClass.simpleName})")
            null
        }
    }

    /** The provider clients behind the one network gate, and the brain on top. */
    private fun brain(store: AgencyStore): Brain {
        val base = overrides?.modelBase
        val gate = NetworkGate(
            allowed = {
                if (base != null) {
                    setOf(Origin(base.scheme, base.host, base.port))
                } else {
                    runCatching { NetworkGate.allowlist(settings.compatUrl) }.getOrElse { NetworkGate.allowlist(null) }
                }
            },
            onBlocked = { host -> errorLog.append("net", "blocked host $host") },
        )
        val transport = Transport(HttpFactory.create(gate))
        val checks = CallChecks(costMeter, CallBudget(clock = Clock.systemUTC()))
        val deepseek = OpenAiCompatClient(
            transport,
            { if (base != null) CompatEndpoint.deepseek(settings.deepseekModel, base) else CompatEndpoint.deepseek(settings.deepseekModel) },
            { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter,
        )
        val claude = AnthropicClient(transport, { settings.claudeModel }, { keyVault.get(Provider.ANTHROPIC) }, checks, costMeter, base ?: AnthropicClient.BASE)
        val compat = OpenAiCompatClient(
            transport, { settings.compatEndpoint(allowHttp = base != null) }, { keyVault.getNamed(AgencySettings.COMPAT_KEY) }, checks, costMeter,
            takeQuota = { settings.takeCompatRequest(quotaDay()) },
        )
        // The data clerk's call goes to the cheapest model of the provider he chose (never another provider): it only sorts
        // his sentence into a kind (controller ruling on the T06 review M3). The compatible service has one model.
        val deepseekClerk = OpenAiCompatClient(
            transport,
            { if (base != null) CompatEndpoint.deepseek(CompatEndpoint.DEEPSEEK_FLASH, base) else CompatEndpoint.deepseek(CompatEndpoint.DEEPSEEK_FLASH) },
            { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter,
        )
        val claudeClerk = AnthropicClient(transport, { CLAUDE_CLERK }, { keyVault.get(Provider.ANTHROPIC) }, checks, costMeter, base ?: AnthropicClient.BASE)
        return Brain(
            store, ProviderRouter({ settings.provider }, deepseek, claude, compat), { settings.provider }, clock,
            intakeLlm = ProviderRouter({ settings.provider }, deepseekClerk, claudeClerk, compat),
        )
    }

    /** Test only: closes the database and forgets the start, so the next [start] opens again (the e2e suite shares a process). */
    @VisibleForTesting
    suspend fun stopForTest() {
        job?.cancelAndJoin()
        db?.close()
        db = null
        importer = null
        _notice.value = null
        _start.value = StartState.Opening
    }

    companion object {
        private const val TAG = "agency"
        private const val NOTICE_KEY = "notice_pending"
        private const val EXPORT_DIR = "export"
        private const val DEFAULT_DAY_START = 5

        /** The cheapest Claude model, for the data clerk only. */
        const val CLAUDE_CLERK = "claude-haiku-5-5"

        /** Set by the instrumentation runner (and tests) before the company first opens. */
        @VisibleForTesting
        @Volatile
        var testOverrides: TestOverrides? = null
    }
}
