package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the user's own 粉嫩二次元 choices ask of the intro's particles (petals, sparkles, how many), and the season's touch. */
data class IntroFx(val petals: Boolean = true, val sparkles: Boolean = true, val scale: Float = 1f, val season: IntroSeason = IntroSeason.NONE) {
    companion object {
        /** From the Look: the KAWAII style's 花瓣 / 星光 / 數量 apply; the cosy style keeps the defaults. The season is [today]'s. */
        fun of(look: Look, today: java.time.LocalDate = java.time.LocalDate.now()): IntroFx {
            val season = IntroSeason.of(today)
            return if (look.style != BackdropStyle.KAWAII) IntroFx(season = season)
            else IntroFx(look.petals, look.sparkles, (look.amount / 50f).coerceIn(0f, 1.4f), season)
        }
    }
}

/**
 * Every particle of the intro as a pure function of time and a fixed hash (no Random, no per-frame allocation): five far stars,
 * the drifting mid petals, a few soft bokeh discs, the petal burst at 核驗通過 and the motes rising from the floor circle.
 */
internal class IntroParticles(private val sc: IntroScene) {
    private val L = sc.lay
    private val dp = L.dp
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()

    // Far stars: placed by the hash, pushed out of the sigil.
    private val starX = FloatArray(5)
    private val starY = FloatArray(5)
    private val starR = FloatArray(5) { (2f + 2f * ih(it, 3)) * dp }
    private val stars = if (sc.fx.sparkles) 5 else 0

    init {
        for (i in 0 until 5) {
            var x = (.08f + .84f * ih(i, 1)) * L.w
            var y = (.10f + .48f * ih(i, 2)) * L.h
            val dx = x - L.c.x
            val dy = y - L.c.y
            val d = hypot(dx, dy)
            val min = 1.1f * L.r
            if (d < min) {
                val k = if (d < 1f) 1f else min / d
                x = L.c.x + (if (d < 1f) min else dx * k)
                y = L.c.y + (if (d < 1f) 0f else dy * k)
            }
            starX[i] = x
            starY[i] = y
        }
    }

    private val mid = if (sc.reduced) minOf(6, (10 * sc.fx.scale).roundToInt()) else (10 * sc.fx.scale).roundToInt()
    private val bokeh = when {
        sc.reduced -> 3
        sc.level == EffectLevel.FULL -> 4
        sc.level == EffectLevel.BALANCED -> 2
        else -> 0
    }
    private val motes = if (sc.level == EffectLevel.SAVER || !sc.fx.sparkles) 0 else 8
    /** The season's few extra blossoms or snowflakes, behind the type like the other petals. */
    private val season = IntroSeasonFx(L.w, L.h, dp, sc.fx.season, introCount(sc.level, sc.fx, 12, 9, 5, sc.reduced), sc.fx)
    private val burst = when (sc.level) { EffectLevel.FULL -> 18; EffectLevel.BALANCED -> 12; EffectLevel.SAVER -> 8 }

    /** How far the wind has carried things by [ms] (seconds at speed 1): slow before the hit, a gust after it, a gust on exit. */
    fun travel(ms: Float, exitAt: Float, exitDur: Float): Float {
        val m = sc.marks
        if (sc.reduced) return 30f
        val t = ms / 1000f
        val hit = m.hit / 1000f
        val gust = .7f * m.pace
        var tr = 30f + .45f * minOf(t, hit)
        if (t > hit) {
            val x = (minOf(t - hit, gust) / gust)
            tr += gust * (x + 1.2f * (1f - (1f - x).pow(4)) / 4f)
            if (t > hit + gust) tr += t - hit - gust
        }
        if (exitDur > 0f && ms > exitAt) {
            val x = ((ms - exitAt) / exitDur).coerceIn(0f, 1f)
            tr += exitDur / 1000f * 2.2f * x.pow(4) / 4f
        }
        return tr
    }

    fun drawStars(scope: DrawScope, ms: Float, amb: Float) {
        val a = seg(ms, 100f, 500f)
        if (a <= 0f) return
        for (i in 0 until stars) {
            val h = ih(i, 4)
            val tw = if (sc.reduced) .6f else .15f + .85f * (.5f + .5f * sin(amb * (1.1f + 1.6f * h) + 6.28f * h)).pow(6)
            // All white: a tiny aqua star twinkling over the pink sky blurs into grey at its thin tips.
            val color = Sakura.White.copy(alpha = .95f)
            sc.drawSparkle(scope, Offset(starX[i], starY[i]), starR[i], color, a * tw)
        }
    }

    /** Mid petals, bokeh, the season's touch and (in the welcome) motes; the burst is drawn by [drawBurst]. */
    fun drawAmbient(scope: DrawScope, ms: Float, amb: Float, exitAt: Float, exitDur: Float) = with(scope) {
        val fade = if (sc.reduced) 1f else seg(ms, 0f, 400f)
        if (fade <= 0f) return@with
        // Over the user's picture the season stays at the sides' strength (.6), so no snowflake sits on the face.
        if (season.active) season.draw(this, if (sc.reduced) 7f else 7f + amb, fade * (if (sc.backdrop) .6f else .9f))
        val tr = travel(ms, exitAt, exitDur)
        val inh = sc.inhale(ms)
        val margin = 24f * dp
        val spanX = L.w + 2 * margin
        val spanY = L.h + 2 * margin
        for (i in 0 until bokeh) {
            val h = ih(i, 60)
            val size = (34f + 18f * h) * dp
            var x = wrap(ih(i, 61) * spanX + 6f * dp * tr * (.7f + .6f * h), spanX) - margin
            if (sc.backdrop) x = if (i % 2 == 0) x * .2f else L.w - (L.w - x) * .2f
            val y = wrap(ih(i, 62) * spanY + 10f * dp * tr * (.7f + .6f * h), spanY) - margin
            withTransform({ translate(x, y); scale(size / 2f, size / 2f, Offset.Zero) }) {
                drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = (.16f + .08f * ih(i, 63)) * fade)
            }
        }
        for (i in 0 until mid) {
            val h = ih(i, 10)
            val r = (4.5f + 3f * ih(i, 11)) * dp
            val speed = .7f + .6f * h
            var x = wrap(ih(i, 12) * spanX + 14f * dp * tr * speed, spanX) - margin
            var y = wrap(ih(i, 13) * spanY + 26f * dp * tr * speed, spanY) - margin
            val period = 3f + 2f * ih(i, 14)
            x += (10f + 10f * ih(i, 15)) * dp * sin(amb * 2f * PI.toFloat() / period + 6.28f * h)
            if (inh > 0f) {
                val dx = L.c.x - x
                val dy = L.c.y - y
                val d = hypot(dx, dy).coerceAtLeast(1f)
                x += dx / d * 8f * dp * inh
                y += dy / d * 8f * dp * inh
            }
            val spin = (if (ih(i, 16) < .5f) -1f else 1f) * (40f + 60f * ih(i, 17)) * amb + 360f * ih(i, 18)
            if (!sc.fx.petals) {
                sc.drawSparkle(this, Offset(x, y), r * .8f, if (h < .6f) Sakura.White else Sakura.Aqua, .9f * fade, spin * .3f)
                continue
            }
            val flip = cos(ih(i, 19) * 6.28f + (1.4f + 1.2f * ih(i, 20)) * amb)
            val color = if (ih(i, 21) < .6f) Sakura.Primary.copy(alpha = .88f) else Sakura.White.copy(alpha = .95f)
            withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(.72f * r * flip, r, Offset.Zero) }) {
                drawPath(petal, color.copy(alpha = color.alpha * fade))
            }
        }
        // Motes rising from the floor circle in the welcome.
        val start = sc.marks.hit + 600f * sc.marks.pace
        if (motes > 0 && ms > start && !sc.reduced) {
            val loop = .45f * L.h
            for (i in 0 until motes) {
                val h = ih(i, 80)
                val rise = (22f + 14f * h) * dp
                val travelled = ((ms - start) / 1000f * rise + ih(i, 81) * loop) % loop
                val prog = travelled / loop
                val a = sin(PI.toFloat() * prog)
                val ang = ih(i, 82) * 6.28f
                val rr = ih(i, 83)
                val x = L.cf.x + cos(ang) * L.rx * .8f * rr + 4f * dp * sin(amb * 2f + 6.28f * h)
                val y = L.cf.y + sin(ang) * L.ry * .8f * rr - travelled
                val ma = .8f * a * seg(ms, start, 300f)
                val mr = (1.2f + 1.3f * ih(i, 84)) * dp
                if (i >= 6) drawCircle(Sakura.White.copy(alpha = ma), mr + 1.25f * dp, Offset(x, y))
                drawCircle((if (i < 6) Sakura.White else Sakura.Aqua).copy(alpha = ma), mr, Offset(x, y))
            }
        }
    }

    /** The petal burst at the hit: ballistic arcs with drag and a little gravity, spinning and flipping, gone by 1.3 s. */
    fun drawBurst(scope: DrawScope, ms: Float) = with(scope) {
        val t = (ms - sc.marks.hit) / 1000f
        if (t <= 0f || t >= 1.3f || sc.reduced) return@with
        val k = 2.6f
        val drag = (1f - exp(-k * t)) / k
        val gx = .5f * 20f * dp * t * t
        val gy = .5f * 140f * dp * t * t
        val alpha = if (t < .6f) 1f else 1f - (t - .6f) / .7f
        for (i in 0 until burst) {
            val h = ih(i, 90)
            val th = 2f * PI.toFloat() * (i / burst.toFloat() + .35f * h)
            val dx = cos(th)
            val dy = sin(th)
            val v = (280f + 240f * h) * dp
            val x = L.c.x + dx * .55f * L.r + dx * v * drag + gx
            val y = L.c.y + dy * .55f * L.r + dy * v * drag + gy
            val r = (5f + 3f * ih(i, 91)) * dp
            val spin = (300f + 400f * ih(i, 92)) * (1f - exp(-1.5f * t)) / 1.5f + 360f * ih(i, 93)
            if (!sc.fx.petals) {
                sc.drawSparkle(this, Offset(x, y), r * .8f, if (h < .5f) Sakura.White else Sakura.Aqua, alpha, spin)
                continue
            }
            val flip = cos((6f + 4f * ih(i, 94)) * t + 6.28f * h)
            val color = if (ih(i, 95) < .6f) Sakura.Primary.copy(alpha = .92f) else Sakura.White.copy(alpha = .95f)
            withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(.72f * r * flip, r, Offset.Zero) }) {
                drawPath(petal, color.copy(alpha = color.alpha * alpha))
            }
        }
        if (t < .6f) {
            val s = 1f - t / .6f
            for (i in 0 until 6) {
                val h = ih(i, 96)
                val th = 2f * PI.toFloat() * (i / 6f + .5f * h)
                val v = (180f + 160f * h) * dp
                val x = L.c.x + cos(th) * (.55f * L.r + v * drag)
                val y = L.c.y + sin(th) * (.55f * L.r + v * drag)
                sc.drawSparkle(this, Offset(x, y), 4f * dp * s, Sakura.Aqua, 1f)
                sc.drawSparkle(this, Offset(x, y), 2f * dp * s, Color.White, 1f)
            }
        }
    }

    private fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span
}
