package app.dshmobile

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Configuration
import app.dshmobile.runtime.DshClient
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.PriceTable
import app.dshmobile.heartbeat.HeartbeatDeps
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.heartbeat.HeartbeatWorkerFactory
import app.dshmobile.log.ErrorLog
import app.dshmobile.notify.Notifier
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.ProotRunner
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.security.AndroidKeyVault
import app.dshmobile.security.KeyVault
import app.dshmobile.service.CrashPolicy
import app.dshmobile.service.DshController
import app.dshmobile.service.DshLauncher
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
    val rootfsInstaller: RootfsInstaller by lazy {
        RootfsInstaller({ assets.open(it) }, filesDir, BuildConfig.ROOTFS_VERSION)
    }
    val crashPolicy: CrashPolicy by lazy { CrashPolicy(clock = System::currentTimeMillis) }

    val controller: DshController by lazy {
        val proot = ProotRunner(File(applicationInfo.nativeLibraryDir), File(filesDir, "rootfs"), File(cacheDir, "proot"))
        val health = HealthCheck(OkHttpClient())
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
        )
    }

    /** Everything [HeartbeatWorker] uses; WorkManager builds the worker through [workManagerConfiguration]'s factory. */
    val heartbeatDeps: HeartbeatDeps by lazy {
        // Long read timeout: the memory plugin's "dream" is one slow model call. The worker's own 5-minute limit still applies.
        val http = OkHttpClient.Builder().readTimeout(HeartbeatWorker.TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        HeartbeatDeps(
            runtime = controller,
            clientFor = { launch -> DshClient(http, launch) },
            notify = notifier::show,
            serviceNotification = notifier::serviceNotification,
            errorLog = errorLog,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(HeartbeatWorkerFactory { heartbeatDeps }).build()

    override fun onCreate() {
        super.onCreate()
        // The interval comes from settings in Task 11b; until then the default. UPDATE keeps an existing schedule's timing.
        HeartbeatScheduler.schedule(this, HeartbeatScheduler.DEFAULT_INTERVAL_MIN)
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
