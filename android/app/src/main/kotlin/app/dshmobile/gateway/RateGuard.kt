package app.dshmobile.gateway

import java.time.Clock

/** Sliding 60 s window per provider; a runaway agent loop gets refused instead of burning the key. */
class RateGuard(private val maxPerMinute: Int = DEFAULT_PER_MINUTE, private val clock: Clock) {
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

    companion object {
        private const val WINDOW_MS = 60_000L
        const val DEFAULT_PER_MINUTE = 120

        /**
         * The limit for a start with up to [maxConcurrent] agents at once: 120 plus 20 per agent (32 agents: 760 a minute). A fixed
         * 120 tripped on ordinary parallel work (owner, 2026-11-05: 「大量代理人同時跑會觸發」); a stuck retry loop still hits it.
         */
        fun forAgents(maxConcurrent: Int): Int = DEFAULT_PER_MINUTE + 20 * maxConcurrent.coerceAtLeast(0)
    }
}
