package app.dshmobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration
import app.dshmobile.config.AgentConfigWriter
import app.agency.core.metering.RateGuard
import app.dshmobile.config.AppSkills
import app.dshmobile.config.AgentPatchProvider
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.runtime.DshClient
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.metering.CostMeter
import app.agency.core.metering.PriceTable
import app.agency.core.metering.Provider
import app.dshmobile.heartbeat.HeartbeatDeps
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.heartbeat.HeartbeatWorkerFactory
import app.dshmobile.log.ErrorLog
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
import app.dshmobile.runtime.RuntimeModeStore
import app.dshmobile.runtime.NativeRunner
import app.dshmobile.runtime.DshLayout
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.runtime.WorkspaceRegistrar
import app.dshmobile.security.AndroidKeyVault
import app.dshmobile.security.KeyVault
import app.dshmobile.service.CrashPolicy
import app.dshmobile.service.DshController
import app.dshmobile.service.DshLauncher
import app.dshmobile.service.RunningDsh
import app.dshmobile.system.PhantomProcessDetector
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
    val notifier: Notifier by lazy { Notifier(this).also { n -> n.isLocked = { id -> id != null && conversationPrefs.isLocked(id) } } }
    val keyVault: KeyVault by lazy { AndroidKeyVault(this) }
    val costMeter: CostMeter by lazy {
        CostMeter(getSharedPreferences("dshm_cost", Context.MODE_PRIVATE), PriceTable(), clock = Clock.systemUTC())
    }
    /** 教練模式: its settings, its records (the app's own files, outside dsh's workspace), its model client and the engine. */
    val coachSettings: app.dshmobile.coach.CoachSettings by lazy {
        app.dshmobile.coach.CoachSettings(getSharedPreferences(app.dshmobile.coach.CoachSettings.PREFS, Context.MODE_PRIVATE))
    }
    val coachStore: app.dshmobile.coach.CoachStore by lazy { app.dshmobile.coach.CoachStore(File(filesDir, "coach")) }
    val coachModel: app.dshmobile.coach.CoachModel by lazy {
        app.dshmobile.coach.HttpCoachModel(
            okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build(),
            keyVault, coachSettings, costMeter, { upstreamsOverride ?: DshController.DEFAULT_UPSTREAMS }, errorLog,
        )
    }
    val coach: app.dshmobile.coach.CoachEngine by lazy {
        app.dshmobile.coach.CoachEngine(
            coachStore, coachSettings, coachModel, Clock.systemUTC(), java.time.ZoneId.of("Asia/Taipei"), applicationScope,
            onError = { errorLog.append("coach", "engine: $it") },
        )
    }

    /** Where the rootfs and the user's data (outside it, bound into the guest) live on the host. */
    val appPaths: AppPaths by lazy { AppPaths(filesDir) }

    /** The bookshelf and setting cards, in the workspace (stage 2 §D). */
    val libraryStore: app.dshmobile.library.LibraryStore by lazy {
        app.dshmobile.library.LibraryStore(java.io.File(appPaths.workspace, app.dshmobile.library.LibraryStore.RELATIVE_PATH))
    }

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

    /** Work that must not end with a screen (settings actions that stop and start dsh). Never cancelled. */
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

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
        // The workspace is also bound at its own host path, under both spellings (/data/user/0/... and the /data/data/... dsh
        // records after resolving links): conversations made natively have one of them as their folder, and dsh never runs a
        // turn whose folder is missing.
        ProotRunner(
            File(applicationInfo.nativeLibraryDir), appPaths.rootfs, File(cacheDir, "proot"),
            appPaths.binds + app.dshmobile.runtime.GuardEnvironment.hostNames(appPaths.workspace).map { appPaths.workspace to it },
        )
    }

    /** The native launcher (Android's own node; no proot), the default since 0.7.0, see [layoutForStart]. */
    val nativeRunner: NativeRunner by lazy {
        NativeRunner(
            File(applicationInfo.nativeLibraryDir ?: File(filesDir, "no-native-libs").path), File(filesDir, "native"), File(appPaths.userData, "home"), File(cacheDir, "native-tmp"),
            assets = { name -> try { assets.open(name) } catch (_: java.io.IOException) { null } },
            remap = appPaths.binds.map { (host, guest) -> guest to host },
            log = { errorLog.append("native", it) },
        )
    }

    /** 朗讀 (0.5.0): one TextToSpeech for the app, created on first use. */
    val speaker: app.dshmobile.chat.Speaker by lazy { app.dshmobile.chat.Speaker(this) }
    /** The app's own weather analysis (0.5.0); the CWA key stays in the vault and in the app's own requests. */
    val weather: app.dshmobile.weather.WeatherService by lazy {
        app.dshmobile.weather.WeatherService(
            app.dshmobile.weather.OkHttpWeatherFetch(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()),
            getSharedPreferences(app.dshmobile.weather.WeatherService.PREFS, Context.MODE_PRIVATE),
            appPaths.workspace,
            cwaKey = { keyVault.getNamed(app.dshmobile.security.KeyVault.CWA) },
        )
    }

    /** Always-on helpers (0.5.0): their specs; the schedules themselves live in dsh. */
    val helperStore: app.dshmobile.chat.HelperStore by lazy { app.dshmobile.chat.HelperStore(java.io.File(filesDir, app.dshmobile.chat.HelperStore.FILE_NAME)) }

    /** Wakes dsh for the soonest helper (WorkManager); call after helpers change and after every heartbeat. */
    fun scheduleHelperWake() {
        runCatching { app.dshmobile.heartbeat.HeartbeatScheduler.wakeForHelper(this, helperStore.nextRun()) }
    }

    /** Projects (= custom assistants): `projects/<slug>/` in the workspace (0.5.0). */
    val projectStore: app.dshmobile.chat.ProjectStore by lazy { app.dshmobile.chat.ProjectStore(appPaths.workspace) }
    /** Pins, locks, temporary chats and projects of conversations (0.5.0); app-private, never in the workspace. */
    val conversationPrefs: app.dshmobile.chat.ConversationPrefs by lazy {
        app.dshmobile.chat.ConversationPrefs(java.io.File(filesDir, app.dshmobile.chat.ConversationPrefs.FILE_NAME))
    }

    val runtimeMode: RuntimeModeStore by lazy { RuntimeModeStore(getSharedPreferences(RuntimeModeStore.PREFS, Context.MODE_PRIVATE)) }

    /**
     * The layout of the next dsh start: native (the default) when this APK carries the native runtime, proot otherwise or when
     * the owner chose 「相容模式」.
     * Native runs the same dsh version (rootfs/opt/dsh/<current>) and plugins as proot would.
     */
    private fun layoutForStart(): DshLayout {
        if (!runtimeMode.native.value) return DshLayout.PROOT
        if (!nativeRunner.available) {
            errorLog.append("native", "native mode is on but this build has no native runtime: using proot")
            return DshLayout.PROOT
        }
        nativeRunner.prepare()
        val dshDir = File(appPaths.rootfs, "opt/dsh/" + dshVersions.current())
        return DshLayout.native(appPaths, dshDir, nativeRunner.node(), nativeRunner.shellPath)
    }

    val agentSettingsStore: AgentSettingsStore by lazy {
        AgentSettingsStore(getSharedPreferences(AgentSettingsStore.PREFS, Context.MODE_PRIVATE))
    }

    /** dsh's own home on the host (guest `/root/.dsh`, bound in from [AppPaths.dshHome]). */
    val agentConfigWriter: AgentConfigWriter by lazy {
        AgentConfigWriter(appPaths.dshHome) { name -> try { assets.open(name) } catch (_: java.io.IOException) { null } }
    }

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
            launcher = DshLauncher { env, args ->
                // The guard plugin's level, Auto review as it applies at that level, and the app's folders under every name
                // (GuardEnvironment); internal, not settings. ShellSession follows the same Auto review value until dsh's next
                // start, so the two never pull a conversation two ways.
                val level = runtimeMode.permission.value
                val switch = agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), false).autoReview
                val native = DshLayout.active.native
                val guard = app.dshmobile.runtime.GuardEnvironment.of(
                    level, switch, native,
                    nativeOwn = listOf(appPaths.rootfs, File(filesDir, "native")),
                    workspaceNames = app.dshmobile.runtime.GuardEnvironment.hostNames(appPaths.workspace),
                )
                if (native) nativeRunner.command(args, env + guard, appPaths.workspace).start()
                else proot.command(args, env + guard).start()
            },
            layout = ::layoutForStart,
            // Many agents at once make many requests: the runaway guard scales with the concurrency setting.
            rateLimitPerMinute = {
                val hasClaude = keyVault.get(Provider.ANTHROPIC) != null
                RateGuard.forAgents(agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), hasClaude).maxConcurrent)
            },
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
                permission = { runtimeMode.permission.value },
            ),
            upstreams = upstreamsOverride ?: DshController.DEFAULT_UPSTREAMS,
            beforeLaunch = {
                appPaths.ensureUserDirs()
                // 相容模式's node loads this before dsh (DshLayout.PROOT): proot's fake hard links broke new files.
                installAsset(DshLayout.REVIEW_JSON_ASSET, File(appPaths.rootfs, DshLayout.PROOT_REVIEW_JSON.removePrefix("/")))
                installAsset(DshLayout.PROOT_LINK_ASSET, File(appPaths.rootfs, DshLayout.PROOT_LINK.removePrefix("/")))
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

    /** Copies an app asset to [dst] when it differs (atomic: temp file, then rename); a failure is logged, not thrown. */
    private fun installAsset(name: String, dst: File) {
        try {
            val bytes = assets.open(name).use { it.readBytes() }
            if (dst.isFile && dst.readBytes().contentEquals(bytes)) return
            dst.parentFile?.mkdirs()
            val tmp = File(dst.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        } catch (e: java.io.IOException) {
            errorLog.append("controller", "could not install $name: ${e.javaClass.simpleName}")
        }
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

    /** The last ten reminders the heartbeat's dream produced (the overview's 記憶提醒). */
    val reminderLog: ReminderLog by lazy {
        ReminderLog(getSharedPreferences(ReminderLog.PREFS, Context.MODE_PRIVATE), Clock.systemUTC())
    }

    /** 「生圖服務位址」 (0.7.0): the owner's own image service, or blank; dsh reads it from the workspace note ([app.dshmobile.chat.ImageGen]). */
    val imageGenUrl: kotlinx.coroutines.flow.MutableStateFlow<String> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(getSharedPreferences("dshm_ui", Context.MODE_PRIVATE).getString(app.dshmobile.chat.ImageGen.KEY, "").orEmpty())
    }

    /** Stores [input] when it is a usable address or blank (blank = not connected) and rewrites the note; false = not an address. */
    fun setImageGenUrl(input: String): Boolean {
        val url = app.dshmobile.chat.ImageGen.clean(input)
        if (url == null && input.isNotBlank()) return false
        imageGenUrl.value = url.orEmpty()
        getSharedPreferences("dshm_ui", Context.MODE_PRIVATE).edit().putString(app.dshmobile.chat.ImageGen.KEY, url.orEmpty()).apply()
        applicationScope.launch(Dispatchers.IO) { app.dshmobile.chat.ImageGen.write(appPaths.workspace, url) }
        return true
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
            afterRun = {
                scheduleHelperWake()
                // dsh reads .dshm/weather.md for weather questions and the daily brief: keep it fresh.
                applicationScope.launch { runCatching { weather.refreshIfStale() } }
                applicationScope.launch(Dispatchers.IO) { app.dshmobile.chat.ImageGen.write(appPaths.workspace, app.dshmobile.chat.ImageGen.clean(imageGenUrl.value)) }
            },
        )
    }

    override val workManagerConfiguration: Configuration
        // Retired: dsh never starts again (arch §2 D0), even for a stale heartbeat that runs before onCreate cancels it.
        get() = Configuration.Builder().setWorkerFactory(HeartbeatWorkerFactory(retired = true) { heartbeatDeps }).build()

    /**
     * 小雨娛樂 (arch 1.1): the store, the importer, the providers and the brain. Nothing opens until [AgencyApp.start], which
     * the company's screen calls; the e2e runner sets [AgencyApp.testOverrides] before this is first read.
     */
    val agency: AgencyApp by lazy { AgencyApp(this, keyVault, costMeter, errorLog) }

    override fun onCreate() {
        super.onCreate()
        // Cutover (arch §2 D0): dsh never starts again. No heartbeat is scheduled, nothing is moved in its folders, the coach
        // engine is not warmed (its folder is the importer's, read-only), and the works an older build left are cancelled.
        cancelDshWork()
        deleteDshChannels()
    }

    /** The old dsh channels and whatever they still show (a tap on one would reach the retired shell). */
    private fun deleteDshChannels() {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            listOf(Notifier.CHANNEL_MESSAGES, Notifier.CHANNEL_ALERTS, Notifier.CHANNEL_SERVICE).forEach(nm::deleteNotificationChannel)
        }.onFailure { errorLog.append("cutover", "could not delete the old channels (${it.javaClass.simpleName})") }
    }

    /** The heartbeat and every helper wake (all tagged with the worker's class name); a failure is logged, never thrown. */
    private fun cancelDshWork() {
        try {
            val wm = androidx.work.WorkManager.getInstance(this)
            wm.cancelUniqueWork(HeartbeatScheduler.WORK_NAME)
            wm.cancelAllWorkByTag(HeartbeatWorker::class.java.name)
        } catch (e: Exception) {
            errorLog.append("cutover", "could not cancel the old works (${e.javaClass.simpleName})")
        }
    }

    private companion object {
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
