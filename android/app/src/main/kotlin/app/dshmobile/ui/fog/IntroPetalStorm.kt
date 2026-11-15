// Adapted from bigfatorangecatjohn/opening-free (c) 2026 bigfatorangecatjohn, MIT License: the breaking wave (its three
// harmonics and the body grain, crest file and spray layout), the burst (a radial flash, two shock rings and 64 rays), the
// gather (a per-dot delay, flight and swirl), the three dotted rings with their comet and the 0.32 Hz breath, the 44-tick meter
// and the batched dot drawing. The flight onto the app's edges follows hbgdjb/dsh-boot-particles (c) 2026 hbgdjb, MIT License
// (a quota by length, the nearest segment that still has quota, then order by projection). Shapes, colours, words and timings
// here are our own; nothing of their images, sounds or names.
package app.dshmobile.ui.fog

import android.graphics.Typeface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「花吹雪聚形」 (Petal storm gathers), the startup look [IntroStyle.PETAL_STORM]: a wave of pink petal grains sweeps in from the
 * left and breaks in the middle (one haptic tick); the burst throws the grains out, and they swirl back and gather into the
 * 櫻紋 crest. Three dotted rings switch on dot by dot round it and breathe, and a meter under them runs to 100 %. The crest's
 * grains then re-form as your name, which lands as the usual sticker under 歡迎回來. At the end the petals fly to where the app's
 * top bar and dock will be, trace them in light, and the app develops exactly there while the sakura iris opens the rest.
 *
 * Beats ([PetalStormBeats]): the wave, the gather and the morph keep their own speed; the crest and the name holds stretch with
 * the 1.5–8 s setting. Below 2.5 s the crest is skipped and the grains gather straight into the name. Everything is a pure
 * function of the run's clock, read only in draw lambdas; the grains are drawn in a few batched point calls per frame.
 */
@Composable
internal fun PetalStormIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val beats = remember(intro.seconds) { PetalStormBeats.of(intro.seconds) }
    val timing = remember(beats) { IntroTiming.of(beats.plan, still = beats.still, hit = beats.burst, announce = beats.announce) }
    val line = introWelcomeLine(intro)
    val words = intro.welcome.trim().ifEmpty { stringResource(R.string.intro_welcome_default) }
    val name = intro.name.trim()
    val gathering = stringResource(R.string.intro_petal_storm_gathering)
    val ready = stringResource(R.string.intro_petal_storm_ready)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val context = LocalContext.current
    val coverage = IntroFonts.coverage(context.resources)
    // The handwriting as a platform typeface, only to trace the name's outlines once (the grains land on them).
    val pen = remember(context) { runCatching { context.resources.getFont(R.font.intro_hand) }.getOrNull() }
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        // 歡迎回來 (or the user's words) above the name; with no name the words themselves are the sticker.
        val brow = if (name.isEmpty()) null else words
        val scene = remember(this, beats, brow, name, gathering, ready) {
            PetalStormScene(this, beats, measurer, coverage, pen, brow, name.ifEmpty { words }, gathering, ready)
        }
        val run = this.run
        run.sceneOrigin = scene.focus
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBackdrop(this, run.ms, run.amb) })
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawStage(this, run) })
        // Before the exit the grains sit under the type (they gather behind the name sticker) ...
        Spacer(Modifier.fillMaxSize().drawBehind { if (!run.exiting) scene.drawPetals(this, run) })
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind { scene.drawType(this, run.ms, run.amb) })
        // No layer of its own: the cut has to reach the host's own layer to show the app underneath (see drawDevelop).
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawDevelop(this, run) })
        // ... and on exit over the developed app, on its edges.
        Spacer(Modifier.fillMaxSize().drawBehind { if (run.exiting) scene.drawPetals(this, run) })
    }
}

/** How many grains for [level]: about 1,500 on FULL, 1,000 on BALANCED and 600 on SAVER (and for the still); the user's 數量 thins them down to 60 %. */
internal fun petalStormCount(level: EffectLevel, fx: IntroFx, reduced: Boolean): Int {
    val base = if (reduced) 600 else when (level) { EffectLevel.FULL -> 1500; EffectLevel.BALANCED -> 1000; EffectLevel.SAVER -> 600 }
    // Never more than the level allows (that is the frame budget), and never so few that the crest and the name stop reading.
    return (base * fx.scale.coerceIn(.6f, 1f)).roundToInt().coerceIn(600, 1500)
}

/**
 * The beats of one run of 「花吹雪聚形」 and the moments derived from them, in ms on the run's clock:
 * the wave (it breaks at [burst]), the gather into the crest, the crest hold, the morph into the name, the name hold, the flight
 * to the app's edges ([landStart]) and the exit. A [compact] run (below 2.5 s) has no crest: the grains gather straight into the
 * name. Only the two holds stretch with the length, so 8 s waits longer instead of moving slower.
 */
internal class PetalStormBeats private constructor(val plan: IntroPlan, val compact: Boolean) {
    val pace: Float get() = plan.pace
    val gather = 1
    val crest = if (compact) -1 else 2
    val morph = if (compact) -1 else 3
    val name = if (compact) 2 else 4
    val land = name + 1
    val exit = name + 2

    /** The wave breaks (the haptic tick). */
    val burst: Float = plan.end(0)
    val gatherStart: Float = plan.at(gather)
    val gatherLen: Float = plan.length(gather)
    private val toName = if (compact) gather else morph
    /** The beat in which the grains fly into the name: its start, length and end. */
    val nameFrom: Float = plan.at(toName)
    val nameLen: Float = plan.length(toName)
    val nameFormed: Float = plan.end(toName)
    /** The crest is complete (the grains are home); [compact] runs have none. */
    val crestLock: Float = gatherStart + .90f * gatherLen
    /** The name sticker starts to stamp in while the last grains still arrive. */
    val stickerStart: Float = nameFrom + .55f * nameLen
    /** The welcome line is announced once the name is on screen. */
    val announce: Float = stickerStart + 160f * pace
    /** The rings switch on, dot by dot, while the crest gathers. */
    val ringStart: Float = gatherStart + .40f * gatherLen
    val morphStart: Float = if (compact) -1f else plan.at(morph)
    val morphLen: Float = if (compact) 0f else plan.length(morph)
    val landStart: Float = plan.at(land)
    val landLen: Float = plan.length(land)
    val exitStart: Float = plan.at(exit)
    /** The reduced-motion frame: the welcome, settled, just before the petals would fly off. */
    val still: Float = plan.end(name) - 1f

    fun isHold(i: Int): Boolean = i == crest || i == name

    private val meterAt: FloatArray
    private val meterValue: FloatArray

    init {
        if (compact) {
            meterAt = floatArrayOf(0f, burst, nameFormed)
            meterValue = floatArrayOf(0f, .20f, 1f)
        } else {
            meterAt = floatArrayOf(0f, burst, plan.end(gather), plan.end(crest), nameFormed)
            meterValue = floatArrayOf(0f, .16f, .46f, .62f, 1f)
        }
    }

    /** The meter, 0..1: it steps up with each beat and reaches 100 % when the name has formed. */
    fun meter(ms: Float): Float {
        if (ms <= 0f) return 0f
        for (i in 1 until meterAt.size) {
            if (ms < meterAt[i]) {
                val k = seg(ms, meterAt[i - 1], meterAt[i] - meterAt[i - 1], IntroEase.SineInOut)
                return lerp(meterValue[i - 1], meterValue[i], k)
            }
        }
        return 1f
    }

    /**
     * A clock (s) that runs at full speed while things move and at 15 % during the holds: the petals' spin and flip use it, so a
     * petal tumbles in flight and only sways once it has landed on the crest or the name.
     */
    fun motion(ms: Float): Float {
        var m = 0f
        for (b in 0 until plan.size) {
            val a = plan.at(b)
            if (ms <= a) break
            m += (min(ms, plan.end(b)) - a) * (if (isHold(b)) .15f else 1f)
        }
        return m / 1000f
    }

    companion object {
        /** Below this the crest is skipped (its beats did not fit without rushing). */
        const val COMPACT_BELOW_MS = 2500f

        fun of(seconds: Float): PetalStormBeats {
            val total = seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000f
            return if (total < COMPACT_BELOW_MS) {
                PetalStormBeats(
                    IntroPlan.of(total, IntroBeat.move(420f), IntroBeat.move(640f), IntroBeat.hold(1f, 160f), IntroBeat.move(320f), IntroBeat.move(280f)),
                    compact = true,
                )
            } else {
                PetalStormBeats(
                    IntroPlan.of(
                        total,
                        IntroBeat.move(480f), IntroBeat.move(760f), IntroBeat.hold(1f, 100f), IntroBeat.move(560f),
                        IntroBeat.hold(1.6f, 300f), IntroBeat.move(380f), IntroBeat.move(340f),
                    ),
                    compact = false,
                )
            }
        }
    }
}

/** One straight piece of the app's edges, in px. */
internal class PetalEdge(val ax: Float, val ay: Float, val bx: Float, val by: Float) {
    val dx: Float = bx - ax
    val dy: Float = by - ay
    val length: Float = hypot(dx, dy)

    /** Where the point's foot falls along the piece, 0..1. */
    fun project(px: Float, py: Float): Float = if (length <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / (length * length)).coerceIn(0f, 1f)

    fun distance2(px: Float, py: Float): Float {
        val t = project(px, py)
        val qx = ax + dx * t - px
        val qy = ay + dy * t - py
        return qx * qx + qy * qy
    }
}

/**
 * Where the app's own edges are when the intro ends: the simple top bar's bottom (FogShell's SimpleBar, 62 dp under the status
 * bar) and the floating dock's outline (12 dp from the sides and the bottom, 68 dp tall, 30 dp corners). Read from the shell's
 * own measurements, so the petals land where the app then appears.
 */
internal object PetalEdges {
    const val HEADER_DP = 62f
    const val DOCK_SIDE_DP = 12f
    const val DOCK_BOTTOM_DP = 12f
    const val DOCK_HEIGHT_DP = 68f
    const val DOCK_CORNER_DP = 30f

    fun headerY(insetTop: Float, dp: Float): Float = insetTop + HEADER_DP * dp

    fun dock(w: Float, h: Float, insetBottom: Float, dp: Float): Rect {
        val bottom = h - insetBottom - DOCK_BOTTOM_DP * dp
        return Rect(DOCK_SIDE_DP * dp, bottom - DOCK_HEIGHT_DP * dp, w - DOCK_SIDE_DP * dp, bottom)
    }

    /** The edges as straight pieces: the bar's bottom in four, the dock's rounded outline with six pieces per corner. */
    fun segments(w: Float, h: Float, dp: Float, insetTop: Float, insetBottom: Float): List<PetalEdge> {
        val out = ArrayList<PetalEdge>()
        val y = headerY(insetTop, dp)
        val l = DOCK_SIDE_DP * dp
        val r = w - DOCK_SIDE_DP * dp
        for (i in 0 until 4) out += PetalEdge(lerp(l, r, i / 4f), y, lerp(l, r, (i + 1) / 4f), y)
        val d = dock(w, h, insetBottom, dp)
        val c = min(DOCK_CORNER_DP * dp, min(d.width, d.height) / 2f)
        // Clockwise from the top-left corner's end: top, top-right arc, right, bottom-right arc, bottom, and so on.
        val corners = arrayOf(
            floatArrayOf(d.right - c, d.top + c, 270f), floatArrayOf(d.right - c, d.bottom - c, 0f),
            floatArrayOf(d.left + c, d.bottom - c, 90f), floatArrayOf(d.left + c, d.top + c, 180f),
        )
        val straights = arrayOf(
            floatArrayOf(d.left + c, d.top, d.right - c, d.top), floatArrayOf(d.right, d.top + c, d.right, d.bottom - c),
            floatArrayOf(d.right - c, d.bottom, d.left + c, d.bottom), floatArrayOf(d.left, d.bottom - c, d.left, d.top + c),
        )
        for (k in 0 until 4) {
            val s = straights[k]
            if (hypot(s[2] - s[0], s[3] - s[1]) > .5f) out += PetalEdge(s[0], s[1], s[2], s[3])
            val (cx, cy, from) = Triple(corners[k][0], corners[k][1], corners[k][2])
            for (j in 0 until 6) {
                val a0 = (from + 15f * j) * (PI.toFloat() / 180f)
                val a1 = (from + 15f * (j + 1)) * (PI.toFloat() / 180f)
                out += PetalEdge(cx + c * cos(a0), cy + c * sin(a0), cx + c * cos(a1), cy + c * sin(a1))
            }
        }
        return out
    }

    /**
     * Gives each of the [n] points ([px], [py]) a place on [edges], written to [ex], [ey]: each piece gets a quota by its length;
     * the points nearest the edges choose first, each the nearest piece that still has room; on each piece its points keep their
     * order along it and spread evenly. So every point lands close by, in order, and no stretch of edge stays bare.
     */
    fun allocate(px: FloatArray, py: FloatArray, n: Int, edges: List<PetalEdge>, ex: FloatArray, ey: FloatArray) {
        if (n == 0 || edges.isEmpty()) return
        val total = edges.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(1f)
        val quota = IntArray(edges.size) { (n * edges[it].length / total).toInt() }
        var left = n - quota.sum()
        val byFraction = edges.indices.sortedByDescending { (n * edges[it].length / total) - quota[it] }
        var j = 0
        while (left > 0) { quota[byFraction[j % edges.size]]++; left--; j++ }
        val nearest = FloatArray(n) { i -> edges.minOf { it.distance2(px[i], py[i]) } }
        val order = (0 until n).sortedBy { nearest[it] }
        val pick = IntArray(n)
        for (i in order) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (s in edges.indices) {
                if (quota[s] <= 0) continue
                val d2 = edges[s].distance2(px[i], py[i])
                if (d2 < bestD) { bestD = d2; best = s }
            }
            pick[i] = best
            quota[best]--
        }
        for (s in edges.indices) {
            val e = edges[s]
            val members = (0 until n).filter { pick[it] == s }.sortedBy { e.project(px[it], py[it]) }
            for ((k, i) in members.withIndex()) {
                val t = (k + .5f) / members.size
                ex[i] = e.ax + e.dx * t
                ey[i] = e.ay + e.dy * t
            }
        }
    }
}

/**
 * Round dots drawn in batches: every dot of a frame goes into one buffer and comes out as one platform point call per colour,
 * size and alpha step (a few dozen calls instead of a thousand). Sizes are a few fixed radii, alphas twelve steps (±.04, below
 * what a dot this small shows). Colours draw in [PetalStormScene.PALETTE] order, so white halos are always under the aqua.
 */
private class DotBatch(capacity: Int, private val radii: FloatArray) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val bins = IntArray(capacity)
    private val pts = FloatArray(capacity * 2)
    private val start = IntArray(BINS + 1)
    private val cursor = IntArray(BINS + 1)
    private val rgb = IntArray(COLOURS) { PetalStormScene.PALETTE[it].toArgb() and 0xFFFFFF }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var n = 0

    fun add(x: Float, y: Float, size: Int, alpha: Float, colour: Int) {
        if (alpha <= .02f || n >= xs.size) return
        val a = (alpha.coerceAtMost(1f) * ALPHAS).toInt().coerceIn(0, ALPHAS - 1)
        xs[n] = x
        ys[n] = y
        bins[n] = (colour * SIZES + size.coerceIn(0, SIZES - 1)) * ALPHAS + a
        n++
    }

    fun flush(scope: DrawScope) {
        if (n == 0) return
        start.fill(0)
        for (i in 0 until n) start[bins[i] + 1]++
        for (b in 0 until BINS) start[b + 1] += start[b]
        start.copyInto(cursor)
        for (i in 0 until n) {
            val at = cursor[bins[i]]++
            pts[2 * at] = xs[i]
            pts[2 * at + 1] = ys[i]
        }
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (b in 0 until BINS) {
                val from = start[b]
                val to = start[b + 1]
                if (from == to) continue
                val a = b % ALPHAS
                val size = (b / ALPHAS) % SIZES
                val colour = b / (ALPHAS * SIZES)
                paint.color = (((a + .5f) / ALPHAS * 255f).roundToInt() shl 24) or rgb[colour]
                paint.strokeWidth = 2f * radii[size]
                nc.drawPoints(pts, from * 2, (to - from) * 2, paint)
            }
        }
        n = 0
    }

    companion object {
        const val COLOURS = 7
        const val SIZES = 5
        const val ALPHAS = 12
        const val BINS = COLOURS * SIZES * ALPHAS
    }
}

/** The wave at one moment: its head and tail (px), its alpha and the clock in seconds for the harmonics. */
private class WaveState {
    var head = 0f
    var tail = 0f
    var span = 1f
    var alpha = 0f
    var t = 0f
}

/**
 * One run's frame-independent state, built once per size: the layout, every grain's targets (the wave, the crest, the name and
 * the app's edges), the paths, brushes and measured type. The draw functions read only this and the clock, so a frame allocates
 * nothing and lays out no text.
 */
internal class PetalStormScene(
    sc: IntroScope,
    private val beats: PetalStormBeats,
    measurer: TextMeasurer,
    coverage: IntArray,
    pen: Typeface?,
    eyebrow: String?,
    stickerText: String,
    gathering: String,
    ready: String,
) {
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val dp = sc.density.density
    private val level = sc.level
    private val reduced = sc.reduced
    private val fx = sc.fx
    private val unit = max(1f, min(w, h))
    private val pace = beats.pace

    // ---- layout: the app's edges, and the scene between them --------------------------------------------------------------
    private val headerY = PetalEdges.headerY(sc.insetTop, dp)
    private val dock = PetalEdges.dock(w, h, sc.insetBottom, dp)
    private val spanTop = headerY + 12f * dp
    private val spanBottom = dock.top - 12f * dp
    private val ringR = minOf(.37f * w, 160f * dp, .31f * (spanBottom - spanTop)).coerceAtLeast(40f * dp)
    val focus = Offset(w / 2f, spanTop + .42f * (spanBottom - spanTop))
    private val crestR = .62f * ringR
    /** Where the wave's head is when it breaks: its curl then sits over the middle. (Declared before init, which uses it.) */
    private val breakX = .50f * w - .035f * unit
    private val s = (min(w / dp / 412f, h / dp / 915f)).coerceIn(.78f, 1.1f)

    // ---- type ------------------------------------------------------------------------------------------------------------------
    private val mm = IntroMeasure(measurer, dp, sc.density.fontScale, s, coverage)
    private val sticker = IntroSticker(mm, stickerText, w - 56f * dp, 72f, 34f, dp)
    private val brow: GlyphRun? = eyebrow?.let { mm.fit(it, IntroFonts.hand(22f), .06f, 22f, 16f, w - 64f * dp) }
    /**
     * Its white edge, stroked twice: a stroke this wide round a contour as small as the dot of 辶 turns its inner edge inside
     * out and leaves a hole beside the ink (ink on the picture's aqua read grey there); the narrower second stroke fills it.
     */
    private val browOutline: Stroke? = brow?.let { Stroke(stickerStroke(it, .16f).width, join = StrokeJoin.Round, cap = StrokeCap.Round) }
    private val browOutlineInner: Stroke? = browOutline?.let { Stroke(it.width * .55f, join = StrokeJoin.Round, cap = StrokeCap.Round) }
    private val browGap = 4f * dp
    private val blockH = (brow?.let { it.height + browGap } ?: 0f) + sticker.height
    private val browTop = focus.y - blockH / 2f + 2f * dp
    val stickerTop: Float = browTop + (brow?.let { it.height + browGap } ?: 0f)
    private val captionGather = mm.run(gathering, IntroFonts.hand(15f, small = true), .06f)
    private val captionReady = mm.run(ready, IntroFonts.hand(15f, small = true), .06f)
    private val digits = mm.run("0123456789%", IntroFonts.latin(12f, FontWeight.SemiBold), 0f)
    private val digitSlot = (0 until 10).maxOf { digits.advance[it] }
    private val tickW = 2.2f * dp
    private val tickH = 11f * dp
    private val tickGap = 3f * dp
    private val ticksW = TICKS * tickW + (TICKS - 1) * tickGap
    private val counterW = 3 * digitSlot + digits.advance[10]
    private val meterW = ticksW + 10f * dp + counterW
    private val captionTop = focus.y + ringR + 22f * dp
    private val ticksTop = captionTop + captionGather.height + 8f * dp
    private val meterLeft = (w - meterW) / 2f

    // ---- the backdrop and the season ---------------------------------------------------------------------------------------
    private val backdrop = IntroBackdrop(sc.picture, w, h, sc.align)
    private val season = IntroSeasonFx(w, h, dp, fx.season, introCount(level, fx, 10, 8, 4, reduced), fx)
    private val drift = IntroDrift(w, h, dp, introCount(level, fx, 12, 8, 5, reduced), fx, salt = 7)
    /**
     * Over the user's picture: a frost, strongest round the circle, so the storm reads over busy art. It lifts while the petals
     * fly to the edges, so the picture is back, clear, as the app appears. White with only a hint of blush: a blush veil over the
     * picture's aqua sky blended to grey.
     */
    private val veil: Brush? = if (sc.picture == null) null else lerpColor(Sakura.White, Sakura.Background, .35f).let { frost ->
        Brush.radialGradient(
            0f to frost.copy(alpha = .86f), .45f to frost.copy(alpha = .72f), 1f to frost.copy(alpha = .42f),
            center = focus, radius = .75f * max(w, h),
        )
    }

    // ---- paths, brushes, strokes -------------------------------------------------------------------------------------------
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()
    private val silhouette = sakuraSilhouette()
    private val wavePath = Path()
    private val trace = Path().apply {
        moveTo(PetalEdges.DOCK_SIDE_DP * dp, headerY)
        lineTo(w - PetalEdges.DOCK_SIDE_DP * dp, headerY)
        addRoundRect(androidx.compose.ui.geometry.RoundRect(dock, CornerRadius(min(PetalEdges.DOCK_CORNER_DP * dp, dock.height / 2f))))
    }
    private val traceGlow = Stroke(5f * dp, cap = StrokeCap.Round)
    private val traceCore = Stroke(1.4f * dp, cap = StrokeCap.Round)
    /** The wave's still-water line: low enough that its curl breaks at [focus], where the crest then gathers. */
    private val by = focus.y + unit * .17f
    private val waveBody: Brush = Brush.verticalGradient(
        0f to Sakura.Primary.copy(alpha = .66f), .5f to lerpColor(Sakura.Primary, Sakura.Background, .45f).copy(alpha = .52f),
        1f to Sakura.Background.copy(alpha = 0f), startY = by - unit * (WAVE_AMP + .03f), endY = by + unit * .16f,
    )
    private val waveLip: Brush = Brush.verticalGradient(
        0f to Sakura.White.copy(alpha = .95f), .6f to Sakura.Primary.copy(alpha = .90f), 1f to Sakura.Primary.copy(alpha = .3f),
        startY = by - unit * (WAVE_AMP + .05f), endY = by,
    )
    private val lipStroke = Stroke(2.2f * dp, cap = StrokeCap.Round)
    private val rippleStroke = Stroke(.9f * dp)
    private val unitPink: Brush = Brush.radialGradient(
        0f to Sakura.Primary.copy(alpha = .9f), 1f to Sakura.Primary.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
    )
    private val unitFlash: Brush = Brush.radialGradient(
        0f to Sakura.White, .32f to lerpColor(Sakura.White, Sakura.Primary, .55f).copy(alpha = .75f), 1f to Sakura.Primary.copy(alpha = 0f),
        center = Offset.Zero, radius = 1f,
    )
    private val crestInk: Brush = Brush.radialGradient(
        0f to Sakura.White, .55f to Sakura.White.copy(alpha = .95f), .85f to lerpColor(Sakura.Background, Sakura.White, .3f),
        1f to lerpColor(Sakura.Background, Sakura.Primary, .25f), center = Offset.Zero, radius = 1f,
    )
    private val crestLine = Stroke(2f * dp / crestR)
    private val crestInner = Stroke(1f * dp / crestR)
    private val crestVein = Stroke(1.1f * dp / crestR, cap = StrokeCap.Round)
    private val hairline = Stroke(1f * dp)
    /** The shock rings' strokes, a few fixed widths (a frame picks one; none is built per frame). */
    private val shock = Array(8) { Stroke((.6f + 3f * it / 7f) * dp) }
    private val headerCut: Brush = (headerY + 16f * dp).let { end ->
        Brush.verticalGradient(0f to Color.Black, (headerY / end) to Color.Black, 1f to Color.Transparent, startY = 0f, endY = end)
    }
    private val browPaint = Paint()

    // ---- the grains ------------------------------------------------------------------------------------------------------
    private val n = petalStormCount(level, fx, reduced)
    private val nBody = (n * .70f).toInt()
    private val nLip = (n * .25f).toInt()
    private val spriteKind = when {
        fx.petals -> 1
        fx.sparkles -> 2
        else -> 0
    }
    private val spriteCount = if (spriteKind == 0) 0 else when (if (reduced) EffectLevel.SAVER else level) {
        EffectLevel.FULL -> 120; EffectLevel.BALANCED -> 80; EffectLevel.SAVER -> 48
    }
    private val radii = floatArrayOf(.55f * dp, .8f * dp, 1.1f * dp, 1.5f * dp, 2f * dp)
    /** Over a picture pink grains get white rims ([HALO]). */
    private val rimmed = sc.picture != null
    private val dots = DotBatch(n * 2 + 1200, radii)
    private val rays = when (level) { EffectLevel.FULL -> 48; EffectLevel.BALANCED -> 36; EffectLevel.SAVER -> 24 }

    // The wave's own layout per grain (where along the wave, how high in it), and where it was when the wave broke.
    private val ws1 = FloatArray(n) { ih(it, 51) }
    private val ws2 = FloatArray(n) { ih(it, 52) }
    private val ws3 = FloatArray(n) { ih(it, 53) }
    /** Each wave grain's fixed place along the wave (k) and depth in it (v), so a frame does not raise them to powers again. */
    private val wk = FloatArray(n) { i ->
        when {
            i < nBody -> 1f - (1f - (i + .5f) / nBody).pow(1.7f)
            i < nBody + nLip -> 1f - (1f - (i - nBody + .5f) / nLip).pow(2.4f)
            else -> 0f
        }
    }
    private val wv = FloatArray(n) { ws1[it].pow(1.35f) }
    private val w0x = FloatArray(n)
    private val w0y = FloatArray(n)
    private val w0c = ByteArray(n)
    private val w0a = FloatArray(n)
    private val w0s = ByteArray(n)
    private val kx = FloatArray(n)
    private val ky = FloatArray(n)
    private val gd = FloatArray(n) { ih(it, 61) }
    private val swirl = FloatArray(n) { ih(it, 62) * 2f - 1f }
    // The crest's and the name's place for each grain, with its colour, alpha and size there.
    private val cx = FloatArray(n)
    private val cy = FloatArray(n)
    private val cCol = ByteArray(n)
    private val cA = FloatArray(n)
    private val cS = ByteArray(n)
    private val nx = FloatArray(n)
    private val ny = FloatArray(n)
    private val nGlyph = IntArray(n)
    private val nCol = ByteArray(n)
    private val nA = FloatArray(n)
    private val nS = ByteArray(n)
    private val hx = FloatArray(n)
    private val hy = FloatArray(n)
    private val md = FloatArray(n) { ih(it, 71) }
    private val arc = FloatArray(n) { ih(it, 72) - .5f }
    // The app's edge each grain lands on.
    private val ex = FloatArray(n)
    private val ey = FloatArray(n)
    private val ld = FloatArray(n) { ih(it, 81) }
    private val lc = FloatArray(n) { ih(it, 82) - .5f }
    private val sprite = BooleanArray(n)
    private val spriteR = FloatArray(n) { (3.4f + 2.4f * ih(it, 91)) * dp }
    private val spriteSpin = FloatArray(n) { (if (ih(it, 92) < .5f) -1f else 1f) * (90f + 140f * ih(it, 93)) }
    private val spriteFlip = FloatArray(n) { 6.28f * ih(it, 94) }
    private val spriteWhite = BooleanArray(n) { ih(it, 95) < .55f }
    // Per frame: the sprites' places (drawn after the dots).
    private val spX = FloatArray(n)
    private val spY = FloatArray(n)
    private val spA = FloatArray(n)
    private var spN = 0
    private val spIdx = IntArray(n)

    // The grain being evaluated (written by [grain], read right after): no object per grain per frame.
    private var qx = 0f
    private var qy = 0f
    private var qa = 0f
    private var qc = 0
    private var qs = 0
    private val waveNow = WaveState()
    private val waveFrom = WaveState()

    // Stamp timing of the sticker (the same as drawSticker's), so each name grain puffs out as its own glyph lands.
    private val stampStep: Float
    private val glyphCount: Int

    init {
        glyphCount = sticker.lines.sumOf { it.size }
        val stagger = (if (beats.compact) 24f else if (!sticker.latin) 40f else 20f) * pace
        stampStep = if (glyphCount > 1) min(stagger, 240f * pace / (glyphCount - 1)) else 0f
        buildWaveStart()
        val perm = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val j = (ih(i, 77) * (i + 1)).toInt().coerceIn(0, i)
            val t = perm[i]; perm[i] = perm[j]; perm[j] = t
        }
        buildCrest(perm)
        buildName(perm, pen)
        // Each grain's halo: most sit just round their stroke (under the sticker's white edge), some drift further out as dust.
        for (i in 0 until n) {
            val far = ih(i, 73) < .38f
            val dist = (if (far) 16f + 40f * ih(i, 74) else 3f + 10f * ih(i, 74)) * dp
            val ox = nx[i] - focus.x
            val oy = ny[i] - (stickerTop + sticker.height / 2f)
            val l = hypot(ox, oy).coerceAtLeast(1f)
            val a = 6.2832f * ih(i, 75)
            val dx = .55f * ox / l + .45f * cos(a)
            val dy = .55f * oy / l + .45f * sin(a)
            val dl = hypot(dx, dy).coerceAtLeast(.01f)
            hx[i] = dx / dl * dist
            hy[i] = dy / dl * dist
        }
        val fromX = FloatArray(n) { nx[it] + hx[it] }
        val fromY = FloatArray(n) { ny[it] + hy[it] }
        PetalEdges.allocate(fromX, fromY, n, PetalEdges.segments(w, h, dp, sc.insetTop, sc.insetBottom), ex, ey)
        // The sprites: spread evenly through the grains (every k-th), so petals ride every part of the wave and the shapes.
        if (spriteCount > 0) {
            val every = max(1, n / spriteCount)
            var k = 0
            var i = (every / 2)
            while (i < n && k < spriteCount) { sprite[i] = true; i += every; k++ }
        }
    }

    // ---- the wave -------------------------------------------------------------------------------------------------------

    private fun setWave(ms: Float, out: WaveState) {
        out.t = ms / 1000f
        val post = 620f * pace
        if (ms < beats.burst) {
            val sweep = (ms / beats.burst.coerceAtLeast(1f)).coerceIn(0f, 1f)
            out.head = lerp(-.30f * w, breakX, sweep * sweep * (3f - 2f * sweep))
            out.alpha = (ms / 140f).coerceIn(0f, 1f)
        } else {
            val k = ((ms - beats.burst) / post).coerceIn(0f, 1f)
            out.head = lerp(breakX, 1.46f * w, k)
            out.alpha = 1f - k
        }
        out.tail = out.head - WAVE_LENGTH * w
        out.span = max(1f, out.head - out.tail)
    }

    /** x in the source's desktop pixels (a 1080 px tall screen), so its harmonics keep their wavelength on a phone. */
    private fun xu(x: Float) = x / unit * 1080f
    private fun reach(x: Float, ws: WaveState) = ((x - ws.tail) / ws.span).coerceIn(0f, 1f)
    private fun amp(x: Float, ws: WaveState) = unit * (.005f + WAVE_AMP * reach(x, ws).pow(1.25f))
    private fun crestY(x: Float, ws: WaveState): Float {
        val k = reach(x, ws)
        val u = xu(x)
        val t = ws.t
        return by - amp(x, ws) + sin(u * .0082f + t * 1.15f) * unit * .024f * (.3f + k) +
            sin(u * .0181f - t * 1.85f + .8f) * unit * .010f * (.22f + k) + sin(u * .0410f + t * 2.60f + 1.7f) * unit * .0028f
    }
    private fun troughY(x: Float, ws: WaveState): Float {
        val k = reach(x, ws)
        val u = xu(x)
        val t = ws.t
        return by + amp(x, ws) * .55f + sin(u * .0077f + t * 1.15f + 1.3f) * unit * .019f * (.3f + k) +
            sin(u * .0197f - t * 1.95f + 2.1f) * unit * .0072f * (.22f + k)
    }

    /** A source radius (desktop px) as one of the batch's size steps. */
    private fun sizeOf(rCss: Float): Int = when {
        rCss < .7f -> 0
        rCss < .95f -> 1
        rCss < 1.3f -> 2
        rCss < 1.75f -> 3
        else -> 4
    }

    /** Grain [i] as part of the wave at [ws]: the body (the first 70 %), the crest file along the lip (25 %) and the spray. */
    private fun waveGrain(i: Int, ws: WaveState) {
        val t = ws.t
        val s1 = ws1[i]
        val s2 = ws2[i]
        val s3 = ws3[i]
        if (i < nBody) {
            val k = wk[i]
            val x = ws.tail + ws.span * k + sin(t * .9f + s2 * 6.2832f) * unit * .006f + (s3 - .5f) * unit * .01f
            val v = wv[i]
            val c = crestY(x, ws)
            qx = x
            qy = c + (troughY(x, ws) - c) * v + sin(t * 1.6f + s2 * 6.2832f + xu(x) * .01f) * unit * .004f
            val tw = .5f + .5f * sin(t * 2.4f + s3 * 6.2832f)
            val light = s1 > .66f
            qa = (ws.alpha * (.18f + .5f * (1f - v) * (.35f + .65f * k)) * (.55f + .45f * tw)).coerceAtMost(1f)
            qc = if (light) WHITE else PINK
            qs = sizeOf((.5f + s2 * 1.6f) * (.6f + .5f * k))
        } else if (i < nBody + nLip) {
            val k = wk[i]
            val x = ws.tail + ws.span * k + sin(t * 1.4f + s2 * 6.2832f) * unit * .004f
            qx = x
            qy = crestY(x, ws) + (s1 - .5f) * unit * .02f * (.35f + k)
            val tw = .45f + .55f * sin(t * 3.1f + s1 * 6.2832f)
            qa = (ws.alpha * (.2f + .75f * k) * tw).coerceIn(0f, 1f)
            qc = WHITE
            qs = sizeOf(.6f + s2 * 2.1f * (.35f + k))
        } else {
            val phase = (t * .52f + s1) % 1f
            // Thrown off anywhere along the last stretch of the lip (all from its tip read as a beak), up and forward, falling.
            val ox = ws.head - unit * .10f * s1
            qx = ox + unit * (.04f + .12f * s1) * phase
            qy = crestY(ox, ws) - unit * (.03f + .08f * s1) * phase + unit * .16f * phase * phase
            qa = ws.alpha * (1f - phase) * (phase / .2f).coerceAtMost(1f) * .8f
            qc = AQUA
            qs = sizeOf(.7f + (1f - phase) * 1.8f)
        }
    }

    /** Where each grain was when the wave broke, and the burst's kick that throws it out from there. */
    private fun buildWaveStart() {
        val ws = WaveState()
        setWave(beats.burst, ws)
        // At the break the head is at full strength (the post-burst fade starts from here).
        ws.alpha = 1f
        for (i in 0 until n) {
            waveGrain(i, ws)
            w0x[i] = qx
            w0y[i] = qy
            w0c[i] = qc.toByte()
            w0a[i] = qa
            w0s[i] = qs.toByte()
            val dx = qx - focus.x
            val dy = qy - focus.y
            val l = hypot(dx, dy)
            var a = if (l > unit * .02f) atan2(dy, dx) else 6.2832f * ih(i, 63)
            a += (ih(i, 64) - .5f) * .7f
            val mag = unit * (.08f + .42f * ih(i, 65).pow(1.6f))
            kx[i] = cos(a) * mag
            ky[i] = sin(a) * mag
        }
    }

    // ---- the crest and the name as targets --------------------------------------------------------------------------------

    /**
     * The 櫻紋 crest as [n] points: the five-petal outline (bright, even along its length), a halftone fill (a sunflower spiral,
     * so the dots are evenly spaced), five veins and the small centre ring. Sorted by x and handed out by [perm].
     */
    private fun buildCrest(perm: IntArray) {
        val px = FloatArray(n)
        val py = FloatArray(n)
        val tone = ByteArray(n)
        val nOut = (n * .34f).toInt()
        val nVein = (n * .10f).toInt()
        val nRing = (n * .08f).toInt()
        val nFill = n - nOut - nVein - nRing
        var k = 0
        // Outline: the silhouette's polar curve, measured, then sampled evenly by length.
        val ox = FloatArray(361)
        val oy = FloatArray(361)
        val cum = FloatArray(361)
        for (d in 0..360) {
            val r = sakuraRadius(d % 360)
            val th = d * (PI.toFloat() / 180f)
            ox[d] = r * cos(th)
            oy[d] = r * sin(th)
            if (d > 0) cum[d] = cum[d - 1] + hypot(ox[d] - ox[d - 1], oy[d] - oy[d - 1])
        }
        var seg = 1
        for (j in 0 until nOut) {
            val at = (j + .5f) / nOut * cum[360]
            while (seg < 360 && cum[seg] < at) seg++
            val u = ((at - cum[seg - 1]) / (cum[seg] - cum[seg - 1]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            val inward = 1f - .03f * ih(j, 101)
            px[k] = lerp(ox[seg - 1], ox[seg], u) * inward
            py[k] = lerp(oy[seg - 1], oy[seg], u) * inward
            tone[k] = T_OUTLINE
            k++
        }
        for (j in 0 until nVein) {
            val petalIdx = j % 5
            val along = (j / 5 + .5f) / ((nVein + 4) / 5)
            val r = lerp(.24f, .60f, along)
            val th = (270f + 72f * petalIdx) * (PI.toFloat() / 180f)
            px[k] = r * cos(th)
            py[k] = r * sin(th)
            tone[k] = T_VEIN
            k++
        }
        for (j in 0 until nRing) {
            val th = (j + .5f) / nRing * 6.2832f
            px[k] = .15f * cos(th)
            py[k] = .15f * sin(th)
            tone[k] = T_RING
            k++
        }
        // Fill: a sunflower spiral over the disc, kept inside the petals and outside the centre ring.
        var m = nFill * 2
        var got: Int
        val fx0 = FloatArray(nFill)
        val fy0 = FloatArray(nFill)
        while (true) {
            got = 0
            val cand = ArrayList<Long>()
            for (j in 0 until m) {
                val rr = sqrt((j + .5f) / m)
                val th = j * 2.399963f
                val deg = (((th * 180f / PI.toFloat()) % 360f) + 360f) % 360f
                if (rr > .23f && rr < sakuraRadius(deg.toInt() % 360) * .88f) cand += (rr.toBits().toLong() shl 32) or (th.toBits().toLong() and 0xFFFFFFFFL)
            }
            if (cand.size >= nFill || m > nFill * 16) {
                // An even subset when there are more candidates than places.
                for (j in 0 until nFill) {
                    if (cand.isEmpty()) break
                    val c = cand[(j.toLong() * cand.size / nFill).toInt().coerceIn(0, cand.size - 1)]
                    val rr = Float.fromBits((c ushr 32).toInt())
                    val th = Float.fromBits((c and 0xFFFFFFFFL).toInt())
                    fx0[got] = rr * cos(th)
                    fy0[got] = rr * sin(th)
                    got++
                }
                break
            }
            m = (m * 1.6f).toInt()
        }
        for (j in 0 until nFill) {
            if (j < got) { px[k] = fx0[j]; py[k] = fy0[j] } else { px[k] = ox[(j * 7) % 360] * .95f; py[k] = oy[(j * 7) % 360] * .95f }
            tone[k] = T_FILL
            k++
        }
        val order = (0 until n).sortedBy { px[it] + .002f * ih(it, 102) }
        for (i in 0 until n) {
            val src = order[perm[i]]
            cx[i] = focus.x + px[src] * crestR
            cy[i] = focus.y + py[src] * crestR
            when (tone[src]) {
                T_OUTLINE -> { cCol[i] = PINK.toByte(); cA[i] = .95f; cS[i] = 3 }
                T_VEIN -> { cCol[i] = ROSE.toByte(); cA[i] = .75f; cS[i] = 1 }
                T_RING -> { cCol[i] = ROSE.toByte(); cA[i] = .85f; cS[i] = 2 }
                else -> if (ih(i, 103) < .5f) { cCol[i] = PINK.toByte(); cA[i] = .55f; cS[i] = 2 } else { cCol[i] = WHITE.toByte(); cA[i] = .9f; cS[i] = 2 }
            }
        }
    }

    /**
     * The name as [n] points on its glyphs' own outlines, traced once from the handwriting with the platform's text path (the
     * thin pen's two edges lie so close that the dots draw the stroke). Points are spread evenly by length over every glyph, so a
     * long name gets as dense a line as a short one. A glyph with no outline (an emoji, or no platform font in a test) gets a
     * small frame round its box instead.
     */
    private fun buildName(perm: IntArray, pen: Typeface?) {
        val polys = ArrayList<FloatArray>()
        val owner = ArrayList<Int>()
        val step = .8f * dp
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { if (pen != null) typeface = pen }
        var seq = 0
        for ((li, line) in sticker.lines.withIndex()) {
            val left = focus.x - line.width / 2f
            val base = sticker.baseline(stickerTop, li)
            paint.textSize = line.fontPx
            for (g in 0 until line.size) {
                val text = line.text[g]
                val ox = left + line.x[g]
                val oy = base - line.baseline + line.dy[g]
                if (text.isBlank()) { seq++; continue }
                var found = false
                if (pen != null && !line.emoji[g]) {
                    runCatching {
                        val path = android.graphics.Path()
                        paint.getTextPath(text, 0, text.length, 0f, line.glyphs[g].firstBaseline, path)
                        val pm = android.graphics.PathMeasure(path, false)
                        val pos = FloatArray(2)
                        do {
                            val len = pm.length
                            if (len > 2f * dp) {
                                val m = (len / step).toInt().coerceAtLeast(2)
                                val poly = FloatArray(m * 2)
                                for (j in 0 until m) {
                                    pm.getPosTan(len * j / m, pos, null)
                                    poly[2 * j] = ox + pos[0]
                                    poly[2 * j + 1] = oy + pos[1]
                                }
                                polys += poly
                                owner += seq
                                found = true
                            }
                        } while (pm.nextContour())
                    }
                }
                if (!found) {
                    // A frame round the glyph's ink box: its advance, from the baseline up .72 em.
                    val gw = line.advance[g] * .8f
                    val gh = .72f * line.fontPx
                    val x0 = ox + line.advance[g] * .1f
                    val y0 = oy + line.glyphs[g].firstBaseline - gh
                    val m = ((2 * (gw + gh)) / step).toInt().coerceAtLeast(8)
                    val poly = FloatArray(m * 2)
                    for (j in 0 until m) {
                        val d = (j.toFloat() / m) * 2 * (gw + gh)
                        val (x, y) = when {
                            d < gw -> Pair(x0 + d, y0)
                            d < gw + gh -> Pair(x0 + gw, y0 + d - gw)
                            d < 2 * gw + gh -> Pair(x0 + gw - (d - gw - gh), y0 + gh)
                            else -> Pair(x0, y0 + gh - (d - 2 * gw - gh))
                        }
                        poly[2 * j] = x
                        poly[2 * j + 1] = y
                    }
                    polys += poly
                    owner += seq
                }
                seq++
            }
        }
        val total = polys.sumOf { it.size / 2 }
        val px = FloatArray(n)
        val py = FloatArray(n)
        val gi = IntArray(n)
        if (total == 0) {
            for (i in 0 until n) { px[i] = focus.x; py[i] = focus.y }
        } else {
            // Even along the concatenated outlines: point j at (j + .5) / n of the way.
            var p = 0
            var base = 0
            for (j in 0 until n) {
                val at = ((j + .5f) / n * total).toInt().coerceIn(0, total - 1)
                while (p < polys.size - 1 && at >= base + polys[p].size / 2) { base += polys[p].size / 2; p++ }
                val local = (at - base).coerceIn(0, polys[p].size / 2 - 1)
                px[j] = polys[p][2 * local]
                py[j] = polys[p][2 * local + 1]
                gi[j] = owner[p]
            }
        }
        val order = (0 until n).sortedBy { px[it] + .002f * ih(it, 104) }
        for (i in 0 until n) {
            val src = order[perm[i]]
            nx[i] = px[src]
            ny[i] = py[src]
            nGlyph[i] = gi[src]
            if (ih(i, 105) < .55f) { nCol[i] = ROSE.toByte(); nA[i] = .9f; nS[i] = 2 } else { nCol[i] = PINK.toByte(); nA[i] = .95f; nS[i] = 2 }
        }
    }

    // ---- one grain at one moment ----------------------------------------------------------------------------------------

    private fun breath(amb: Float) = .5f + .5f * sin(6.2832f * .32f * (amb - .9f) - 1.1f)

    /** How far grain [i]'s glyph has puffed it out into the halo (0 until its glyph lands). */
    private fun puff(i: Int, ms: Float): Float {
        if (reduced) return 1f
        val at = beats.stickerStart + stampStep * nGlyph[i] + 40f * pace
        return IntroEase.ExpoOut.transform(((ms - at) / (520f * pace)).coerceIn(0f, 1f))
    }

    /**
     * Grain [i] before it leaves for the app's edges: in the wave, thrown out by the burst and swirling into the crest (or the
     * name when compact), resting there breathing, flowing from the crest into the name and puffing out round it as the sticker
     * lands. Written to qx, qy, qa, qc, qs.
     */
    private fun grain(i: Int, ms: Float, amb: Float, ws: WaveState) {
        if (ms < beats.burst) { waveGrain(i, ws); return }
        val b = breath(amb)
        // The first shape's place now: the crest (breathing) or, compact, the name.
        var tx: Float
        var ty: Float
        var tCol: Int
        var tA: Float
        var tS: Int
        if (beats.compact) {
            val p = puff(i, ms)
            tx = nx[i] + hx[i] * p + drift(i, amb, true)
            ty = ny[i] + hy[i] * p + drift(i, amb, false)
            tCol = if (p > .5f) puffColour(i) else nCol[i].toInt()
            tA = lerp(nA[i], puffAlpha(i), p)
            tS = nS[i].toInt()
        } else {
            val k = 1f + .012f * b
            tx = focus.x + (cx[i] - focus.x) * k + drift(i, amb, true) * .5f
            ty = focus.y + (cy[i] - focus.y) * k + drift(i, amb, false) * .5f
            tCol = cCol[i].toInt()
            tA = cA[i]
            tS = cS[i].toInt()
        }
        val e = IntroEase.ExpoOut.transform(((ms - beats.burst) / (380f * pace)).coerceIn(0f, 1f))
        val sx = w0x[i] + kx[i] * e
        val sy = w0y[i] + ky[i] * e
        val gs = beats.burst + gd[i] * .30f * beats.gatherLen
        val raw = ((ms - gs) / (.60f * beats.gatherLen)).coerceIn(0f, 1f)
        val k = raw * raw * (3f - 2f * raw)
        if (k < 1f) {
            // The swirl round the centre while flying (strongest mid-flight), as in the source.
            val norm = if (beats.compact) max(sticker.width, 2f * crestR) else 2f * crestR
            val sw = sin(PI.toFloat() * k) * swirl[i] * unit * .12f
            qx = lerp(sx, tx, k) - sw * (ty - focus.y) / norm * 2.2f
            qy = lerp(sy, ty, k) + sw * (tx - focus.x) / norm * 2.2f
            qa = lerp(w0a[i], tA, k)
            qc = if (k < .45f) w0c[i].toInt() else tCol
            qs = if (k < .45f) w0s[i].toInt() else tS
            if (qc == AQUA && k >= .2f) qc = WHITE
        } else {
            qx = tx
            qy = ty
            qa = tA
            qc = tCol
            qs = tS
        }
        if (beats.compact || ms < beats.morphStart) return
        // The morph: from the crest into the name, each grain on its own slight arc.
        val m0 = beats.morphStart + md[i] * .35f * beats.morphLen
        val km = IntroEase.CubicInOut.transform(((ms - m0) / (.55f * beats.morphLen)).coerceIn(0f, 1f))
        if (km <= 0f) return
        val p = puff(i, ms)
        val nxx = nx[i] + hx[i] * p + drift(i, amb, true)
        val nyy = ny[i] + hy[i] * p + drift(i, amb, false)
        val dx = nxx - qx
        val dy = nyy - qy
        val bow = sin(PI.toFloat() * km)
        val push = .25f * bow
        qx = lerp(qx, nxx, km) - dy * arc[i] * .5f * bow + (qx - focus.x) * push
        qy = lerp(qy, nyy, km) + dx * arc[i] * .5f * bow + (qy - focus.y) * push
        qa = lerp(qa, lerp(nA[i], puffAlpha(i), p), km)
        if (km > .5f) { qc = if (p > .5f) puffColour(i) else nCol[i].toInt(); qs = nS[i].toInt() }
    }

    private fun puffColour(i: Int): Int = if (ih(i, 106) < .5f) WHITE else PINK
    private fun puffAlpha(i: Int): Float = if (ih(i, 73) < .38f) .75f else .5f

    /** A grain's slow drift while it rests (px), from the ambient clock (0 for reduced motion). */
    private fun drift(i: Int, amb: Float, x: Boolean): Float {
        val ph = 6.2832f * ih(i, if (x) 111 else 112)
        val f = .5f + .5f * ih(i, if (x) 113 else 114)
        return 1.6f * dp * sin(amb * 2.1f * f + ph)
    }

    // ---- the flight to the app's edges ------------------------------------------------------------------------------------

    /** When and how fast the grains leave for the edges: the land beat, or a tap's exit if it came first. */
    private class Flight(val from: Float, val dur: Float, val tap: Boolean)

    private val planned = Flight(beats.landStart, beats.landLen, false)
    private var tapFlight: Flight? = null

    private fun flight(run: IntroRun): Flight {
        val at = run.exitAt
        if (at < 0f || at >= beats.landStart) return planned
        val f = tapFlight
        if (f != null && f.from == at) return f
        return Flight(at, .70f * run.exitDur, true).also { tapFlight = it }
    }

    /** Grain [i] at [ms], all the way to its edge: [grain], then the flight along a gentle curve, then resting on the edge. */
    private fun petalAt(i: Int, ms: Float, amb: Float, fl: Flight) {
        if (ms < fl.from) { grain(i, ms, amb, waveNow); return }
        grain(i, fl.from, if (reduced) 0f else fl.from / 1000f, waveFrom)
        val sx = qx
        val sy = qy
        val sa = qa
        val sc = qc
        val ss = qs
        val start = fl.from + ld[i] * .30f * fl.dur
        val k = IntroEase.CubicInOut.transform(((ms - start) / (.66f * fl.dur)).coerceIn(0f, 1f))
        if (k <= 0f) return
        val dx = ex[i] - sx
        val dy = ey[i] - sy
        // A quadratic curve bowed to one side: the petals are blown onto the edges, not shot.
        val bx = (sx + ex[i]) / 2f - dy * lc[i] * .45f
        val byy = (sy + ey[i]) / 2f + dx * lc[i] * .45f
        val u = 1f - k
        qx = u * u * sx + 2f * u * k * bx + k * k * ex[i]
        qy = u * u * sy + 2f * u * k * byy + k * k * ey[i]
        qa = lerp(sa, if (i % 5 == 0) 1f else .95f, k)
        qc = if (k < .5f) sc else if (i % 5 == 0) WHITE else PINK
        qs = if (k < .5f) ss else if (i % 5 == 0) 3 else 2
    }

    // ---- drawing --------------------------------------------------------------------------------------------------------

    fun drawBackdrop(scope: DrawScope, ms: Float, amb: Float) = with(scope) {
        backdrop.draw(this, backdrop.zoom(ms, beats.exitStart, reduced = reduced))
        // The veil eases to 65 % once the name stands (the sticker reads on its own), and lifts as the petals fly to the edges.
        veil?.let {
            // It only starts to lift once the circle has gone: rings fading over the bare picture's aqua blended to grey.
            val a = if (reduced) .65f else (1f - .35f * seg(ms, beats.nameFormed, 600f * pace, IntroEase.SineInOut)) *
                (1f - seg(ms, beats.landStart + LEAVE * beats.landLen, (.95f - LEAVE) * beats.landLen, IntroEase.SineInOut))
            drawRect(it, alpha = a)
        }
        // A few loose petals in the open sky once the wave has broken (the storm's leftovers).
        val loose = if (reduced) .8f else .8f * seg(ms, beats.burst, 600f * pace)
        // Over a picture they leave with the frost (pink petals straight on its aqua sky would blend to grey).
        drift.draw(this, amb, if (rimmed && !reduced) loose * (1f - seg(ms, beats.landStart, LEAVE * beats.landLen)) else loose)
        season.draw(this, amb)
    }

    /** How far the stage (rings, crest, bloom) has left: from the land beat, or from a tap's exit. */
    private fun leave(ms: Float, run: IntroRun): Float {
        val planned = seg(ms, beats.landStart, LEAVE * beats.landLen)
        val tap = if (run.exitAt >= 0f && run.exitAt < beats.landStart) seg(ms, run.exitAt, .35f * run.exitDur) else 0f
        return max(planned, tap)
    }

    /** The bloom behind the rings, the wave, the crest's crisp mark, the rings and the burst. */
    fun drawStage(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val amb = run.amb
        val b = breath(amb)
        val alive = .55f + .45f * b
        val gone = 1f - leave(ms, run)
        val ringsOn = if (reduced) 1f else seg(ms, beats.ringStart - 120f * pace, 400f * pace)
        // A soft white light behind the circle, so the crest and the name read over a busy picture.
        val bloom = ringsOn * gone * (if (backdrop.hasPicture) .70f + .10f * b else .40f + .12f * b)
        if (bloom > 0f) withTransform({ translate(focus.x, focus.y); scale(1.25f * ringR, 1.25f * ringR, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = bloom)
        }
        if (!reduced) drawWave(this, ms)
        drawCrestInk(this, ms, b, gone)
        drawRings(this, ms, b, alive, gone * ringsOn)
        if (!reduced) drawBurst(this, ms)
    }

    private fun drawWave(scope: DrawScope, ms: Float) = with(scope) {
        if (ms > beats.burst + 620f * pace) return@with
        val ws = waveNow
        setWave(ms, ws)
        if (ws.alpha <= .01f) return@with
        val head = ws.head
        val path = wavePath
        path.reset()
        path.moveTo(ws.tail, by)
        val stepX = 4f * dp
        var x = ws.tail
        while (x <= head) { path.lineTo(x, crestY(x, ws)); x += stepX }
        val u = unit
        val top = crestY(head, ws)
        // The crest runs over the top and curls forward and down (a breaking lip, not a wall), tucks under, and the face falls
        // to the foot of the wave.
        path.cubicTo(head + u * .03f, top - u * .03f, head + u * .075f, top - u * .012f, head + u * .07f, top + u * .022f)
        path.quadraticTo(head + u * .05f, top + u * .02f, head + u * .035f, top + u * .05f)
        val foot = head - u * .02f
        path.cubicTo(head + u * .05f, top + u * .12f, head + u * .03f, by - amp(head, ws) * .05f, foot, troughY(foot, ws))
        x = foot
        while (x >= ws.tail) { path.lineTo(x, troughY(x, ws)); x -= stepX }
        path.close()
        drawPath(path, waveBody, alpha = ws.alpha)
        // The lip along the top and round the curl, so the head keeps a hard edge; faint ripples behind it.
        path.reset()
        val from = head - u * .08f
        path.moveTo(from, crestY(from, ws))
        path.quadraticTo(head - u * .025f, crestY(head - u * .025f, ws) - u * .006f, head, top)
        path.cubicTo(head + u * .03f, top - u * .03f, head + u * .075f, top - u * .012f, head + u * .07f, top + u * .022f)
        path.quadraticTo(head + u * .05f, top + u * .02f, head + u * .035f, top + u * .05f)
        drawPath(path, waveLip, alpha = ws.alpha, style = lipStroke)
        for (i in 1..6) {
            val ry = u * (.006f + i * .0055f)
            val cxx = head - i * u * .055f
            drawOval(
                Sakura.Primary, Offset(cxx - ry * 3.2f, by + u * .03f - ry), Size(ry * 6.4f, ry * 2f),
                alpha = ws.alpha * .30f * (1f - i / 7f), style = rippleStroke,
            )
        }
        withTransform({ translate(head + u * .04f, top + u * .06f); scale(u * .26f, u * .26f, Offset.Zero) }) {
            drawCircle(unitPink, 1f, Offset.Zero, alpha = .25f * ws.alpha)
        }
    }

    /** The crest's crisp mark: it bleeds in under the grains as they lock, then stays as a faint watermark behind the name. */
    private fun drawCrestInk(scope: DrawScope, ms: Float, b: Float, gone: Float) = with(scope) {
        val ink = when {
            reduced -> .22f
            beats.compact -> .22f * seg(ms, beats.ringStart, 500f * pace)
            else -> seg(ms, beats.crestLock - 60f * pace, 520f * pace) * lerp(1f, .22f, seg(ms, beats.morphStart, .6f * beats.morphLen))
        } * gone
        if (ink <= .005f) return@with
        val k = crestR * (1f + .012f * b)
        withTransform({ translate(focus.x, focus.y); scale(k, k, Offset.Zero) }) {
            drawPath(silhouette, crestInk, alpha = .85f * ink)
            drawPath(silhouette, Sakura.Primary, alpha = ink, style = crestLine)
            drawCircle(Sakura.RoseBrown, .15f, Offset.Zero, alpha = .7f * ink, style = crestInner)
            for (p in 0 until 5) {
                val th = (270f + 72f * p) * (PI.toFloat() / 180f)
                drawLine(Sakura.RoseBrown.copy(alpha = .45f * ink), Offset(.24f * cos(th), .24f * sin(th)), Offset(.58f * cos(th), .58f * sin(th)), crestVein.width, StrokeCap.Round)
            }
        }
    }

    /** A dot; over a picture a pink or rose one sits on a white rim ([HALO]). */
    private fun dot(x: Float, y: Float, size: Int, alpha: Float, colour: Int) {
        // The rim is more opaque than the dot, so the dot's soft edge falls on white, not on the picture.
        if (rimmed && (colour == PINK || colour == ROSE)) dots.add(x, y, (size + 1).coerceAtMost(4), (alpha * 1.6f).coerceAtMost(1f), HALO)
        dots.add(x, y, size, alpha, colour)
    }

    /** Three dotted rings that switch on dot by dot and counter-rotate, a comet round the outer one, all on one breath. */
    private fun drawRings(scope: DrawScope, ms: Float, b: Float, alive: Float, vis: Float) = with(scope) {
        if (vis <= .01f) return@with
        val ringT = if (reduced) 10f else (ms - beats.ringStart) / (1000f * pace)
        val spinT = (ms - beats.ringStart) / 1000f
        if (ringT < -.05f) return@with
        val swell = 1f + .06f * (1f - vis)
        for (s in 0 until 3) {
            val r = RING_R[s] * ringR * (1f + .014f * b) * swell
            val count = RING_N[s]
            for (i in 0 until count) {
                val appear = ((ringT - (i.toFloat() / count) * .5f) / .24f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                if (appear <= .01f) continue
                val a = (i.toFloat() / count) * 6.2832f + RING_SPIN[s] * spinT + .35f * s
                dot(focus.x + cos(a) * r, focus.y + sin(a) * r, RING_SIZE[s] - (if (appear < .5f) 1 else 0), RING_A[s] * appear * alive * vis, RING_C[s])
            }
        }
        // The comet: 20 dots racing round the outer ring, white over a pink under-dot.
        val head = spinT * 1.5f
        val cr = ringR * (1f + .014f * b) * swell
        val on = if (reduced) 1f else (ringT / .4f).coerceIn(0f, 1f)
        for (i in 0 until 20) {
            val a = head - i * .032f
            val fade = (1f - i / 20f) * .95f * on * vis
            val x = focus.x + cos(a) * cr
            val y = focus.y + sin(a) * cr
            if (i < 3) {
                dot(x, y, 4, fade * .35f, PINK)
                dots.add(x, y, 3, fade, WHITE)
            } else {
                dot(x, y, if (i < 9) 3 else 2, fade * .85f, PINK)
            }
        }
        dots.flush(this)
        val all = if (reduced) 1f else (ringT / .3f).coerceIn(0f, 1f)
        for (s in 0 until 2) {
            drawCircle(Sakura.Primary, RING_R[s] * ringR * swell, focus, alpha = .22f * alive * all * vis, style = hairline)
        }
    }

    /** The break: a radial flash, two shock rings and a ring of thin petal-shaped rays. */
    private fun drawBurst(scope: DrawScope, ms: Float) = with(scope) {
        val bt = ms - beats.burst
        if (bt < 0f || bt > 1050f) return@with
        val punch = if (level == EffectLevel.SAVER) .7f else 1f
        val flash = (1f - bt / 260f).coerceAtLeast(0f)
        if (flash > .01f) {
            val reachR = unit * (.12f + .5f * min(1f, bt / 260f))
            withTransform({ translate(focus.x, focus.y); scale(reachR, reachR, Offset.Zero) }) {
                drawCircle(unitFlash, 1f, Offset.Zero, alpha = .85f * flash * punch)
            }
        }
        for (ring in 0 until 2) {
            val k = ((bt - ring * 70f) / 620f).coerceIn(0f, 1f)
            if (k <= 0f || k >= 1f) continue
            val st = shock[((1f - k) * 7f).roundToInt().coerceIn(0, 7)]
            drawCircle(Sakura.Primary, unit * .5f * k.pow(.62f), focus, alpha = .75f * (1f - k).pow(1.7f) * punch, style = st)
        }
        val sk = min(1f, bt / 420f)
        val a = .80f * (1f - sk).pow(1.6f) * punch
        if (a > .01f) for (i in 0 until rays) {
            val ang = (i.toFloat() / rays) * 6.2832f + .21f + (ih(i, 121) - .5f) * .10f
            val long = .05f + .2f * (((i * 37) % 100) / 100f)
            val inner = unit * .04f * sqrt(sk)
            val outer = unit * (.04f + long) * sk.pow(.55f)
            val len = outer - inner
            if (len <= 1f) continue
            val mid = (inner + outer) / 2f
            withTransform({
                translate(focus.x + cos(ang) * mid, focus.y + sin(ang) * mid)
                rotate(ang * 180f / PI.toFloat() + 90f, Offset.Zero)
                scale(max(.8f * dp, len * .05f), len / 2f, Offset.Zero)
            }) {
                drawPath(petal, if (i % 3 == 0) Sakura.White else Sakura.Primary, alpha = a)
            }
        }
    }

    /** Every grain this frame: the dots in batches, then the petal (or sparkle) sprites, and the edges' light once they land. */
    fun drawPetals(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val amb = run.amb
        val fl = flight(run)
        if (ms >= fl.from) setWave(fl.from, waveFrom)
        if (ms < beats.burst + 620f * pace) setWave(ms, waveNow)
        val exiting = run.exiting
        // Landed grains fade with the exit (a tap's a little later: they are still arriving).
        val out = if (!exiting) 1f else if (fl.tap) 1f - seg(ms, run.exitAt + .55f * run.exitDur, .40f * run.exitDur)
        else 1f - seg(ms, run.exitAt + .30f * run.exitDur, .55f * run.exitDur)
        if (out <= 0f) return@with
        drawTrace(this, ms, run, fl, out)
        val motion = beats.motion(ms)
        spN = 0
        for (i in 0 until n) {
            petalAt(i, ms, amb, fl)
            val a = qa * out
            if (a <= .02f) continue
            if (sprite[i]) {
                spIdx[spN] = i; spX[spN] = qx; spY[spN] = qy; spA[spN] = a; spN++
                continue
            }
            if (qc == AQUA) {
                dots.add(qx, qy, (qs + 1).coerceAtMost(4), a, WHITE)
                dots.add(qx, qy, qs, a, qc)
            } else {
                // Over a picture's bare aqua (once the frost lifts) rose-brown blends to grey even on a rim: the flight is pink.
                dot(qx, qy, qs, a, if (rimmed && qc == ROSE && ms >= fl.from) PINK else qc)
            }
        }
        dots.flush(this)
        for (j in 0 until spN) {
            val i = spIdx[j]
            val r = spriteR[i]
            val spin = 360f * ih(i, 96) + spriteSpin[i] * motion
            if (spriteKind == 1) {
                val flip = cos(spriteFlip[i] + 3.1f * motion)
                val col = if (spriteWhite[i]) Sakura.White else Sakura.Primary
                withTransform({ translate(spX[j], spY[j]); rotate(spin, Offset.Zero); scale(.72f * r * flip.coerceIn(-1f, 1f).let { if (abs(it) < .15f) .15f else it }, r, Offset.Zero) }) {
                    // Over a picture a pink petal lies on a slightly larger white one, its rim (see [HALO]).
                    if (rimmed && !spriteWhite[i]) withTransform({ scale(1.3f, 1.18f, Offset.Zero) }) { drawPath(petal, Sakura.White, alpha = spA[j] * .85f) }
                    drawPath(petal, col, alpha = spA[j] * (if (spriteWhite[i]) .95f else .9f))
                }
            } else {
                introSparkle(sparkle, Offset(spX[j], spY[j]), r * .8f, if (spriteWhite[i]) Sakura.White else Sakura.Aqua, spA[j], spin * .3f)
            }
        }
    }

    /** A thin light along the edges, drawn once the grains have landed, under them. */
    private fun drawTrace(scope: DrawScope, ms: Float, run: IntroRun, fl: Flight, out: Float) = with(scope) {
        val on = seg(ms, fl.from + .80f * fl.dur, .25f * fl.dur) * out
        if (on <= .01f) return@with
        drawPath(trace, Sakura.Primary, alpha = .35f * on, style = traceGlow)
        drawPath(trace, Sakura.White, alpha = .95f * on, style = traceCore)
    }

    /**
     * On exit the app develops where the petals landed: its top bar and its dock show through first, while the host's sakura
     * iris opens the rest. Drawn straight into the host's own layer (which is offscreen from the exit's first frame on, except on
     * SAVER where the host only fades): DstOut there clears the intro's pixels and shows the app. Never on SAVER (no layer: the
     * cut would reach past the intro) nor for the still.
     */
    fun drawDevelop(scope: DrawScope, run: IntroRun) = with(scope) {
        if (!run.exiting || reduced || level == EffectLevel.SAVER) return@with
        val tap = run.exitAt < beats.landStart
        val k = IntroEase.CubicOut.transform(seg(run.ms, run.exitAt + (if (tap) .30f else .05f) * run.exitDur, .45f * run.exitDur))
        if (k <= 0f) return@with
        drawRect(headerCut, Offset.Zero, Size(w, headerY + 16f * dp), alpha = k, blendMode = BlendMode.DstOut)
        val c = min(PetalEdges.DOCK_CORNER_DP * dp, dock.height / 2f) + 1f * dp
        drawRoundRect(Color.Black, Offset(dock.left - dp, dock.top - dp), Size(dock.width + 2f * dp, dock.height + 2f * dp), CornerRadius(c), alpha = k, blendMode = BlendMode.DstOut)
    }

    /** The meter with its caption and counter, 歡迎回來 and the name sticker. */
    fun drawType(scope: DrawScope, ms: Float, amb: Float) = with(scope) {
        val b = breath(amb)
        drawMeter(this, ms, b)
        drawBrow(this, ms)
        drawSticker(sticker, focus.x, stickerTop, ms, beats.stickerStart, pace, beats.compact, reduced, beats.exitStart)
    }

    private fun drawMeter(scope: DrawScope, ms: Float, b: Float) = with(scope) {
        val on = if (reduced) 1f else seg(ms, 160f * pace, 260f * pace)
        if (on <= 0f) return@with
        val p = if (reduced) 1f else beats.meter(ms)
        // A soft white pill behind it, so it reads over a busy picture.
        val padX = 14f * dp
        val top = captionTop - 6f * dp
        val bottom = ticksTop + tickH + 8f * dp
        drawRoundRect(
            Sakura.White.copy(alpha = (if (backdrop.hasPicture) .62f else .38f) * on), Offset(meterLeft - padX, top),
            Size(meterW + 2 * padX, bottom - top), CornerRadius(16f * dp),
        )
        // The caption: 花瓣集合中 with three blinking dots, then 準備好了 typed in when the meter is full.
        val readyAt = beats.nameFormed - 40f * pace
        val gatherA = on * (if (reduced) 0f else 1f - seg(ms, readyAt - 120f * pace, 120f * pace))
        val left = meterLeft
        if (gatherA > 0f) {
            drawRun(captionGather, left, captionTop, Sakura.TextSecondary, gatherA)
            val dotY = captionTop + captionGather.baseline - 2.5f * dp
            val lit = ((ms / 240f).toInt() % 4)
            for (d in 0 until 3) {
                val x = left + captionGather.width + (6f + 6f * d) * dp
                drawCircle(Sakura.Primary, 1.6f * dp, Offset(x, dotY), alpha = gatherA * (if (d < lit) 1f else .3f))
            }
        }
        if (reduced || ms >= readyAt) drawTyped(captionReady, left, captionTop, Sakura.TextSecondary, on, ms, readyAt, 55f * pace, 400f, reduced)
        // The 44 ticks: filled ones in pink breathing with the rings, the rest a faint rose.
        val filled = p * TICKS
        for (i in 0 until TICKS) {
            val x = left + i * (tickW + tickGap)
            val f = (filled - i).coerceIn(0f, 1f)
            drawRoundRect(Sakura.RoseBrown, Offset(x, ticksTop), Size(tickW, tickH), CornerRadius(tickW / 2f), alpha = .18f * on * (1f - f))
            if (f > 0f) drawRoundRect(Sakura.Primary, Offset(x, ticksTop), Size(tickW, tickH), CornerRadius(tickW / 2f), alpha = on * f * (.65f + .35f * b))
        }
        // 000 % in Quicksand, each digit in a fixed slot so the number does not jitter.
        val value = (p * 100f).toInt().coerceIn(0, 100)
        var x = left + ticksW + 10f * dp
        val y = ticksTop + tickH / 2f - digits.height / 2f
        for (place in 0 until 3) {
            val d = when (place) { 0 -> value / 100; 1 -> (value / 10) % 10; else -> value % 10 }
            drawGlyph(digits, d, Offset(x + (digitSlot - digits.advance[d]) / 2f, y), Sakura.Text, on)
            x += digitSlot
        }
        drawGlyph(digits, 10, Offset(x, y), Sakura.Text, on)
    }

    /** 歡迎回來 (or the user's words) above the name: its tracking closes in as it fades up, in ink inside a white edge. */
    private fun drawBrow(scope: DrawScope, ms: Float) = with(scope) {
        val run = brow ?: return@with
        val start = beats.stickerStart - 120f * pace
        val e = if (reduced) 1f else IntroEase.ExpoOut.transform(((ms - start) / (420f * pace)).coerceIn(0f, 1f))
        if (e <= 0f) return@with
        val spread = .14f * run.fontPx * (1f - e)
        val left = focus.x - run.width / 2f
        val mid = (run.size - 1) / 2f
        val outline = browOutline ?: return@with
        drawIntoCanvas { canvas ->
            // Composed opaque and faded as one, so the ink never shows through a half-faded white edge as grey.
            browPaint.alpha = e
            val pad = outline.width + run.fontPx * .3f + spread * run.size
            canvas.saveLayer(Rect(left - pad, browTop - pad, left + run.width + pad, browTop + run.height + pad), browPaint)
            val inner = browOutlineInner ?: outline
            for (i in 0 until run.size) {
                drawGlyph(run, i, Offset(left + run.x[i] + (i - mid) * spread, browTop), Sakura.White, 1f, stroke = outline)
                drawGlyph(run, i, Offset(left + run.x[i] + (i - mid) * spread, browTop), Sakura.White, 1f, stroke = inner)
            }
            for (i in 0 until run.size) drawGlyph(run, i, Offset(left + run.x[i] + (i - mid) * spread, browTop), Sakura.Text, 1f)
            canvas.restore()
        }
    }

    companion object {
        /**
         * The dots' colours, in drawing order: first the white rims ([HALO]) that pink grains get over a picture (pink edges
         * straight on the picture's aqua blend to grey), then the pinks, then white, and aqua last, only ever on a white halo.
         */
        val PALETTE = arrayOf(Sakura.White, Sakura.Background, Sakura.MilkTea, Sakura.Primary, Sakura.RoseBrown, Sakura.White, Sakura.Aqua)
        const val HALO = 0
        const val PINK = 3
        const val ROSE = 4
        const val WHITE = 5
        const val AQUA = 6
        const val TICKS = 44
        private const val T_OUTLINE: Byte = 0
        private const val T_VEIN: Byte = 1
        private const val T_RING: Byte = 2
        private const val T_FILL: Byte = 3
        /**
         * The wave on a portrait phone: the source's .72 screen widths and .158 of the short side made a short stub here (a
         * phone's short side is its width), so it is longer and taller.
         */
        private const val WAVE_LENGTH = 1.0f
        /** The circle (rings, crest, bloom) leaves in this share of the flight; a picture's frost lifts only after it. */
        private const val LEAVE = .35f
        private const val WAVE_AMP = .22f
        private val RING_R = floatArrayOf(1f, .86f, .72f)
        private val RING_N = intArrayOf(96, 72, 120)
        private val RING_SPIN = floatArrayOf(.26f, -.34f, .44f)
        private val RING_SIZE = intArrayOf(3, 2, 2)
        private val RING_A = floatArrayOf(.92f, .72f, .56f)
        private val RING_C = intArrayOf(PINK, ROSE, PINK)
    }
}
