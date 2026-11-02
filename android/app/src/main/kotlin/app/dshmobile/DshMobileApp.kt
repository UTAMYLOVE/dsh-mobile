package app.dshmobile

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.PriceTable
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

/** Holds the app-wide singletons (service and heartbeat worker share them). Everything is lazy: nothing runs at process start. */
class DshMobileApp : Application() {
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

    /** DNS servers of the active network as literals; link-local IPv6 ones are unusable in the guest without a scope id. */
    private fun activeNetworkDnsServers(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(network)?.dnsServers.orEmpty()
            .filterNot { it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }
}
