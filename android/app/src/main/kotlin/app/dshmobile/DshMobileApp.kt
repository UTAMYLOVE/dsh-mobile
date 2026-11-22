package app.dshmobile

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import app.agency.core.metering.PriceTable
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.metering.CostMeter
import app.dshmobile.agency.migrate.LegacyCleanup
import app.dshmobile.agency.migrate.RetiredWork
import app.dshmobile.log.ErrorLog
import app.dshmobile.security.AndroidKeyVault
import app.dshmobile.security.KeyVault
import java.time.Clock
import kotlin.concurrent.thread

/**
 * 小雨娛樂's application: the error log, the key vault (his DeepSeek and Claude keys, unchanged since v0.8.3), the spend meter
 * (unchanged prefs `dshm_cost`) and the company ([AgencyApp]). Everything is lazy: nothing opens until the company's screen
 * calls [AgencyApp.start]. The class name stays (the manifest and an in-place upgrade over v0.8.3 point at it).
 */
class DshMobileApp : Application(), Configuration.Provider {
    val errorLog: ErrorLog by lazy { ErrorLog(filesDir) }
    val keyVault: KeyVault by lazy { AndroidKeyVault(this) }
    val costMeter: CostMeter by lazy {
        CostMeter(getSharedPreferences("dshm_cost", Context.MODE_PRIVATE), PriceTable(), clock = Clock.systemUTC())
    }

    /**
     * 小雨娛樂 (arch 1.1): the store, the importer, the providers and the brain. Nothing opens until [AgencyApp.start], which
     * the company's screen calls; the e2e runner sets [AgencyApp.testOverrides] before this is first read.
     */
    val agency: AgencyApp by lazy { AgencyApp(this, keyVault, costMeter, errorLog) }

    /** On-demand WorkManager init: an old build's heartbeat that still runs once ends at once ([RetiredWork]). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(RetiredWork.Factory()).build()

    override fun onCreate() {
        super.onCreate()
        // dsh is gone (arch §2 D2): the works and channels an older build left are removed, and its program files freed in
        // the background (his old conversations and novels stay; see LegacyCleanup).
        RetiredWork.cancel(this, errorLog)
        RetiredWork.deleteChannels(this, errorLog)
        thread(name = "legacy-cleanup", isDaemon = true) { LegacyCleanup(filesDir, cacheDir).run { errorLog.append("cleanup", it) } }
    }
}
