package app.dshmobile.service

import android.os.SystemClock

/**
 * Decides whether dsh may be restarted after it died: [max] crashes inside a sliding [windowMs] window means give up.
 * [clock] returns milliseconds from a monotonic source: by default `SystemClock.elapsedRealtime` (counts deep sleep too, and
 * unlike the wall clock cannot jump when the user or the network sets the time); tests inject their own.
 */
class CrashPolicy(
    private val max: Int = 3,
    private val windowMs: Long = 600_000,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class Decision { RESTART, GIVE_UP }

    private val crashes = ArrayDeque<Long>()

    @Synchronized fun onCrash(): Decision {
        val now = clock()
        while (crashes.isNotEmpty() && now - crashes.first() >= windowMs) crashes.removeFirst()
        crashes.addLast(now)
        return if (crashes.size >= max) Decision.GIVE_UP else Decision.RESTART
    }

    @Synchronized fun reset() = crashes.clear()
}
