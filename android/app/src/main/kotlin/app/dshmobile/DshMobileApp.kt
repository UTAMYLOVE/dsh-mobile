package app.dshmobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration
import app.dshmobile.config.AgentConfigWriter
import app.dshmobile.config.AgentPatchProvider
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.runtime.DshClient
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.heartbeat.HeartbeatDeps
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.heartbeat.HeartbeatWorkerFactory
import app.dshmobile.log.ErrorLog
import app.dshmobile.overview.OverviewSource
import app.dshmobile.overview.OverviewState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import app.dshmobile.overview.ReminderLog
import app.dshmobile.heartbeat.nextRunAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import app.dshmobile.notify.Notifier
import app.dshmobile.export.Exporter
import app.dshmobile.runtime.AppPaths
import app.dshmobile.runtime.DshVersions
import app.dshmobile.runtime.VerifiedDsh
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.AndroidSignals
import app.dshmobile.runtime.ProcessTreeKiller
import app.dshmobile.runtime.ProcfsSource
import app.dshmobile.runtime.ProotGuestCommand
import app.dshmobile.runtime.ProotRunner
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.runtime.WorkspaceRegistrar
import app.dshmobile.security.AndroidKeyVault
import app.dshmobile.security.KeyVault
import app.dshmobile.service.CrashPolicy
import app.dshmobile.service.DshController
import app.dshmobile.service.DshLauncher
import app.dshmobile.remote.DshRemote
import app.dshmobile.service.DshService
import app.dshmobile.service.RunningDsh
import app.dshmobile.service.WorkRemote
import app.dshmobile.service.asWorkRemote
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.SettingsJobs
import app.dshmobile.ui.UiHolder
import app.dshmobile.ui.fog.EffectsController
import app.dshmobile.ui.fog.UiPrefs
import app.dshmobile.ui.shell.ShellSession
import app.dshmobile.ui.shell.ShellSessionHolder
import app.dshmobile.update.ApkInstaller
import app.dshmobile.update.ApkUpdateChecker
import app.dshmobile.update.DailyUpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Holds the app-wide singletons (service and heartbeat worker share them). Everything is lazy: nothing runs at process start. */
class DshMobileApp : Application(), Configuration.Provider {
    val errorLog: ErrorLog by lazy { ErrorLog(filesDir) }
    val notifier: Notifier by lazy { Notifier(this) }
    val keyVault: KeyVault by lazy { AndroidKeyVault(this) }
    val costMeter: CostMeter by lazy {
        CostMeter(getSharedPreferences("dshm_cost", Context.MODE_PRIVATE), PriceTable(), clock = Clock.systemUTC())
    }
    /** Where the rootfs and the user's data (outside it, bound into the guest) live on the host. */
    val appPaths: AppPaths by lazy { AppPaths(filesDir) }

    val rootfsInstaller: RootfsInstaller by lazy {
        // A reinstall never touches userdata/; data an older build kept inside the rootfs moves out first. If that move fails the
        // install fails too (the old rootfs, with the data, stays): never delete what could not be moved.
        RootfsInstaller({ assets.open(it) }, filesDir, BuildConfig.ROOTFS_VERSION, beforeReplace = { appPaths.migrateLegacy() })
    }
    val crashPolicy: CrashPolicy by lazy { CrashPolicy() } // SystemClock.elapsedRealtime: immune to wall-clock changes

    /**
     * True from the moment [app.dshmobile.service.DshService] gives up on a crash-looping dsh until the next start. The main
     * screen shows its error screen for it and never restarts dsh by itself (Retry is the user's explicit action).
     */
    val serviceGaveUp = MutableStateFlow(false)

    /**
     * The one HTTP client for talking to dsh on 127.0.0.1 (health checks, the workspace registrar, the shell's and the service's
     * remotes, Settings' update check): one connection pool and one dispatcher instead of one per caller (final review B1-M7).
     * [DshClient] adds its own cookie jar and `Proxy.NO_PROXY` on a derived client.
     */
    val loopbackHttp: OkHttpClient by lazy { OkHttpClient() }

    /** Builds the [WorkRemote] [DshService] watches for work; a test seam (production: a [DshRemote] on the running dsh). */
    @VisibleForTesting
    internal var workRemoteFactory: (RunningDsh, CoroutineScope) -> WorkRemote = { dsh, scope ->
        DshRemote(DshClient(loopbackHttp, dsh.launch), scope).asWorkRemote()
    }

    /** Starts [DshService]; a test seam (Robolectric cannot make `startForegroundService` refuse). */
    @VisibleForTesting
    internal var startDshService: (Context) -> Unit = DshService::start

    /**
     * The explicit hand-over of dsh to [DshService]'s crash supervision (Retry, a rollback, Settings' restart): ends a give-up and
     * starts the service. Android 12+ refuses a foreground-service start while the app is in the background
     * (`ForegroundServiceStartNotAllowedException`, e.g. a rollback that ends after the user left); that refusal is logged, never
     * thrown. Without the service dsh is then only held by whoever still holds it, and the next screen start starts the service.
     */
    fun supervise(context: Context = this) {
        serviceGaveUp.value = false
        try {
            startDshService(context)
        } catch (e: Exception) {
            errorLog.append("service", "could not start the service (${e.javaClass.simpleName})")
        }
    }

    /** Work that must not end with a screen (settings actions that stop and start dsh). Never cancelled. */
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val settingsJobs: SettingsJobs by lazy { SettingsJobs(applicationScope, controller) }

    /**
     * Ends dsh's and guest commands' process trees for real (bug-writer-held): `/proc` for the tree, `Os.kill` for the signals,
     * only ever this app's own uid. One per process, so a guest command running now is never swept as a leftover.
     */
    val processKiller: ProcessTreeKiller by lazy {
        ProcessTreeKiller(
            procs = ProcfsSource(),
            signals = AndroidSignals,
            myUid = android.os.Process.myUid(),
            myPid = android.os.Process.myPid(),
            markers = DshController.SWEEP_MARKERS,
        )
    }

    private val proot: ProotRunner by lazy {
        ProotRunner(File(applicationInfo.nativeLibraryDir), appPaths.rootfs, File(cacheDir, "proot"), appPaths.binds)
    }

    val agentSettingsStore: AgentSettingsStore by lazy {
        AgentSettingsStore(getSharedPreferences(AgentSettingsStore.PREFS, Context.MODE_PRIVATE))
    }

    /** dsh's own home on the host (guest `/root/.dsh`, bound in from [AppPaths.dshHome]). */
    val agentConfigWriter: AgentConfigWriter by lazy { AgentConfigWriter(appPaths.dshHome) }

    /**
     * Test-only: the model upstreams the gateway forwards to (the emulator end-to-end test points them at an in-process fake
     * model on 127.0.0.1). Must be set before [controller] is first used; null (always, in production) means the real providers.
     */
    @VisibleForTesting
    @Volatile
    var upstreamsOverride: Map<Provider, HttpUrl>? = null

    val controller: DshController by lazy {
        val loopback = loopbackHttp
        val health = HealthCheck(loopback)
        val workspaces = WorkspaceRegistrar(loopback, errorLog)
        DshController(
            filesDir = filesDir,
            isRootfsInstalled = rootfsInstaller::isInstalled,
            launcher = DshLauncher { env, args -> proot.command(args, env).start() },
            vault = keyVault,
            meter = costMeter,
            errorLog = errorLog,
            healthCheck = health::check,
            dnsServers = ::activeNetworkDnsServers,
            onNotify = notifier::show,
            onCostAlert = notifier::showCostAlert,
            onRateGuard = notifier::showRateGuard,
            patchProvider = AgentPatchProvider(
                vault = keyVault,
                store = agentSettingsStore,
                writer = agentConfigWriter,
                phantom = PhantomProcessDetector(contentResolver)::state,
            ),
            upstreams = upstreamsOverride ?: DshController.DEFAULT_UPSTREAMS,
            beforeLaunch = {
                appPaths.ensureUserDirs()
                // A rootfs upgrade resets dsh to its bundled version: a `current` link to a version that is gone goes back to it.
                try {
                    dshVersions.current()
                } catch (e: IllegalStateException) {
                    errorLog.append("controller", "no usable dsh version: ${e.message}")
                }
            },
            // dsh's own folder picker does not work in the WebView: list /root/workspace in dsh after every start.
            afterHealthy = { launch -> workspaces.ensure(launch) },
            killer = processKiller,
        )
    }

    /** dsh versions under the rootfs' /opt/dsh (guest commands run through proot); the main screen's rollback uses it. */
    val dshVersions: DshVersions by lazy {
        val rootfs = appPaths.rootfs
        DshVersions(
            File(rootfs, "opt/dsh"), ProotGuestCommand(proot, rootfs, ::activeNetworkDnsServers, processKiller, errorLog), errorLog,
            bundledVersion = BuildConfig.DSH_PINNED_VERSION,
        )
    }

    val exporter: Exporter by lazy { Exporter(this, appPaths.workspace, errorLog) }

    /**
     * The UI's hold on dsh and the ten-minute background grace. Process-level on purpose: the countdown must survive an
     * Activity being recreated.
     */
    val uiHolder: UiHolder by lazy {
        UiHolder(
            runtime = controller,
            holders = { controller.holders },
            // Work in flight keeps the service (and dsh); DshService ends itself when the work is done and nobody is looking.
            stopService = { if (DshService.WORK_HOLDER !in controller.holders) stopService(Intent(this, DshService::class.java)) },
        )
    }

    /** The last ten reminders the heartbeat's dream produced (the overview's 記憶提醒). */
    val reminderLog: ReminderLog by lazy {
        ReminderLog(getSharedPreferences(ReminderLog.PREFS, Context.MODE_PRIVATE), Clock.systemUTC())
    }

    /** Small per-install UI choices (effect level, last conversation). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(getSharedPreferences(UiPrefs.PREFS, Context.MODE_PRIVATE)) }

    /** The effect level the screen draws with: the user's choice held down by the jank governor. One per process. */
    val effectsController: EffectsController by lazy { EffectsController(uiPrefs) }

    /**
     * The native interface's link to the running dsh: one [ShellSession] per dsh instance, in a scope of its own (a child of
     * [applicationScope], cancelled when that instance's session closes). Whenever dsh stops, with or without a screen (the
     * background grace, a give-up), the session is detached here, so its remote never keeps retrying a dead port. On the main
     * thread, like every attach, so a stop seen late can never detach the next instance's session.
     */
    val shellSessions: ShellSessionHolder by lazy {
        val loopback = loopbackHttp
        val holder = ShellSessionHolder { running ->
            val scope = CoroutineScope(applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]))
            DshRemote(DshClient(loopback, running.launch), scope).let { ShellSession(it, it.events, scope, autoReview = { agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), false).autoReview }) }
        }
        applicationScope.launch(Dispatchers.Main) {
            controller.isRunning.collect { running -> if (!running) holder.detach() }
        }
        holder
    }

    /**
     * What the overview shows. The agents and recent flows follow whichever dsh session is current. [unverifiedDshVersion] is the current dsh version when this app has not verified it (read on IO by the source).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val overviewSource: OverviewSource by lazy {
        val sessions = shellSessions.current
        val agents: Flow<Int?> = sessions.flatMapLatest { it?.agents?.runningCount ?: flowOf(null) }
        val recent = sessions.flatMapLatest { s -> s?.index?.rows?.map { it.take(3) } ?: flowOf(emptyList()) }
        val live = sessions.flatMapLatest { s ->
            s?.tree?.state?.map { app.dshmobile.chat.allUsage(it) }?.distinctUntilChanged() ?: flowOf(app.dshmobile.chat.SessionUsage())
        }
        OverviewSource(
            meter = costMeter,
            reminders = reminderLog,
            agentsLimit = {
                agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), keyVault.has(Provider.ANTHROPIC)).maxConcurrent
            },
            heartbeatIntervalMin = { agentSettingsStore.heartbeatIntervalMin },
            nextHeartbeatAt = HeartbeatScheduler.nextRunAt(this),
            agentsRunning = agents,
            recent = recent,
            liveUsage = live,
            unverifiedDshVersion = { runCatching { dshVersions.current() }.getOrNull()?.takeUnless(VerifiedDsh::isVerified) },
            clock = Clock.systemUTC(),
            context = Dispatchers.IO,
        )
    }

    /**
     * [overviewSource]'s state shared by every overview visit: computed while the page shows and for five seconds after it, and
     * the last state kept, so a revisit never flashes an empty page (final review B2-M12).
     */
    val overviewState: StateFlow<OverviewState?> by lazy {
        overviewSource.state().stateIn(applicationScope, SharingStarted.WhileSubscribed(OVERVIEW_LINGER_MS), null)
    }

    /** Asks the public releases repo for a newer shell app (no credentials; a failure is the caller's to log). */
    val updateChecker: ApkUpdateChecker by lazy {
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        ApkUpdateChecker(http, BuildConfig.RELEASES_REPO, BuildConfig.VERSION_CODE)
    }

    /** Downloads, verifies and hands over a release APK; no call timeout (the APK carries the rootfs), only per-read ones. */
    val apkInstaller: ApkInstaller by lazy {
        ApkInstaller(this, OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
    }

    /** The heartbeat's once-a-day update check; announces a newer release with [Notifier.showUpdateAvailable]. */
    val dailyUpdateCheck: DailyUpdateCheck by lazy {
        DailyUpdateCheck(
            prefs = getSharedPreferences(DailyUpdateCheck.PREFS, Context.MODE_PRIVATE),
            check = updateChecker::latest,
            onAvailable = notifier::showUpdateAvailable,
            errorLog = errorLog,
        )
    }

    /** Everything [HeartbeatWorker] uses; WorkManager builds the worker through [workManagerConfiguration]'s factory. */
    val heartbeatDeps: HeartbeatDeps by lazy {
        // Long read timeout: the memory plugin's "dream" is one slow model call. The worker's own 5-minute limit still applies.
        val http = OkHttpClient.Builder().readTimeout(HeartbeatWorker.TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        HeartbeatDeps(
            runtime = controller,
            clientFor = { launch -> DshClient(http, launch) },
            // Only the heartbeat's own reminders are logged for the overview (the gateway's onNotify is dsh's plugins and is not).
            notify = { request ->
                reminderLog.add(request.title, request.body)
                notifier.show(request)
            },
            serviceNotification = notifier::serviceNotification,
            errorLog = errorLog,
            updateCheck = dailyUpdateCheck,
            serviceGaveUp = { serviceGaveUp.value },
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(HeartbeatWorkerFactory { heartbeatDeps }).build()

    override fun onCreate() {
        super.onCreate()
        // Before anything reads the workspace or dsh's home: data an older build kept inside the rootfs moves to userdata/.
        migrateLegacyUserData()
        // The interval is the user's setting (default 45). UPDATE keeps an existing schedule's timing.
        HeartbeatScheduler.schedule(this, agentSettingsStore.heartbeatIntervalMin)
    }

    /** [AppPaths.migrateLegacy], logged; a failure leaves the data where it was (nothing is deleted) and is retried next time. */
    private fun migrateLegacyUserData() {
        try {
            appPaths.migrateLegacy().forEach { errorLog.append("paths", "moved $it out of the rootfs into userdata") }
        } catch (e: Exception) {
            errorLog.append("paths", "could not move user data out of the rootfs (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val OVERVIEW_LINGER_MS = 5_000L
    }

    /** DNS servers of the active network as literals; link-local IPv6 ones are unusable in the guest without a scope id. */
    private fun activeNetworkDnsServers(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(network)?.dnsServers.orEmpty()
            .filterNot { it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }
}
