package app.dshmobile.service

/**
 * Decides whether dsh may be restarted after it died: [max] crashes inside a sliding [windowMs] window means give up.
 * [clock] returns milliseconds from any monotonic-enough source.
 */
class CrashPolicy(
    private val max: Int = 3,
    private val windowMs: Long = 600_000,
    private val clock: () -> Long,
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
