package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.util.lerp
import app.dshmobile.ui.theme.Sakura
import java.time.LocalDate
import java.time.Month
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Shared pieces for the startup looks (櫻紋陣 and the four in IntroStardust.kt, IntroPetalStorm.kt, IntroTunnel.kt and
// IntroOneStroke.kt). Everything here is a pure function of the clock and a fixed hash: build it once per size (in a remember
// keyed on the IntroScope), draw it from draw lambdas, and a frame allocates nothing.

/**
 * The season's touch every look may add (subtle): extra sakura in spring (March–April), snow in winter (December–February). The
 * idea of seasonal touches comes from KylinQ01/dsh-startup-animation (MIT); the months are our own.
 */
enum class IntroSeason {
    NONE, SAKURA, SNOW;

    companion object {
        fun of(date: LocalDate): IntroSeason = when (date.month) {
            Month.MARCH, Month.APRIL -> SAKURA
            Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> SNOW
            else -> NONE
        }
    }
}

/** The little sparkle every look uses: a four-point star; aqua always sits on a white halo (aqua straight on pink reads grey). */
internal fun DrawScope.introSparkle(path: Path, at: Offset, r: Float, color: Color, alpha: Float, rot: Float = 0f) {
    if (alpha <= 0f || r <= 0f) return
    withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(r, r, Offset.Zero) }) {
        if (color.copy(alpha = 1f) == Sakura.Aqua) withTransform({ scale(1.6f, 1.6f, Offset.Zero) }) {
            drawPath(path, Color.White.copy(alpha = (1.6f * alpha).coerceAtMost(1f)))
        }
        drawPath(path, color.copy(alpha = color.alpha * alpha))
    }
}

/** A soft white disc of radius 1 at the origin, scaled where it is drawn (blooms, glows). */
internal val IntroUnitBloom: Brush = Brush.radialGradient(
    0f to Color.White, .4f to Color.White.copy(alpha = .55f), 1f to Color.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)

/** The blush sky every look stands on when there is no picture (pink at the top and bottom, white light in the middle). */
internal fun introSky(h: Float): Brush = Brush.verticalGradient(
    0f to lerpColor(Sakura.Background, Sakura.Primary, .45f), .12f to lerpColor(Sakura.Background, Sakura.Primary, .28f),
    .34f to Sakura.Background, .60f to lerpColor(Sakura.Background, Sakura.White, .45f), .78f to Sakura.Background,
    1f to lerpColor(Sakura.Background, Sakura.Primary, .40f), startY = 0f, endY = h,
)

/**
 * The user's picture as the full-screen backdrop, with a slow camera push: [zoom] eases from [from] to 1 at the exit, where the
 * crop is exactly the home screen's (ContentScale.Crop with the 對齊 bias), so the iris opens onto the same picture with no jump.
 * Without a picture it is the blush sky. Optional [scrim]s keep type readable over busy art.
 */
internal class IntroBackdrop(val picture: ImageBitmap?, private val w: Float, private val h: Float, align: PictureAlign) {
    private val fx = .5f
    private val fy = (1f + align.bias) / 2f
    private val place = Placement()
    val sky: Brush = introSky(h)
    val hasPicture: Boolean get() = picture != null

    init { picture?.let { runCatching { it.prepareToDraw() } } }

    /** The push at [ms]: [from] at the start, eased to 1 by [settle] (normally the exit mark); 1 for reduced motion. */
    fun zoom(ms: Float, settle: Float, from: Float = 1.08f, reduced: Boolean = false): Float =
        if (reduced) 1f else lerp(from, 1f, seg(ms, 0f, settle, IntroEase.SineInOut))

    fun draw(scope: DrawScope, zoom: Float, alpha: Float = 1f) {
        val p = picture
        if (p == null) { scope.drawRect(sky, alpha = alpha); return }
        cover(p.width.toFloat(), p.height.toFloat(), w, h, zoom, fx, fy, fx * w, fy * h, place)
        scope.withTransform({ translate(place.ox, place.oy); scale(place.k, place.k, Offset.Zero) }) { drawImage(p, alpha = alpha) }
    }

    /** A blush veil from [from] (0..1 of the height, at [strength]) fading up to nothing [reach] higher: room for type. */
    fun scrim(from: Float, reach: Float, strength: Float = .85f): Brush = Brush.verticalGradient(
        0f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = strength),
        startY = (from - reach) * h, endY = from * h,
    )
}

/**
 * Drifting petals (or, with petals off, white and aqua sparkles), falling slowly on the wind: a look's ambient layer. [count]
 * should come from [introCount]. [draw] takes the run's ambient seconds (0 freezes it for reduced motion) and how strong the wind
 * is (1 = a calm drift).
 */
internal class IntroDrift(private val w: Float, private val h: Float, private val dp: Float, private val count: Int, private val fx: IntroFx, private val salt: Int = 0) {
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()

    fun draw(scope: DrawScope, amb: Float, alpha: Float = 1f, wind: Float = 1f) = with(scope) {
        if (alpha <= 0f) return@with
        val margin = 24f * dp
        val spanX = w + 2 * margin
        val spanY = h + 2 * margin
        val tr = 30f + amb * wind
        for (i in 0 until count) {
            val h0 = ih(i, 10 + salt)
            val r = (4.5f + 3f * ih(i, 11 + salt)) * dp
            val speed = .7f + .6f * h0
            var x = wrap(ih(i, 12 + salt) * spanX + 14f * dp * tr * speed, spanX) - margin
            val y = wrap(ih(i, 13 + salt) * spanY + 26f * dp * tr * speed, spanY) - margin
            val period = 3f + 2f * ih(i, 14 + salt)
            x += (10f + 10f * ih(i, 15 + salt)) * dp * sin(amb * 2f * PI.toFloat() / period + 6.28f * h0)
            val spin = (if (ih(i, 16 + salt) < .5f) -1f else 1f) * (40f + 60f * ih(i, 17 + salt)) * amb + 360f * ih(i, 18 + salt)
            if (!fx.petals) {
                introSparkle(sparkle, Offset(x, y), r * .8f, if (h0 < .6f) Sakura.White else Sakura.Aqua, .9f * alpha, spin * .3f)
                continue
            }
            val flip = cos(ih(i, 19 + salt) * 6.28f + (1.4f + 1.2f * ih(i, 20 + salt)) * amb)
            val color = if (ih(i, 21 + salt) < .6f) Sakura.Primary.copy(alpha = .88f) else Sakura.White.copy(alpha = .95f)
            withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(.72f * r * flip, r, Offset.Zero) }) {
                drawPath(petal, color.copy(alpha = color.alpha * alpha))
            }
        }
    }
}

/**
 * The season's extra layer ([IntroSeason]): a few small five-petal blossoms turning as they fall in spring (only with petals on),
 * soft snow in winter (a white disc with an aqua ring inside a white halo, so the aqua never touches the pink). [count] from
 * [introCount]; nothing in [IntroSeason.NONE].
 */
internal class IntroSeasonFx(private val w: Float, private val h: Float, private val dp: Float, season: IntroSeason, count: Int, fx: IntroFx) {
    private val kind = if (season == IntroSeason.SAKURA && !fx.petals) IntroSeason.NONE else season
    private val n = if (kind == IntroSeason.NONE) 0 else count
    private val blossom = sakuraSilhouette()
    val active: Boolean get() = n > 0

    fun draw(scope: DrawScope, amb: Float, alpha: Float = 1f) = with(scope) {
        if (n == 0 || alpha <= 0f) return@with
        val margin = 20f * dp
        val spanY = h + 2 * margin
        for (i in 0 until n) {
            val hh = ih(i, 140)
            val fall = (if (kind == IntroSeason.SNOW) 34f else 28f) * dp * (.7f + .6f * hh)
            val y = ((ih(i, 141) * spanY + amb * fall) % spanY) - margin
            val sway = (8f + 10f * ih(i, 142)) * dp * sin(amb * (.8f + .6f * hh) + 6.28f * hh)
            val x = ih(i, 143) * w + sway
            if (kind == IntroSeason.SNOW) {
                val r = (1.6f + 1.8f * ih(i, 144)) * dp
                drawCircle(Sakura.White.copy(alpha = .30f * alpha), r * 2.6f, Offset(x, y))
                drawCircle(Sakura.White.copy(alpha = .85f * alpha), r * 1.9f, Offset(x, y))
                drawCircle(Sakura.Aqua.copy(alpha = .9f * alpha), r * 1.3f, Offset(x, y))
                drawCircle(Sakura.White.copy(alpha = alpha), r, Offset(x, y))
            } else {
                val r = (5f + 3f * ih(i, 145)) * dp
                val spin = 360f * ih(i, 146) + (30f + 40f * hh) * amb
                withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(r, r * (.75f + .25f * cos(amb * 1.3f + 6.28f * hh)), Offset.Zero) }) {
                    drawPath(blossom, Sakura.Primary.copy(alpha = .85f * alpha))
                }
                drawCircle(Sakura.White.copy(alpha = .9f * alpha), .28f * r, Offset(x, y))
            }
        }
    }
}

private fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span
