package app.dshmobile.ui.fog

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import app.dshmobile.ui.theme.Sakura
import kotlin.math.roundToInt
import kotlin.math.sin

/** What floats over the 粉嫩二次元 background. */
enum class KawaiiKind { CLOUD, PETAL, SPARKLE, HEART }

/**
 * One floating thing, all in screen fractions and seconds so it fits any screen: [x], [y] where it starts, [size] in dp, [speed]
 * its own pace, [sway] how far it rocks sideways, [spin] degrees per second, [phase] where in its cycle it starts.
 */
data class KawaiiParticle(
    val kind: KawaiiKind,
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val sway: Float,
    val spin: Float,
    val phase: Float,
    val color: Color,
)

object Kawaii {
    /** The most floating things at 數量 100 (clouds are counted apart: at most [MAX_CLOUDS]). */
    const val MAX_PARTICLES = 40
    const val MAX_CLOUDS = 4

    /** A small fixed sequence instead of Random, so the same settings always draw the same picture (and tests can count). */
    private fun hash(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777216f
    }

    /** The particles [look] asks for: none for the cosy style or at 數量 0. */
    fun particles(look: Look): List<KawaiiParticle> {
        if (look.style != BackdropStyle.KAWAII || look.amount <= 0) return emptyList()
        val kinds = listOfNotNull(
            KawaiiKind.PETAL.takeIf { look.petals }, KawaiiKind.SPARKLE.takeIf { look.sparkles }, KawaiiKind.HEART.takeIf { look.hearts },
        )
        val out = ArrayList<KawaiiParticle>()
        if (look.clouds) {
            val clouds = (MAX_CLOUDS * look.amount / 100f).roundToInt().coerceIn(1, MAX_CLOUDS)
            repeat(clouds) { i ->
                out += KawaiiParticle(
                    KawaiiKind.CLOUD, x = hash(i, 1), y = .04f + .3f * hash(i, 2) + if (i % 2 == 1) .55f else 0f,
                    size = 70f + 50f * hash(i, 3), speed = .006f + .006f * hash(i, 4), sway = 0f, spin = 0f, phase = hash(i, 5),
                    color = Sakura.White.copy(alpha = .55f),
                )
            }
        }
        if (kinds.isEmpty()) return out
        val total = (MAX_PARTICLES * look.amount / 100f).roundToInt().coerceAtLeast(kinds.size)
        repeat(total) { i ->
            val kind = kinds[i % kinds.size]
            val r = { salt: Int -> hash(i + 100, salt) }
            out += when (kind) {
                KawaiiKind.PETAL -> KawaiiParticle(
                    kind, r(1), r(2), size = 12f + 10f * r(3), speed = .035f + .03f * r(4), sway = .025f + .03f * r(5),
                    spin = (if (r(6) < .5f) -1 else 1) * (40f + 70f * r(7)), phase = r(8),
                    color = if (i % 3 == 0) Sakura.White.copy(alpha = .9f) else Sakura.Primary.copy(alpha = .75f),
                )
                KawaiiKind.SPARKLE -> KawaiiParticle(
                    kind, r(1), r(2), size = 7f + 9f * r(3), speed = .006f + .01f * r(4), sway = 0f, spin = 0f, phase = r(8),
                    color = if (i % 2 == 0) Sakura.White else Sakura.Aqua,
                )
                else -> KawaiiParticle(
                    kind, r(1), r(2), size = 10f + 10f * r(3), speed = .02f + .02f * r(4), sway = .015f + .02f * r(5), spin = 0f, phase = r(8),
                    color = if (i % 2 == 0) Sakura.Primary.copy(alpha = .7f) else Sakura.White.copy(alpha = .85f),
                )
                // CLOUD is never in kinds
            }
        }
        return out
    }

    /** 速度 0..100 as a time multiplier: 0 barely moves, 40 (the default) is calm, 100 is lively. */
    fun pace(speed: Int): Float = .15f + 2.2f * (speed.coerceIn(0, 100) / 100f)

    /** Where [p] is at time [t] (seconds, already paced), in screen fractions, plus its rotation and opacity factor. */
    fun place(p: KawaiiParticle, t: Float): FloatArray {
        val wave = sin((t * .6f + p.phase * 6.2832f).toDouble()).toFloat()
        return when (p.kind) {
            KawaiiKind.CLOUD -> floatArrayOf(wrap(p.x + t * p.speed, -.3f, 1.3f), p.y, 0f, 1f)
            KawaiiKind.PETAL -> floatArrayOf(wrap(p.x + p.sway * wave + t * p.speed * .35f, -.05f, 1.05f), wrap(p.y + t * p.speed, -.05f, 1.05f), p.phase * 360f + t * p.spin, 1f)
            KawaiiKind.SPARKLE -> {
                val twinkle = .5f + .5f * sin((t * 1.8f + p.phase * 6.2832f).toDouble()).toFloat()
                floatArrayOf(p.x, wrap(p.y - t * p.speed, -.05f, 1.05f), 0f, .25f + .75f * twinkle)
            }
            KawaiiKind.HEART -> {
                val y = wrap(p.y - t * p.speed, -.05f, 1.05f)
                floatArrayOf(p.x + p.sway * wave, y, 12f * wave, (y * 4f).coerceIn(0f, 1f))
            }
        }
    }

    private fun wrap(v: Float, from: Float, to: Float): Float {
        val span = to - from
        return from + (((v - from) % span) + span) % span
    }

    /** A sakura petal 2 x 2 around the origin: rounded, with the little notch at its tip. */
    internal fun petalPath(): Path = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-1.1f, .45f, -.9f, -.75f, -.18f, -1f)
        lineTo(0f, -.72f)
        lineTo(.18f, -1f)
        cubicTo(.9f, -.75f, 1.1f, .45f, 0f, 1f)
        close()
    }

    /** A four-pointed sparkle 2 x 2 around the origin. */
    internal fun sparklePath(): Path = Path().apply {
        moveTo(0f, -1f)
        quadraticTo(.12f, -.12f, 1f, 0f)
        quadraticTo(.12f, .12f, 0f, 1f)
        quadraticTo(-.12f, .12f, -1f, 0f)
        quadraticTo(-.12f, -.12f, 0f, -1f)
        close()
    }

    /** A heart 2 x 2 around the origin. */
    internal fun heartPath(): Path = Path().apply {
        moveTo(0f, .9f)
        cubicTo(-1.3f, .05f, -.85f, -1.05f, 0f, -.42f)
        cubicTo(.85f, -1.05f, 1.3f, .05f, 0f, .9f)
        close()
    }

}

/**
 * The 粉嫩二次元 floating things in a layer of their own (a clock tick re-records only this layer), drawn at [clock] seconds
 * times [Kawaii.pace]. With the clock stopped (保守 effects or the system's reduced motion) they stay where they are.
 */
internal fun Modifier.kawaii(clock: State<Float>, particles: List<KawaiiParticle>, pace: Float): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.kawaii")
        val petal = Kawaii.petalPath()
        val sparkle = Kawaii.sparklePath()
        val heart = Kawaii.heartPath()
        onDrawBehind {
            FogDebug.count("background.kawaii.draw")
            val t = clock.value * pace
            for (p in particles) drawParticle(p, Kawaii.place(p, t), petal, sparkle, heart)
        }
    }

private fun DrawScope.drawParticle(p: KawaiiParticle, at: FloatArray, petal: Path, sparkle: Path, heart: Path) {
    val x = at[0] * size.width
    val y = at[1] * size.height
    val r = p.size * density / 2f
    val alpha = at[3]
    if (alpha <= 0f) return
    when (p.kind) {
        KawaiiKind.CLOUD -> {
            val c = p.color.copy(alpha = p.color.alpha * alpha)
            drawCircle(c, r * .55f, Offset(x - r * .55f, y + r * .1f))
            drawCircle(c, r * .7f, Offset(x, y - r * .15f))
            drawCircle(c, r * .5f, Offset(x + r * .6f, y + r * .12f))
            drawCircle(c, r * .38f, Offset(x + r * .2f, y + r * .3f))
        }
        else -> withTransform({
            translate(x, y)
            rotate(at[2], Offset.Zero)
            scale(r, r, Offset.Zero)
        }) {
            val path = when (p.kind) { KawaiiKind.PETAL -> petal; KawaiiKind.SPARKLE -> sparkle; else -> heart }
            drawPath(path, p.color.copy(alpha = p.color.alpha * alpha))
        }
    }
}
