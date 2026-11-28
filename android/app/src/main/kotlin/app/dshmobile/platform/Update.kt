package app.dshmobile.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.net.NetworkGate
import app.agency.core.net.ReleaseCheck
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.data.NewVersion
import app.dshmobile.data.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 新版本通知: at most once a day, ask GitHub (through the one network gate) for the latest release of the public releases
 * repository. A newer one is kept in the settings (a line in 設定) and notified once, quietly; tapping the notification opens the
 * release page. The day is stored before the request, so a failure is not retried the same day. No key, no id of him or the phone
 * goes out: see [ReleaseCheck].
 */
class UpdateCheck(
    private val release: () -> ReleaseCheck,
    private val settings: Settings,
    private val notifier: Notifier,
    private val clock: () -> Clock,
    private val installedName: String,
    private val installedCode: Int,
    private val context: Context,
    private val log: (String) -> Unit = {},
) {
    enum class Outcome { SKIPPED_TODAY, NO_ANSWER, UP_TO_DATE, NEWER }

    /** Runs the check unless it already ran today ([force] ignores that). */
    suspend fun run(force: Boolean = false): Outcome {
        val today = Days.today(clock()).toString()
        if (!force && settings.get().updateCheckedDay == today) return Outcome.SKIPPED_TODAY
        settings.update { it.copy(updateCheckedDay = today) }
        val latest = try {
            release().latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("release check failed (${e.javaClass.simpleName})")
            null
        } ?: return Outcome.NO_ANSWER
        if (!ReleaseCheck.newer(latest, installedName, installedCode)) {
            if (settings.get().newVersion != null) settings.update { it.copy(newVersion = null) }
            notifier.cancel(NOTICE_KEY)
            return Outcome.UP_TO_DATE
        }
        val known = settings.get().newVersion
        settings.update { it.copy(newVersion = NewVersion(latest.versionName, latest.page)) }
        if (known?.versionName != latest.versionName) {
            notifier.post(
                NOTICE_KEY, context.getString(R.string.update_title), context.getString(R.string.update_text, latest.versionName),
                loud = false, openUrl = latest.page,
            )
        }
        return Outcome.NEWER
    }

    companion object {
        const val NOTICE_KEY = "update"

        /** The latest-release address of [repo] (`owner/name`) on GitHub's API host, the only host the check may reach. */
        fun latestUrl(repo: String): HttpUrl = "https://${NetworkGate.GITHUB_API_HOST}/repos/$repo/releases/latest".toHttpUrl()
    }
}

/** The daily check as WorkManager work (network required). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as? DshMobileApp)?.graph ?: return Result.success()
        try {
            graph.update.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.errorLog.append("update", "worker failed (${e.javaClass.simpleName})")
        }
        return Result.success()
    }
}

object UpdateWork {
    const val NAME = "xiaoyu-update-check"

    /** Registers the daily check once (an existing registration is kept). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
