package app.dshmobile.ui.shell

import app.dshmobile.runtime.NativeModeControl
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
        override fun updateTarget(): String? = VerifiedUpdatePolicy.target(runCatching { dshVersions().current() }.getOrNull())
        // The health check is part of ensureRunning; updateWithAutoRollback only needs the object. Only a verified version is installed:
        // from an unverified current this is the switch back to the newest verified one.
        override suspend fun checkUpdate(): UpdateOutcome {
            val versions = dshVersions()
            val target = VerifiedUpdatePolicy.target(runCatching { versions.current() }.getOrNull()) ?: return UpdateOutcome.UpToDate
            return versions.updateWithAutoRollback(app.controller, HealthCheck(app.loopbackHttp), target)
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
        // The native switch restarts dsh in the chosen mode on the app's settings jobs (it outlives the screen).
        nativeMode = NativeModeControl(app.nativeRunner.available, app.runtimeMode.native) { on ->
            app.runtimeMode.set(on)
            app.settingsJobs.launch {
                runCatching {
                    app.controller.stop()
                    app.controller.ensureRunning()
                }.onFailure { app.errorLog.append("native", "restart after the mode switch failed: ${it.javaClass.simpleName}") }
            }
        },
        exportWorkspace = { app.exporter.share(app.exporter.workspaceZip()) },
        jobs = app.settingsJobs,
        effectLevel = app.effectsController.level,
        onEffectLevel = onEffectLevel,
        effectiveLevel = app.effectsController.effective,
        // Any session of the current dsh running, subagents included (they are in the same tree).
        dshStatus = app.controller.status,
        openAppSettings = {
            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.packageName, null)))
        },
        workRunning = { app.shellSessions.current.value?.tree?.state?.value?.summaries?.values?.any { it.running == true } == true },
    )
}
