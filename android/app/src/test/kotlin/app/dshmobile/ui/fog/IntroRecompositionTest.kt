package app.dshmobile.ui.fog

import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every look reads its clock only in draw lambdas and graphicsLayer blocks, so a run recomposes a few times (once per beat that
 * adds or drops a screen-reader node), never once per frame: a frame that recomposes is the first thing that drops a mid-range
 * phone below 60 fps. Counted with the recomposer's own change counter over a 3 s run at 60 fps (about 180 frames).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroRecompositionTest {
    @get:Rule val rule = createComposeRule()

    private fun changes(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    private fun composesPerBeatNotPerFrame(style: IntroStyle, picture: Boolean = false) {
        rule.mainClock.autoAdvance = false
        val pic = if (picture) {
            android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFA5DEE4.toInt()) }.asImageBitmap()
        } else {
            null
        }
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                StartupIntro(Intro(enabled = true, name = "小雨", seconds = 3f, style = style), pic, false) {}
            }
        }
        // Past the picture's decision and the first frames, where the look composes its layers once.
        rule.mainClock.advanceTimeBy(64)
        val before = changes()
        repeat(170) { rule.mainClock.advanceTimeBy(16) }
        val frames = changes() - before
        assertTrue("$style recomposed on $frames of 170 frames", frames <= 12)
    }

    @Test fun sigil() = composesPerBeatNotPerFrame(IntroStyle.SIGIL)
    @Test fun stardust() = composesPerBeatNotPerFrame(IntroStyle.STARDUST)
    @Test fun stardustWithPicture() = composesPerBeatNotPerFrame(IntroStyle.STARDUST, picture = true)
    @Test fun petalStorm() = composesPerBeatNotPerFrame(IntroStyle.PETAL_STORM)
    @Test fun tunnel() = composesPerBeatNotPerFrame(IntroStyle.TUNNEL)
    @Test fun oneStroke() = composesPerBeatNotPerFrame(IntroStyle.ONE_STROKE)
    @Test fun oneStrokeWithPicture() = composesPerBeatNotPerFrame(IntroStyle.ONE_STROKE, picture = true)
}
