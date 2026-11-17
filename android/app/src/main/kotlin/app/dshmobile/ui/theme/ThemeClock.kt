package app.dshmobile.ui.theme

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import app.dshmobile.ui.fog.FogDebug
import app.dshmobile.ui.fog.FogMotion
import app.dshmobile.ui.fog.FogSwitches
import app.dshmobile.ui.fog.LocalPageActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * The one frame clock every theme drawing reads (R12, §2.4.4): seconds in [time], advanced by [ThemeClockDriver] only while
 * something holds it. A drawing reads [time] in its draw lambda only while it holds the clock itself (E5); otherwise it draws its
 * still, so a running mark never redraws a still backdrop. CLASSIC never uses it.
 */
@Stable
class ThemeClock internal constructor() {
    internal val source = mutableFloatStateOf(0f)

    /** Seconds of theme motion. Read it only in draw. */
    val time: State<Float> get() = source

    /** How many drawings hold the clock now; only the driver reads it (eng E19), so a hold or a release recomposes nothing. */
    internal var holders by mutableIntStateOf(0)

    /** How many of [holders] are the backdrop's own hold (for its frameMs and idlePause). */
    internal var backdropHolders by mutableIntStateOf(0)

    /** Counts a holder while [active]; [backdrop] marks the backdrop's own hold. */
    @Composable
    fun Hold(active: Boolean, backdrop: Boolean = false) {
        DisposableEffect(this, active, backdrop) {
            if (!active) return@DisposableEffect onDispose { }
            holders++
            if (backdrop) backdropHolders++
            onDispose {
                holders--
                if (backdrop) backdropHolders--
            }
        }
    }
}

/** The shell's clock (FogShell provides one); the default is a clock nothing drives, so a drawing outside a shell shows its still. */
val LocalThemeClock: ProvidableCompositionLocal<ThemeClock> = staticCompositionLocalOf { ThemeClock() }

/**
 * Drives [clock] (in FogShell next to rememberIdle). It ticks while LocalPageActive && !FogSwitches.plainBackground && something
 * holds the clock, looping on withInfiniteAnimationFrameNanos exactly as rememberFogClock does (anything else keeps Compose tests
 * from idling at FULL). Idle: .25× speed, one tick per 66 ms; if only the backdrop holds and [backdrop] has idlePause, it stops
 * until the screen wakes or another drawing holds it. While only the backdrop holds, ticks are at least its frameMs apart. The
 * holder count is read only here, through snapshotFlow inside the effect (eng E19). Counts FogDebug "clock.tick".
 */
@Composable
internal fun ThemeClockDriver(clock: ThemeClock, backdrop: ThemeBackdrop?, idle: State<Boolean>) {
    val running = LocalPageActive.current && !FogSwitches.plainBackground
    val frameMs = backdrop?.frameMs ?: 0L
    val idlePause = backdrop?.idlePause ?: false
    LaunchedEffect(clock, running, frameMs, idlePause, idle) {
        if (!running) return@LaunchedEffect
        snapshotFlow { clock.holders > 0 }.collectLatest { held -> if (held) clock.run(idle, frameMs, idlePause) }
    }
}

private suspend fun ThemeClock.run(idle: State<Boolean>, frameMs: Long, idlePause: Boolean) {
    var last = withInfiniteAnimationFrameNanos { it }
    while (true) {
        val onlyBackdrop = holders == backdropHolders
        if (idle.value) {
            if (onlyBackdrop && idlePause) {
                // Stopped, not slowed: wait for a touch or another holder, then start from that frame (no jump).
                snapshotFlow { !idle.value || holders != backdropHolders }.first { it }
                last = withInfiniteAnimationFrameNanos { it }
                continue
            }
            delay(FogMotion.IDLE_TICK_MS)
        }
        val now = withInfiniteAnimationFrameNanos { it }
        // The backdrop alone is capped at one tick per frameMs (neon's 30 fps); a frame too early is skipped, not counted.
        if (onlyBackdrop && frameMs > 0L && !idle.value && now - last < frameMs * 1_000_000L) continue
        source.floatValue += (now - last) / 1_000_000_000f * (if (idle.value) FogMotion.IDLE_SPEED else 1f)
        last = now
        FogDebug.count("clock.tick")
    }
}
