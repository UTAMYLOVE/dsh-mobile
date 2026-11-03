package app.dshmobile.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.dshmobile.R
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.gateway.Provider
import app.dshmobile.service.DshService
import app.dshmobile.service.RunningWork
import app.dshmobile.update.ReleaseInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts every notification the app shows. Taps open `app.dshmobile.ui.MainActivity` (built by Task 11a, so it is
 * addressed by class name) with extras `sessionId` or `action`. Without notification permission nothing is
 * posted and nothing is thrown. All PendingIntents are immutable.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val nextId = AtomicInteger(FIRST_DYNAMIC_ID)

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "代理人訊息", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ALERTS, "花費與錯誤警示", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SERVICE, "背景執行", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun show(req: NotifyRequest) {
        val id = nextId.getAndIncrement()
        post(
            id,
            builder(CHANNEL_MESSAGES, req.title, req.body)
                .setContentIntent(openMainActivity(id, EXTRA_SESSION_ID to req.sessionId))
                .setAutoCancel(true),
        )
    }

    fun showCostAlert(usd: Double) {
        val text = String.format(Locale.US, "今日花費已超過 US$%.2f", usd)
        post(ID_COST, builder(CHANNEL_ALERTS, "花費提醒", text).setContentIntent(openMainActivity(ID_COST)).setAutoCancel(true))
    }

    fun showRateGuard(p: Provider) {
        val name = when (p) {
            Provider.DEEPSEEK -> "DeepSeek"
            Provider.ANTHROPIC -> "Anthropic"
        }
        post(
            ID_RATE_GUARD + p.ordinal,
            builder(CHANNEL_ALERTS, "請求過於頻繁", "$name 的請求已被暫時限制，以免異常重送耗費額度")
                .setContentIntent(openMainActivity(ID_RATE_GUARD + p.ordinal))
                .setAutoCancel(true),
        )
    }

    fun showCrashGiveUp() {
        post(
            ID_CRASH,
            builder(CHANNEL_ALERTS, "DSH Mobile 已停止", "代理人多次當掉，已不再自動重啟")
                .setContentIntent(openMainActivity(ID_CRASH))
                .addAction(action("退版", ID_CRASH + 1, ACTION_ROLLBACK))
                .addAction(action("查看錯誤記錄", ID_CRASH + 2, ACTION_EXPORT_ERROR_LOG))
                .setAutoCancel(true),
        )
    }

    /** One notice for the newest release: a newer one replaces it. The tap carries only `action=install_update`; MainActivity re-checks and downloads by itself. */
    fun showUpdateAvailable(info: ReleaseInfo) {
        post(
            ID_UPDATE,
            builder(CHANNEL_ALERTS, "有新版本可更新", "DSH Mobile ${info.versionName} 已發佈，點一下下載並安裝")
                .setContentIntent(openMainActivity(ID_UPDATE, EXTRA_ACTION to ACTION_INSTALL_UPDATE))
                .setAutoCancel(true),
        )
    }

    /** The APK is downloaded and verified but could not be handed to the installer: the same tap action retries (the download is reused). */
    fun showUpdateReady(info: ReleaseInfo) {
        post(
            ID_UPDATE,
            builder(CHANNEL_ALERTS, "新版已下載", "DSH Mobile ${info.versionName} 已下載，點一下安裝")
                .setContentIntent(openMainActivity(ID_UPDATE, EXTRA_ACTION to ACTION_INSTALL_UPDATE))
                .setAutoCancel(true),
        )
    }

    fun serviceNotification(): Notification =
        builder(CHANNEL_SERVICE, "DSH Mobile 執行中", "AI 代理人正在背景運作")
            .setContentIntent(openMainActivity(ID_SERVICE))
            .setOngoing(true)
            .build()

    /**
     * The service notification while dsh works: the first conversation's title (at most 40 characters, "等 N 件" for the
     * rest) and a 「停止」 action that sends `action=stop_work` to [DshService]. Never a URL or a token.
     */
    fun workingNotification(work: List<RunningWork>): Notification {
        val first = work.firstOrNull { it.parentId == null } ?: work.firstOrNull()
        val name = first?.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::clip) ?: "未命名對話"
        val text = if (work.size > 1) "$name 等 ${work.size} 件" else name
        val stop = PendingIntent.getService(
            context, ID_WORK_STOP,
            Intent(context, DshService::class.java).putExtra(EXTRA_ACTION, ACTION_STOP_WORK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return builder(CHANNEL_SERVICE, "DSH 正在工作…", text)
            // Not ID_SERVICE: the plain notification's intent (no extras) would replace this one's conversation (FLAG_UPDATE_CURRENT).
            .setContentIntent(openMainActivity(ID_WORK_OPEN, EXTRA_SESSION_ID to first?.sessionId))
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    /** Dsh finished everything while the app was away; the tap opens that conversation. */
    fun showWorkDone(sessionId: String?, title: String?) {
        val id = nextId.getAndIncrement()
        val name = title?.trim()?.takeIf { it.isNotEmpty() }?.let(::clip) ?: "未命名對話"
        post(
            id,
            builder(CHANNEL_MESSAGES, "DSH 完成了", name)
                .setContentIntent(openMainActivity(id, EXTRA_SESSION_ID to sessionId))
                .setAutoCancel(true),
        )
    }

    /**
     * dsh waits on an approval or a question while nobody looks (final review A-I3): one notice (a newer one replaces it) whose
     * tap opens [sessionId], the top-level conversation that asks. [cancelWaitingForAnswer] withdraws it once nothing waits.
     */
    fun showWaitingForAnswer(sessionId: String?, title: String?) {
        val name = title?.trim()?.takeIf { it.isNotEmpty() }?.let(::clip) ?: "未命名對話"
        post(
            ID_WAITING,
            builder(CHANNEL_ALERTS, "DSH 在等你回答", name)
                .setContentIntent(openMainActivity(ID_WAITING, EXTRA_SESSION_ID to sessionId))
                .setAutoCancel(true),
        )
    }

    fun cancelWaitingForAnswer() {
        manager.cancel(ID_WAITING)
    }

    private fun clip(s: String): String {
        if (s.length <= MAX_TITLE) return s
        var cut = MAX_TITLE - 1
        if (Character.isHighSurrogate(s[cut - 1])) cut-- // never split a surrogate pair
        return s.take(cut) + "…"
    }

    private fun builder(channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)

    private fun action(title: String, requestCode: Int, action: String): Notification.Action =
        Notification.Action.Builder(null, title, openMainActivity(requestCode, EXTRA_ACTION to action)).build()

    /** Each notification needs its own [requestCode]: PendingIntents that differ only in extras would otherwise be merged. */
    private fun openMainActivity(requestCode: Int, extra: Pair<String, String?>? = null): PendingIntent {
        val intent = Intent().apply {
            component = ComponentName(context, MAIN_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (extra?.second != null) putExtra(extra.first, extra.second)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(id: Int, builder: Notification.Builder) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "dshm_messages"
        const val CHANNEL_ALERTS = "dshm_alerts"
        const val CHANNEL_SERVICE = "dshm_service"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_ACTION = "action"
        const val ACTION_ROLLBACK = "rollback"
        const val ACTION_EXPORT_ERROR_LOG = "export_error_log"
        const val ACTION_INSTALL_UPDATE = "install_update"
        const val ACTION_STOP_WORK = "stop_work"
        private const val MAX_TITLE = 40
        private const val ID_WORK_STOP = 7
        private const val ID_WAITING = 8
        private const val ID_WORK_OPEN = 9
        const val ID_SERVICE = 1
        private const val ID_COST = 2
        private const val ID_RATE_GUARD = 3 // 3 and 4: one per provider
        const val ID_HEARTBEAT = 5 // the heartbeat's foreground notification; not ID_SERVICE, whose notification WorkManager would cancel when the heartbeat ends
        private const val ID_UPDATE = 6
        private const val ID_CRASH = 10 // 10..12: notification and its two actions' request codes
        private const val FIRST_DYNAMIC_ID = 100
        const val MAIN_ACTIVITY = "app.dshmobile.ui.MainActivity"
    }
}
