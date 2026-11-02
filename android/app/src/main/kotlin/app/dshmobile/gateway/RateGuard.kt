package app.dshmobile.gateway

import java.time.Clock

/** Sliding 60 s window per provider; a runaway agent loop gets refused instead of burning the key. */
class RateGuard(private val maxPerMinute: Int = 120, private val clock: Clock) {
    private val windows = Provider.entries.associateWith { ArrayDeque<Long>() }

    /** Counts the call and returns true, or returns false (without counting) when the window is full. */
    fun tryAcquire(p: Provider): Boolean {
        val window = windows.getValue(p)
        synchronized(window) {
            val now = clock.millis()
            while (window.isNotEmpty() && now - window.first() >= WINDOW_MS) window.removeFirst()
            if (window.size >= maxPerMinute) return false
            window.addLast(now)
            return true
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
