package app.dshmobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.dshmobile.DshMobileApp
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.service.DshService
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.theme.SakuraTheme
import okhttp3.OkHttpClient

/**
 * Hosts [SettingsScreen] with the app's real singletons. While it is on screen it counts as the UI for [UiHolder]: dsh must not
 * be stopped after ten minutes just because the user is reading settings with the main screen stopped behind it.
 */
class SettingsActivity : ComponentActivity() {
    private val app get() = application as DshMobileApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val env = buildEnv()
        setContent { SakuraTheme { SettingsScreen(env, onClose = ::finish) } }
    }

    override fun onStart() {
        super.onStart()
        app.uiHolder.onUiStart()
    }

    override fun onStop() {
        app.uiHolder.onUiStop()
        super.onStop()
    }

    private fun buildEnv(): SettingsEnv {
        val app = app
        val platform = AndroidOnboardingPlatform(this)
        val ops = MainOps(app.controller, { app.dshVersions.previous() }, { app.dshVersions.rollback() }, app.errorLog)
        val versions = object : SettingsVersions {
            override fun current(): String = app.dshVersions.current()
            override fun previous(): String? = app.dshVersions.previous()
            // The health check is part of ensureRunning; updateWithAutoRollback only needs the object.
            override suspend fun checkUpdate(): UpdateOutcome = app.dshVersions.updateWithAutoRollback(app.controller, HealthCheck(OkHttpClient()))
            override suspend fun rollback(): MainOps.RollbackOutcome = ops.rollback()
        }
        return SettingsEnv(
            vault = app.keyVault,
            store = app.agentSettingsStore,
            meter = app.costMeter,
            phantomState = PhantomProcessDetector(contentResolver)::state,
            runtime = app.controller,
            isRunning = app.controller.isRunning,
            versions = versions,
            applyAgentTeams = app.agentConfigWriter::ensureAgentTeamsBundle,
            scheduleHeartbeat = { minutes -> HeartbeatScheduler.schedule(applicationContext, minutes) },
            supervise = {
                app.serviceGaveUp.value = false // an explicit user restart ends a give-up (see MainActivity.superviseAndShow)
                DshService.start(applicationContext)
            },
            // Developer options hide until "build number" was tapped seven times: then the way there is the device info page.
            openDeveloperSettings = { if (platform.developerOptionsEnabled()) platform.openDeveloperSettings() else platform.openDeviceInfoSettings() },
            exportErrorLog = { app.exporter.share(app.exporter.errorLogZip()) },
            exportMemory = { app.exporter.share(app.exporter.memoryBackupZip()) },
        )
    }
}
