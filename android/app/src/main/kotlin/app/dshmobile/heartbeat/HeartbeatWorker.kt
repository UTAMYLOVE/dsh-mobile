package app.dshmobile.heartbeat

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.log.ErrorLog
import app.dshmobile.notify.Notifier
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.update.DailyUpdateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What the worker needs; the app builds it from its singletons, tests build it from fakes. */
class HeartbeatDeps(
    val runtime: DshHolderRuntime,
    val clientFor: (LaunchUrl) -> DshClient,
    val notify: (NotifyRequest) -> Unit,
    val serviceNotification: () -> Notification,
    val errorLog: ErrorLog,
    val timeoutMs: Long = HeartbeatWorker.TIMEOUT_MS,
    /** The once-a-day shell app update check that rides on the heartbeat; null = none. */
    val updateCheck: DailyUpdateCheck? = null,
    /** `DshMobileApp.serviceGaveUp`: after a give-up only the user restarts dsh, so the heartbeat leaves it alone. */
    val serviceGaveUp: () -> Boolean = { false },
    /** After every run: schedules the wake for the next always-on helper (0.5.0). */
    val afterRun: () -> Unit = {},
)

/** Hands [deps] to [HeartbeatWorker]; any other worker class falls through to WorkManager's default factory. */
class HeartbeatWorkerFactory(private val deps: () -> HeartbeatDeps) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == HeartbeatWorker::class.java.name) HeartbeatWorker(appContext, workerParameters, deps()) else null
}

/**
 * The periodic "dream" run: cold-start dsh if nobody else has it up, ask the memory plugin to consolidate the daily
 * notes, post the reminders it returns, and let dsh go again unless somebody else (the service, the UI) holds it.
 *
 * The first heartbeat of each Taipei day also asks the releases repo for a newer shell app ([DailyUpdateCheck]).
 *
 * It never retries: every failure (route error, 409 busy, timeout, exception) is logged by status or exception class
 * only and the run ends in success, so the next period simply tries again. It never starts a second dsh and never
 * stops one that another holder uses (see [DshHolderRuntime.stopIfUnretained]).
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: HeartbeatDeps,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        goForeground()
        // Independent of dsh (needs no rootfs, no holder) and unable to fail the run: it logs its own problems.
        deps.updateCheck?.runIfDue()
        if (deps.serviceGaveUp()) {
            // dsh crashed repeatedly and was given up on: starting it again every period would only crash it again (and a dsh
            // started here would run unsupervised behind the error screen). Retry or the rollback are the user's way back.
            deps.errorLog.append(TAG, "skipped: dsh gave up after repeated crashes")
            return Result.success()
        }
        deps.runtime.retain(HOLDER)
        try {
            withTimeout(deps.timeoutMs) { heartbeat() }
            // A helper wake: keep dsh up while the helper's schedule comes due and runs.
            val hold = inputData.getLong(HeartbeatScheduler.HOLD_MS_KEY, 0L).coerceIn(0L, 15 * 60_000L)
            if (hold > 0) kotlinx.coroutines.delay(hold)
        } catch (e: TimeoutCancellationException) {
            deps.errorLog.append(TAG, "skipped: timed out after ${deps.timeoutMs} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.errorLog.append(TAG, "skipped: ${e.javaClass.simpleName}")
        } finally {
            // Must also run when WorkManager cancels this worker; stopping blocks until the process is gone.
            withContext(NonCancellable + Dispatchers.IO) {
                deps.runtime.release(HOLDER)
                deps.runtime.stopIfUnretained()
            }
            runCatching { deps.afterRun() }
        }
        return Result.success()
    }

    /**
     * Android 12+ refuses a foreground start from the background (ForegroundServiceStartNotAllowedException), and a
     * refusal must not cost the heartbeat: WorkManager keeps the process alive while doWork runs, which is all a
     * cold start needs. So any failure is logged and the work goes on.
     */
    private suspend fun goForeground() {
        try {
            setForeground(
                ForegroundInfo(Notifier.ID_HEARTBEAT, deps.serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.errorLog.append(TAG, "could not go foreground (${e.javaClass.simpleName}), continuing in the background")
        }
    }

    private suspend fun heartbeat() {
        val running = deps.runtime.ensureRunning()
        val client = deps.clientFor(running.launch)
        client.login()
        val response = client.postJsonForText(ROUTE, "{}")
        if (response.code != 200) {
            deps.errorLog.append(TAG, "skipped: HTTP ${response.code}")
            return
        }
        val parsed = parse(response.body)
        if (parsed == null) {
            deps.errorLog.append(TAG, "skipped: unparseable response")
            return
        }
        for (reminder in parsed.reminders) {
            try {
                deps.notify(NotifyRequest(reminder.first, reminder.second, null))
            } catch (e: Exception) {
                deps.errorLog.append(TAG, "could not post a reminder (${e.javaClass.simpleName})")
            }
        }
        deps.errorLog.append(TAG, "done: promoted=${parsed.promoted} reminders=${parsed.reminders.size}")
    }

    private class Parsed(val promoted: Int, val reminders: List<Pair<String, String>>)

    private fun parse(body: String): Parsed? {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return null
        }
        val promoted = (root["promoted"] as? JsonArray)?.size ?: 0
        val reminders = (root["reminders"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            val text = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            title to text
        }
        return Parsed(promoted, reminders)
    }

    companion object {
        const val HOLDER = "heartbeat"
        const val TIMEOUT_MS = 5 * 60_000L
        private const val TAG = "heartbeat"
        private const val ROUTE = "/dshm/heartbeat"
    }
}
