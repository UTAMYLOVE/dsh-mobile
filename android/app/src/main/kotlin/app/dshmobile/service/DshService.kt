package app.dshmobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import app.dshmobile.DshMobileApp
import app.dshmobile.notify.Notifier
import app.dshmobile.ui.UiHolder
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

    // Work in the background (spec §5.11): while dsh has work the "work" holder keeps it (and this service) alive.
    @Volatile internal var tracker: WorkTracker? = null
        private set
    private var trackerScope: CoroutineScope? = null
    private var remote: WorkRemote? = null
    private var wasBusy = false // only touched by the one collector of the current tracker, or under attachLock
    private var lastWork: RunningWork? = null
    private val attachLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        exitSubscription = app.controller.onExit { code ->
            app.errorLog.append("service", "dsh exited with code $code")
            handleCrash()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(Notifier.EXTRA_ACTION) == Notifier.ACTION_STOP_WORK) {
            // The notification's 「停止」: not a (re)start. Without a tracker there is nothing to stop and nothing running here.
            val t = tracker
            if (t == null) stopSelf() else scope.launch { t.stopAll() }
            return START_NOT_STICKY
        }
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

    /**
     * The owner swiped the app away. With work in flight nothing changes: the service and dsh keep going until the work is
     * done. Without work the service has never stopped here either (dsh follows the usual holder and grace rules).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        exitSubscription?.close()
        detachTracker()
        scope.cancel()
        // Stopping waits for the process to die; keep that off the main thread.
        val controller = app.controller
        // Let go of our hold only: a heartbeat (or the UI) that still holds dsh keeps it running.
        controller.release(HOLDER)
        controller.release(WORK_HOLDER)
        Thread({ controller.stopIfUnretained() }, "dshm-stop").start()
        super.onDestroy()
    }

    private fun startDsh() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            try {
                val dsh = app.controller.ensureRunning()
                scheduleCrashPolicyReset()
                attachTracker(dsh)
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

    /** Watches the (new) dsh for work; a restart after a crash replaces the old watcher and its hold. */
    private fun attachTracker(dsh: RunningDsh) {
        synchronized(attachLock) {
            detachTracker()
            val ts = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
            val r = app.workRemoteFactory(dsh, ts)
            val t = WorkTracker(r, ts)
            trackerScope = ts
            remote = r
            tracker = t
            ts.launch { t.running.collect { onWork(t, it) } }
        }
    }

    private fun detachTracker() {
        synchronized(attachLock) {
            trackerScope?.cancel()
            trackerScope = null
            runCatching { remote?.close() }
            remote = null
            tracker = null
            if (wasBusy) app.controller.release(WORK_HOLDER)
            wasBusy = false
        }
    }

    private fun onWork(from: WorkTracker, work: List<RunningWork>) {
        synchronized(attachLock) { handleWork(from, work) }
    }

    private fun handleWork(from: WorkTracker, work: List<RunningWork>) {
        if (tracker !== from) return // replaced or destroyed while this update was waiting
        if (work.isNotEmpty()) {
            lastWork = work.firstOrNull { it.parentId == null } ?: work.first()
            if (!wasBusy) {
                app.controller.retain(WORK_HOLDER)
                wasBusy = true
            }
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE, app.notifier.workingNotification(work),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (wasBusy) {
            wasBusy = false
            app.controller.release(WORK_HOLDER)
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE, app.notifier.serviceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            if (!app.isInForeground) app.notifier.showWorkDone(lastWork?.sessionId, lastWork?.title)
            stopWhenNobodyIsLooking()
        }
    }

    /**
     * The UI's grace countdown may have run out while the work held dsh (it then left the service alone): end it now. If the
     * countdown is still pending, or the UI is up, they end it by their own rules.
     */
    private fun stopWhenNobodyIsLooking() {
        Handler(Looper.getMainLooper()).post {
            if (!app.isInForeground && !app.uiHolder.graceScheduled && UiHolder.HOLDER !in app.controller.holders) stopSelf()
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

        /** Held while any conversation or subagent is running (see [WorkTracker]). */
        const val WORK_HOLDER = "work"
        private const val RESTART_DELAY_MS = 2_000L
        private const val STABLE_AFTER_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DshService::class.java))
        }
    }
}
