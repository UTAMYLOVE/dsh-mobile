package app.dshmobile.agency.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.agency.core.schedule.SlotKey
import app.dshmobile.DshMobileApp

/**
 * The model's words for a push that is already on screen (arch 1.6): expedited, needs the network; the same notification is
 * updated silently and the chat line takes the words. A failure, the spend cap or the daily cap keeps the template.
 */
class WordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val post = read(inputData) ?: return Result.success()
        val app = applicationContext as? DshMobileApp ?: return Result.success()
        app.agency.word(post)
        return Result.success()
    }

    companion object {
        const val KEY = "key"
        const val NUDGE = "nudge"
        const val TEXT = "text"
        const val CHANNEL = "channel"
        const val SLOT = "slot"
        const val GAPS = "gaps"

        fun data(p: Post): Data = Data.Builder()
            .putString(KEY, p.key).putString(NUDGE, p.nudgeId).putString(TEXT, p.text).putString(CHANNEL, p.channel.name)
            .putString(SLOT, p.slot?.name).putStringArray(GAPS, p.gapKeys.map { it.name }.toTypedArray()).build()

        fun read(d: Data): Post? {
            val id = d.getString(NUDGE) ?: return null
            return Post(
                key = d.getString(KEY) ?: return null, nudgeId = id, text = d.getString(TEXT).orEmpty(),
                channel = PostChannel.entries.firstOrNull { it.name == d.getString(CHANNEL) } ?: PostChannel.SCHEDULE, alert = false,
                gapKeys = d.getStringArray(GAPS).orEmpty().mapNotNull { SlotKey.parse(it) }, slot = SlotKey.parse(d.getString(SLOT)),
            )
        }

        fun enqueue(context: Context, p: Post) {
            val request = OneTimeWorkRequestBuilder<WordingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(data(p))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
