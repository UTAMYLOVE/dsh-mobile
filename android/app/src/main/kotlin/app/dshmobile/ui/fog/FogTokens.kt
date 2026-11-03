package app.dshmobile.ui.fog

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/** Motion constants, taken from the owner's FOGCITY mockup (docs/design-reference). */
object FogMotion {
    val EaseOut: Easing = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val Spring: Easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)
    val Soft: Easing = CubicBezierEasing(.2f, .8f, .2f, 1f)

    const val PAGE_MS = 560
    const val SAVER_PAGE_MS = 200
    const val SWEEP_MS = 620
    const val PILL_MS = 550
    const val SHEET_MS = 550
    const val SCRIM_MS = 350
    const val RISE_MS = 600
    const val STAGGER_MS = 70
    const val STAGGER_BASE_MS = 120
    const val STAGGER_MAX = 6
    const val COUNT_MS = 950
    const val RING_MS = 1300
    const val RING_DELAY_MS = 200
    const val METER_MS = 1100
    const val IDLE_AFTER_MS = 8_000L
    const val IDLE_SPEED = 0.25f
}

object FogDimens {
    val Panel = 22.dp
    val Sheet = 30.dp
    val BubbleTail = 8.dp
    val SearchCard = 18.dp
    val Thumb = 16.dp
    val DockSide = 12.dp
    val DockHeight = 196.dp
    val HeaderHeight = 92.dp
    val Round = 48.dp
}
