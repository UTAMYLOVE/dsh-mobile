package app.dshmobile.ui.fog

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * 「一筆描」 (One stroke), the startup look [IntroStyle.ONE_STROKE]: the 櫻紋 crest shows as an 8 % ghost, then each stroke wipes in,
 * 300 ms apart; the finished crest breathes a pink/white glow; a wide-tracked caption types in with a block cursor; the shortest
 * and lightest look. Ideas from yannicksong0106/dsh-550c-boot (MIT, the plugin's simple tier, as an idea only: write it from
 * scratch); nothing of its images, sounds or names.
 *
 * PLACEHOLDER (0.8.2 base): a simple working version (the backdrop with its slow push, drifting petals, the welcome sticker) so
 * the app builds and the picker works. The look itself is written in this file only, with its words in
 * res/values/strings_intro_one_stroke.xml (names starting intro_one_stroke_) and its tests in its own test file
 * (IntroOneStrokeTest.kt). The host ([IntroHost]) already gives it the capped clock, tap/back to skip, the exit iris, the
 * reduced-motion still, the 跳過 label and the welcome announcement; see IntroKit.kt, IntroSticker.kt, IntroType.kt and
 * [IntroPlan] for the shared parts.
 */
@Composable
internal fun OneStrokeIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    // In (fixed), the welcome (stretches with the 1.5–8 s setting), the exit iris (fixed).
    val plan = remember(intro.seconds) {
        IntroPlan.of(intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000f, IntroBeat.move(700f), IntroBeat.hold(1f, 200f), IntroBeat.move(380f))
    }
    val timing = remember(plan) { IntroTiming.of(plan, still = plan.end(1) - 1f, announce = plan.at(1)) }
    val line = introWelcomeLine(intro)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val dp = density.density
        val backdrop = remember(this) { IntroBackdrop(this.picture, widthPx, heightPx, align) }
        val drift = remember(this) { IntroDrift(widthPx, heightPx, dp, introCount(level, fx, 14, 10, 6, reduced), fx) }
        val season = remember(this) { IntroSeasonFx(widthPx, heightPx, dp, fx.season, introCount(level, fx, 10, 8, 4, reduced), fx) }
        val sticker = remember(this, line) {
            IntroSticker(IntroMeasure(measurer, dp, density.fontScale, 1f, coverage), line, widthPx - 48f * dp, 56f, 32f, dp)
        }
        val run = this.run
        val reduced = this.reduced
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val ms = run.ms
                backdrop.draw(this, backdrop.zoom(ms, plan.at(2), reduced = reduced))
                drift.draw(this, run.amb, seg(ms, 0f, 400f).let { if (reduced) 1f else it })
                season.draw(this, run.amb)
            },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind {
                drawSticker(sticker, size.width / 2f, size.height * .42f, run.ms, plan.at(0) + 260f * plan.pace, plan.pace, plan.pace < 1f, reduced, plan.at(2))
            },
        )
    }
}
