package app.dshmobile

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.agency.core.agent.CompanyApi
import app.agency.core.base.Clock
import app.agency.core.base.SystemClock
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.ReleaseCheck
import app.agency.core.net.Transport
import app.agency.core.store.Store
import app.dshmobile.data.Db
import app.dshmobile.data.Settings
import app.dshmobile.data.SqlStore
import app.dshmobile.log.ErrorLog
import app.dshmobile.platform.Attachments
import app.dshmobile.platform.Backup
import app.dshmobile.platform.Notifier
import app.dshmobile.platform.OldData
import app.dshmobile.platform.UpdateCheck
import app.dshmobile.platform.UpdateWork
import app.dshmobile.platform.alarm.AlarmScheduler
import app.dshmobile.platform.alarm.WakeHandler
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.SystemCamera
import app.dshmobile.security.KeyVault
import app.dshmobile.security.WrappedKeyVault
import okhttp3.HttpUrl

/**
 * Manual wiring, built once by [DshMobileApp] and found through `(context.applicationContext as DshMobileApp).graph`. Every
 * member is lazy: the encrypted database opens on the first use of [db], [store] or [settings], never at process start, and
 * the constructor's providers let a test swap the database, the key vault and the media vault.
 *
 * The one network exit is [gate] + [transport]: allowlist `api.deepseek.com`, `api.anthropic.com`, `api.github.com` only.
 * Nothing else builds an HTTP client.
 *
 * Extension points for the other layers (set once, early, from `DshMobileApp.onCreate` or a test):
 * [company] (the brain behind the screens), [wake] (what an alarm does), [clock] (replaceable time), [claudeBase] /
 * [deepseekBase] (instrumentation tests point the providers at a local fake server).
 */
class AppGraph(
    val context: Context,
    private val openDb: () -> SupportSQLiteDatabase = { Db.open(context) },
    private val keyVaultProvider: () -> KeyVault = { WrappedKeyVault.forApp(context) },
    private val mediaVaultProvider: () -> MediaVault = { MediaVault.forApp(context) },
) {
    // ---- extension points ----------------------------------------------------------------------------------------------

    /** The brain behind the screens (`CompanyApi`); null until the wiring layer registers it. Use [requireCompany] in screens. */
    @Volatile var company: CompanyApi? = null

    /** What the alarm, a reboot and a clock change do (see `Wake`); null until registered. */
    @Volatile var wake: WakeHandler? = null

    /** The one source of time. Tests replace it with a moving clock; everything reads it when it needs the time. */
    @Volatile var clock: Clock = SystemClock()

    /** Instrumentation tests: base URL of a fake Claude / DeepSeek server on 127.0.0.1. When either is set only the set hosts are reachable. */
    @Volatile var claudeBase: HttpUrl? = null
    @Volatile var deepseekBase: HttpUrl? = null

    fun requireCompany(): CompanyApi = checkNotNull(company) { "the company is not registered yet" }

    // ---- data ------------------------------------------------------------------------------------------------------------

    val errorLog: ErrorLog by lazy { ErrorLog(context.filesDir) }

    /** The open encrypted database (opens on first use; call that first use off the main thread). */
    val db: SupportSQLiteDatabase by lazy { openDb() }
    val store: Store by lazy { SqlStore(db) }
    val settings: Settings by lazy { Settings(store) }
    val keys: KeyVault by lazy { keyVaultProvider() }
    val vault: MediaVault by lazy { mediaVaultProvider() }

    // ---- platform --------------------------------------------------------------------------------------------------------

    val attachments: Attachments by lazy { Attachments(context, vault) }
    val systemCamera: SystemCamera by lazy { SystemCamera.forApp(context) }
    val notifier: Notifier by lazy { Notifier(context) }
    val alarms: AlarmScheduler by lazy { AlarmScheduler(context) }
    val backup: Backup by lazy { Backup({ db }, vault, onRestored = { settings.reload() }) }
    val oldData: OldData by lazy { OldData.forApp(context) }

    // ---- network: the single exit ----------------------------------------------------------------------------------------

    val gate: NetworkGate by lazy { NetworkGate(allowed = ::allowedOrigins, onBlocked = { host -> errorLog.append("net", "blocked host $host") }) }
    val transport: Transport by lazy { Transport(HttpFactory.create(gate)) }

    /** Production: the three hosts. With a test base set: exactly the test hosts (never the real ones). */
    private fun allowedOrigins(): Set<Origin> {
        val bases = listOfNotNull(claudeBase, deepseekBase)
        if (bases.isEmpty()) return NetworkGate.allowlist(null)
        return bases.map { Origin(it.scheme, it.host, it.port) }.toSet()
    }

    // ---- update check ----------------------------------------------------------------------------------------------------

    /** The release check against `BuildConfig.RELEASES_REPO` (the Gradle property `dshm.releasesRepo`). */
    val update: UpdateCheck by lazy {
        UpdateCheck(
            release = { ReleaseCheck(transport, UpdateCheck.latestUrl(BuildConfig.RELEASES_REPO)) },
            settings = settings,
            notifier = notifier,
            clock = { clock },
            installedName = BuildConfig.VERSION_NAME,
            installedCode = BuildConfig.VERSION_CODE,
            context = context,
            log = { errorLog.append("update", it) },
        )
    }

    /** Process start: the notification channels and the daily update check. Cheap; opens no database. */
    fun start() {
        notifier.ensureChannels()
        runCatching { UpdateWork.schedule(context) }
    }
}
