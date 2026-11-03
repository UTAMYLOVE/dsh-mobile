package app.dshmobile.ui.shell

import androidx.activity.ComponentActivity
import app.dshmobile.DshMobileApp
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.runtime.DshVersions
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.runtime.VerifiedDsh
import app.dshmobile.runtime.VerifiedUpdatePolicy
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.AndroidOnboardingPlatform
import app.dshmobile.ui.MainOps
import app.dshmobile.ui.SettingsEnv
import app.dshmobile.ui.SettingsVersions
import app.dshmobile.ui.fog.EffectLevel
import okhttp3.OkHttpClient

/**
 * [app.dshmobile.ui.SettingsScreen]'s environment with the app's real singletons, for the shell's settings tab. Nothing here
 * runs on construction: versions, vault and exports are touched only when the screen asks.
 */
fun buildSettingsEnv(
    activity: ComponentActivity,
    app: DshMobileApp,
    onEffectLevel: (EffectLevel) -> Unit = app.effectsController::set,
    /** The installed versions; the end-to-end test hands in one whose npm only records (t05). */
    dshVersions: () -> DshVersions = { app.dshVersions },
): SettingsEnv {
    val platform = AndroidOnboardingPlatform(activity)
    // The rollback never targets a version this app has not verified (the same filter as the crash notification's rollback).
    val ops = MainOps(app.controller, { VerifiedUpdatePolicy.previous(dshVersions().previous()) }, { dshVersions().rollback() }, app.errorLog)
    val versions = object : SettingsVersions {
        override fun current(): String = dshVersions().current()
        override fun previous(): String? = VerifiedUpdatePolicy.previous(dshVersions().previous())
        override fun verified(): List<String> = VerifiedDsh.VERSIONS
        // The health check is part of ensureRunning; updateWithAutoRollback only needs the object. Only a verified version is installed:
        // from an unverified current this is the switch back to the newest verified one.
        override suspend fun checkUpdate(): UpdateOutcome {
            val versions = dshVersions()
            val target = VerifiedUpdatePolicy.target(runCatching { versions.current() }.getOrNull()) ?: return UpdateOutcome.UpToDate
            return versions.updateWithAutoRollback(app.controller, HealthCheck(OkHttpClient()), target)
        }
        override suspend fun rollback(): MainOps.RollbackOutcome = ops.rollback()
    }
    return SettingsEnv(
        vault = app.keyVault,
        store = app.agentSettingsStore,
        meter = app.costMeter,
        phantomState = PhantomProcessDetector(activity.contentResolver)::state,
        runtime = app.controller,
        isRunning = app.controller.isRunning,
        versions = versions,
        applyAgentTeams = app.agentConfigWriter::ensureAgentTeamsBundle,
        scheduleHeartbeat = { minutes -> HeartbeatScheduler.schedule(app, minutes) },
        // An explicit user restart ends a give-up; a refused service start (app in the background) is logged, not thrown.
        supervise = { app.supervise() },
        // Developer options hide until "build number" was tapped seven times: then the way there is the device info page.
        openDeveloperSettings = { if (platform.developerOptionsEnabled()) platform.openDeveloperSettings() else platform.openDeviceInfoSettings() },
        exportErrorLog = { app.exporter.share(app.exporter.errorLogZip()) },
        exportMemory = { app.exporter.share(app.exporter.memoryBackupZip()) },
        exportWorkspace = { app.exporter.share(app.exporter.workspaceZip()) },
        jobs = app.settingsJobs,
        effectLevel = app.effectsController.level,
        onEffectLevel = onEffectLevel,
    )
}
