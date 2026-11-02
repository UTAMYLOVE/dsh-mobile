package app.dshmobile.update

import android.content.SharedPreferences
import app.dshmobile.log.ErrorLog
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.ZoneId

/**
 * Runs [check] at most once per calendar day in Asia/Taipei (the first heartbeat of the day) and calls [onAvailable] when it
 * finds a newer release. The day is remembered in [prefs] only after the check ran to the end (an answer of "nothing new"
 * counts); a failure is logged with its class name only and the next heartbeat tries again. Nothing thrown here ever reaches
 * the caller, apart from cancellation.
 */
class DailyUpdateCheck(
    private val prefs: SharedPreferences,
    private val check: suspend () -> ReleaseInfo?,
    private val onAvailable: (ReleaseInfo) -> Unit,
    private val errorLog: ErrorLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun runIfDue() {
        val today = clock.instant().atZone(TAIPEI).toLocalDate().toString()
        if (prefs.getString(KEY_LAST_CHECK_DAY, null) == today) return
        try {
            val release = check()
            prefs.edit().putString(KEY_LAST_CHECK_DAY, today).apply()
            if (release == null) {
                errorLog.append(TAG, "no newer release")
            } else {
                errorLog.append(TAG, "release ${release.versionName} (${release.versionCode}) is available")
                onAvailable(release)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "check failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val PREFS = "dshm_update"
        private const val KEY_LAST_CHECK_DAY = "last_check_day"
        private const val TAG = "update"
        private val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
