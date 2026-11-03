package app.dshmobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.dshmobile.DshMobileApp
import app.dshmobile.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps dsh alive while the user chats with it. If dsh dies, [CrashPolicy] decides
 * between restarting it and giving up (notify the user, stop the service).
 */
class DshService : Service() {
    private val app get() = application as DshMobileApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exitSubscription: AutoCloseable? = null
    private var resetJob: Job? = null
    private var startJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        exitSubscription = app.controller.onExit { code ->
            app.errorLog.append("service", "dsh exited with code $code")
            handleCrash()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notifier.ID_SERVICE, app.notifier.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        app.controller.retain(HOLDER)
        app.serviceGaveUp.value = false // a new start (the user opened the app) begins a fresh attempt
        startDsh()
        // Spec §11: dsh is not resident while the app is not in the foreground. After a process death the system must not
        // restart this service (and dsh) with nobody to end it: the next screen start or heartbeat starts dsh again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exitSubscription?.close()
        scope.cancel()
        // Stopping waits for the process to die; keep that off the main thread.
        val controller = app.controller
        // Let go of our hold only: a heartbeat (or the UI) that still holds dsh keeps it running.
        controller.release(HOLDER)
        Thread({ controller.stopIfUnretained() }, "dshm-stop").start()
        super.onDestroy()
    }

    private fun startDsh() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            try {
                app.controller.ensureRunning()
                scheduleCrashPolicyReset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RootfsNotInstalledException) {
                // Not a crash: restarting cannot help, and a "crashed repeatedly" notice would be misleading.
                app.errorLog.append("service", "could not start dsh: rootfs not installed")
                stopSelf()
            } catch (e: Exception) {
                app.errorLog.append("service", "could not start dsh: ${e.message}")
                handleCrash()
            }
        }
    }

    private fun handleCrash() {
        resetJob?.cancel()
        when (app.crashPolicy.onCrash()) {
            CrashPolicy.Decision.RESTART -> scope.launch {
                delay(RESTART_DELAY_MS)
                startDsh()
            }
            CrashPolicy.Decision.GIVE_UP -> {
                app.serviceGaveUp.value = true // before stop(): whoever sees dsh stop must already know why
                app.notifier.showCrashGiveUp()
                app.controller.stop() // dsh is dead or crash-looping: the one place that stops it whoever holds it
                app.crashPolicy.reset() // a manual restart by the user counts from zero again
                stopSelf()
            }
        }
    }

    /** A dsh that has run for [STABLE_AFTER_MS] is considered healthy again: earlier crashes no longer count. */
    private fun scheduleCrashPolicyReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(STABLE_AFTER_MS)
            app.crashPolicy.reset()
        }
    }

    companion object {
        /** The holder name this service uses with [DshController.retain]. */
        const val HOLDER = "service"
        private const val RESTART_DELAY_MS = 2_000L
        private const val STABLE_AFTER_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DshService::class.java))
        }
    }
}
