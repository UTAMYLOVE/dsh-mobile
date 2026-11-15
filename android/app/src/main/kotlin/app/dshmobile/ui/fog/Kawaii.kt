package app.dshmobile.ui.fog

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import app.dshmobile.ui.theme.Sakura
import kotlin.math.cos
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
                    // The colour comes from the petal's own hash: `i % 3` picked the same petals as the kind did, so with all three
                    // kinds on every petal was white (the owner's "teeth").
                    color = if (r(9) < .35f) Sakura.White.copy(alpha = .9f) else Sakura.Primary.copy(alpha = .80f),
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

    /**
     * Where [p] is at time [t] (seconds, already paced), in screen fractions, plus its rotation, opacity factor and (for petals)
     * the 3D flip, -1..1, that slims and turns it over. With [sides] (a picture is shown) clouds keep to the outer fifths and fade
     * in there, so nothing floats over the picture's subject.
     */
    fun place(p: KawaiiParticle, t: Float, sides: Boolean = false): FloatArray {
        val wave = sin((t * .6f + p.phase * 6.2832f).toDouble()).toFloat()
        return when (p.kind) {
            KawaiiKind.CLOUD -> if (!sides) floatArrayOf(wrap(p.x + t * p.speed, -.3f, 1.3f), p.y, 0f, 1f, 1f) else {
                val u = wrap(p.x + t * p.speed, 0f, 1f)
                val x = if (p.phase < .5f) .2f - .5f * u else .8f + .5f * u
                floatArrayOf(x, p.y, 0f, (u / .15f).coerceIn(0f, 1f), 1f)
            }
            KawaiiKind.PETAL -> floatArrayOf(
                wrap(p.x + p.sway * wave + t * p.speed * .35f, -.05f, 1.05f), wrap(p.y + t * p.speed, -.05f, 1.05f), p.phase * 360f + t * p.spin, 1f,
                cos((t * (1.4f + 1.2f * p.phase) + p.phase * 6.28f).toDouble()).toFloat(),
            )
            KawaiiKind.SPARKLE -> {
                val twinkle = .5f + .5f * sin((t * 1.8f + p.phase * 6.2832f).toDouble()).toFloat()
                floatArrayOf(p.x, wrap(p.y - t * p.speed, -.05f, 1.05f), 0f, .25f + .75f * twinkle, 1f)
            }
            KawaiiKind.HEART -> {
                val y = wrap(p.y - t * p.speed, -.05f, 1.05f)
                floatArrayOf(p.x + p.sway * wave, y, 12f * wave, (y * 4f).coerceIn(0f, 1f), 1f)
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

    /**
     * A cloud of radius 1 around the origin as one shape: four puffs and a base, cut flat underneath (the anime cloud). Built as
     * one path so its alpha is applied once; four circles drawn separately doubled up where they overlapped (visible seams).
     */
    internal fun cloudPath(): Path {
        val puffs = Path().apply {
            addOval(Rect(Offset(-.55f, .1f), .55f))
            addOval(Rect(Offset(0f, -.15f), .7f))
            addOval(Rect(Offset(.6f, .12f), .5f))
            addOval(Rect(Offset(.2f, .3f), .38f))
        }
        val merged = Path().apply { op(puffs, Path().apply { addRect(Rect(-.99f, .15f, 1f, CLOUD_BASE)) }, PathOperation.Union) }
        return Path().apply { op(merged, Path().apply { addRect(Rect(-2f, -2f, 2f, CLOUD_BASE)) }, PathOperation.Intersect) }
    }

    /** Where a cloud's flat base sits in [cloudPath]'s units. */
    internal const val CLOUD_BASE = .42f

    /**
     * Halftone dots in the lower-left corner of a [w] × [h] screen, larger toward the corner, on a 45° grid of [pitch] px: one path
     * of circles, built once (the startup animation and the pink background share the motif).
     */
    internal fun halftone(w: Float, h: Float, pitch: Float, maxR: Float, minR: Float): Path = Path().apply {
        val reach = kotlin.math.hypot(.62f * w, .30f * h) * .85f
        var row = 0
        var y = h
        while (y > .70f * h - pitch) {
            var x = if (row % 2 == 0) 0f else pitch
            while (x < .62f * w + pitch) {
                val d = kotlin.math.hypot(x, h - y) / reach
                if (d < 1f) addOval(Rect(Offset(x, y), kotlin.math.max(minR, maxR * Math.pow((1f - d).toDouble(), 1.2).toFloat())))
                x += 2f * pitch
            }
            y -= pitch
            row++
        }
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
internal fun Modifier.kawaii(clock: State<Float>, particles: List<KawaiiParticle>, pace: Float, sides: Boolean = false): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.kawaii")
        val petal = Kawaii.petalPath()
        val sparkle = Kawaii.sparklePath()
        val heart = Kawaii.heartPath()
        val cloud = Kawaii.cloudPath()
        onDrawBehind {
            FogDebug.count("background.kawaii.draw")
            val t = clock.value * pace
            for (p in particles) drawParticle(p, Kawaii.place(p, t, sides), petal, sparkle, heart, cloud)
        }
    }

private fun DrawScope.drawParticle(p: KawaiiParticle, at: FloatArray, petal: Path, sparkle: Path, heart: Path, cloud: Path) {
    val x = at[0] * size.width
    val y = at[1] * size.height
    val r = p.size * density / 2f
    val alpha = at[3]
    if (alpha <= 0f) return
    when (p.kind) {
        KawaiiKind.CLOUD -> withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) {
            drawPath(cloud, p.color.copy(alpha = p.color.alpha * alpha))
            // A milk-tea band along the flat base and a faint rose-brown rim: the flat-bottomed anime cloud.
            val band = 8f * density / r
            clipPath(cloud) { drawRect(Sakura.MilkTea.copy(alpha = .30f * alpha), Offset(-2f, Kawaii.CLOUD_BASE - band), Size(4f, band)) }
            drawPath(cloud, Sakura.RoseBrown.copy(alpha = .18f * alpha), style = Stroke(1f * density / r))
        }
        else -> withTransform({
            translate(x, y)
            rotate(at[2], Offset.Zero)
            // Petals are slim (× .72) and turn over in 3D (the flip), so they read as petals, not teeth.
            if (p.kind == KawaiiKind.PETAL) scale(.72f * r * at[4], r, Offset.Zero) else scale(r, r, Offset.Zero)
        }) {
            val path = when (p.kind) { KawaiiKind.PETAL -> petal; KawaiiKind.SPARKLE -> sparkle; else -> heart }
            drawPath(path, p.color.copy(alpha = p.color.alpha * alpha))
        }
    }
}
