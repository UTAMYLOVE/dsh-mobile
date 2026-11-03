package app.dshmobile.ui.fog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches frames (fed from JankStats, on its own thread) and steps the effect level a cap down when the screen keeps dropping
 * them: every [windowFrames] frames count as one window (about a second at 120 Hz); a window with at least [jankyPerWindow] janky
 * frames is a bad one, and [windowsInARow] bad windows in a row lower [cap] by one level and start the count over. The cap only
 * goes down, until [reset] (the user picks a level) or the next process: stepping back up would make the effects flicker.
 */
class JankGovernor(
    private val windowFrames: Int = 120,
    private val jankyPerWindow: Int = 12,
    private val windowsInARow: Int = 2,
) {
    private val _cap = MutableStateFlow(EffectLevel.FULL)
    val cap: StateFlow<EffectLevel> = _cap

    /** Test hook: false only counts and never degrades (the CI perf gate pins the level at FULL with it). */
    @Volatile var autoDegrade: Boolean = true

    /** Called (on the frame thread) after each step down, with the janky and total frames of the bad windows that caused it. */
    @Volatile var onDegrade: ((cap: EffectLevel, janky: Int, frames: Int) -> Unit)? = null

    /** Told after every change of [cap], including [reset]; the [EffectsController] keeps its effective level in step with it. */
    @Volatile internal var onCapChanged: (() -> Unit)? = null

    /** The user's chosen level: what is on screen is the thriftier of it and [cap], and a step goes one below that. */
    @Volatile var floor: EffectLevel = EffectLevel.FULL

    /** Frames still to ignore after [onStart]: the hitch of coming to the front is not the screen's smoothness. */
    private var warmup = 0

    private var inWindow = 0
    private var jankyInWindow = 0
    private var badStreak = 0
    private var streakJanky = 0

    /** Frames and janky frames seen since the process started (not cleared by [reset]); a screen's own share is the difference. */
    @Volatile var frames: Long = 0L
        private set
    @Volatile var janky: Long = 0L
        private set

    /** The screen was started (tracking turns on): the first [WARMUP_FRAMES] frames and any half window are dropped. */
    fun onStart() {
        synchronized(this) {
            warmup = WARMUP_FRAMES
            inWindow = 0
            jankyInWindow = 0
            badStreak = 0
            streakJanky = 0
        }
    }

    fun onFrame(isJank: Boolean) {
        var stepped: Triple<EffectLevel, Int, Int>? = null
        synchronized(this) {
            if (warmup > 0) {
                warmup--
                return
            }
            frames++
            if (isJank) {
                janky++
                jankyInWindow++
            }
            if (++inWindow < windowFrames) return
            val bad = jankyInWindow >= jankyPerWindow
            if (bad) {
                badStreak++
                streakJanky += jankyInWindow
            } else {
                badStreak = 0
                streakJanky = 0
            }
            if (badStreak >= windowsInARow) {
                // One below what is really on screen; nothing to do (and nothing to log) when that is already the last level.
                val shown = effectiveLevel(floor, _cap.value)
                val next = EffectLevel.entries.getOrNull(shown.ordinal + 1)
                if (autoDegrade && next != null) {
                    _cap.value = next
                    stepped = Triple(next, streakJanky, badStreak * windowFrames)
                }
                badStreak = 0
                streakJanky = 0
            }
            inWindow = 0
            jankyInWindow = 0
        }
        stepped?.let { (level, janky, total) ->
            onCapChanged?.invoke()
            onDegrade?.invoke(level, janky, total)
        }
    }

    /** Back to FULL with the window and the streak cleared. */
    fun reset() {
        synchronized(this) {
            _cap.value = EffectLevel.FULL
            inWindow = 0
            jankyInWindow = 0
            badStreak = 0
            streakJanky = 0
        }
        onCapChanged?.invoke()
    }
}

private const val WARMUP_FRAMES = 30

/** The thriftier of the user's choice and the cap. */
fun effectiveLevel(user: EffectLevel, cap: EffectLevel): EffectLevel = if (cap.ordinal > user.ordinal) cap else user
