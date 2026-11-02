package app.dshmobile.ui

import android.content.Intent
import app.dshmobile.notify.Notifier

/**
 * What a launch or `onNewIntent` intent of MainActivity asks for (extras set by [Notifier]). MainActivity is exported, so
 * anything unrecognised is ignored, and a session id is only accepted in a plain, short shape.
 */
sealed interface LaunchRequest {
    data object None : LaunchRequest
    data object Rollback : LaunchRequest
    data object ExportErrorLog : LaunchRequest
    data class OpenSession(val id: String) : LaunchRequest

    companion object {
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,200}$")

        fun from(intent: Intent?): LaunchRequest {
            if (intent == null) return None
            when (intent.getStringExtra(Notifier.EXTRA_ACTION)) {
                Notifier.ACTION_ROLLBACK -> return Rollback
                Notifier.ACTION_EXPORT_ERROR_LOG -> return ExportErrorLog
            }
            val id = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
            return if (id != null && SESSION_ID.matches(id)) OpenSession(id) else None
        }
    }
}
