package app.agency.core.metering

import java.time.Clock

/**
 * At most [maxPerMinute] calls in any sliding 60 s window, across every provider and the search: a runaway tool loop gets
 * refused instead of burning the key. A refused attempt is not counted.
 */
class CallBudget(private val maxPerMinute: Int = DEFAULT_PER_MINUTE, private val clock: Clock) {
    private val window = ArrayDeque<Long>()

    fun tryAcquire(): Boolean = synchronized(window) {
        val now = clock.millis()
        while (window.isNotEmpty() && now - window.first() >= WINDOW_MS) window.removeFirst()
        if (window.size >= maxPerMinute) return false
        window.addLast(now)
        true
    }

    companion object {
        const val DEFAULT_PER_MINUTE = 30
        private const val WINDOW_MS = 60_000L
    }
}
