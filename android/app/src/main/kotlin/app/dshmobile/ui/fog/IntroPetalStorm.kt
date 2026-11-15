// Adapted from bigfatorangecatjohn/opening-free (c) 2026 bigfatorangecatjohn, MIT License: the wave of grains on summed
// harmonics (its three sines, the body, crest file and spray layout), the burst (a radial flash, two shock rings and rays), the
// gather (a per-dot delay, flight and swirl), the three dotted rings with their comet and the 0.32 Hz breath, the 44-tick meter
// and the batched dot drawing. The flight onto the app's edges follows hbgdjb/dsh-boot-particles (c) 2026 hbgdjb, MIT License
// (a quota by length, the nearest segment that still has quota, then order by projection). Shapes, colours, words and timings
// here are our own; nothing of their images, sounds or names.
package app.dshmobile.ui.fog

import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
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
 * left edge and breaks just past the middle, the run's one hit (a haptic tick, a white core flash, two shock rings and petal-stroke
 * rays); the burst throws the grains out, and they swirl back and gather into the 櫻紋 crest. Three dotted rings switch on dot by
 * dot round it and breathe, and a meter under them runs on (90 % when the name stands, 100 % while it holds). The crest's grains
 * then re-form as your name, which lands as the usual sticker under 歡迎回來, always inside the inner ring. From the start a faint
 * line marks where the app's top bar ends and a dotted outline where its dock sits; at the end the name turns petal pink as its
 * grains fly onto exactly those edges, and the app appears where they land: the look's ground gives way from the dock upward, the
 * top bar grows out of the petal line, the dock frosts white inside its petal outline and the composer comes up out of it, and
 * what is left fades into the app.
 *
 * The look does its own hand-off, so the host's sakura iris is only ever a tap's (its planned exit is the run's last instant,
 * see [petalStormTiming]). With a picture the storm plays in a calm blush field under the figure, and the picture stays clear
 * above it. Beats ([PetalStormBeats]): the wave, the gather and the morph keep their own speed; the crest and the name holds
 * stretch with the 1.5–8 s setting. Everything is a pure function of the run's clock, read only in draw lambdas; the grains are
 * drawn in a few batched point calls per frame.
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
    val timing = remember(beats) { petalStormTiming(beats) }
    val line = introWelcomeLine(intro)
    val words = intro.welcome.trim().ifEmpty { stringResource(R.string.intro_welcome_default) }
    val name = intro.name.trim()
    val gathering = stringResource(R.string.intro_petal_storm_gathering)
    val ready = stringResource(R.string.intro_petal_storm_ready)
    val skip = stringResource(R.string.intro_skip)
    val date = remember { SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date()) }
    // The home screen shows the picture at the 透明度 setting: the storm's picture is that same picture, so the hand-off has no seam.
    val homeAlpha = (LocalLook.current.opacity / 100f).coerceIn(0f, 1f)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val context = LocalContext.current
    val coverage = IntroFonts.coverage(context.resources)
    // The handwriting as a platform typeface, only to trace the name's outlines once (the grains land on them).
    val pen = remember(context) { runCatching { context.resources.getFont(R.font.intro_hand) }.getOrNull() }
    val gate = remember { PetalStormGate() }
    val opaqueNow by rememberUpdatedState(onOpaqueChange)
    // Each change passes once (the host and the look both say "no longer opaque" on the way out).
    val opaque: (Boolean) -> Unit = remember(gate) { { v -> if (gate.opaque != v) { gate.opaque = v; opaqueNow(v) } } }
    // The hand-off cuts holes in the intro (DstOut) to show the app: that needs a layer of its own round the host, whose own layer
    // is not offscreen before its exit. Only while the app develops, so the rest of the run pays for no extra layer.
    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = if (gate.revealing(beats)) CompositingStrategy.Offscreen else CompositingStrategy.Auto }) {
        IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, opaque, onDone, announce = null) {
            val run = this.run
            SideEffect { gate.run = run }
            // The app underneath has to draw again before it shows through: from the moment the petals take off (or the still's
            // fade). Until then it stays hidden, so it costs nothing under the storm.
            LaunchedEffect(run) {
                snapshotFlow { run.ms >= beats.landStart || run.fade < 1f }.first { it }
                opaque(false)
            }
            // 歡迎回來 (or the user's words) above the name; with no name the words themselves are the sticker.
            val brow = if (name.isEmpty()) null else words
            val scene = remember(this, beats, brow, name, gathering, ready, skip, date, homeAlpha, intro.skippable) {
                PetalStormScene(this, beats, measurer, coverage, pen, brow, name.ifEmpty { words }, gathering, ready, skip, date, homeAlpha, intro.skippable)
            }
            run.sceneOrigin = scene.focus
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBackdrop(this, run) })
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawGhosts(this, run) })
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawStage(this, run) })
            // Until they take off the grains sit under the type (they gather behind the name sticker) ...
            Spacer(Modifier.fillMaxSize().drawBehind { if (!scene.flying(run)) scene.drawPetals(this, run) })
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawMeter(this, run); scene.drawSkip(this, run) })
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawType(this, run) })
            // No layer of its own: the cut has to reach the layer round the host to show the app underneath (see drawReveal).
            Spacer(Modifier.fillMaxSize().drawBehind { scene.drawReveal(this, run) })
            // ... and in flight over everything, landing on the app's edges as it appears.
            Spacer(Modifier.fillMaxSize().drawBehind { if (scene.flying(run)) scene.drawPetals(this, run) })
            PetalStormSemantics(this, scene, timing, intro.skippable, skip, line)
        }
    }
}

/**
 * The host's timing for a run: the haptic tick when the wave breaks, the welcome announced once the name is there, and the exit
 * at the very end. The look hands over to the app by itself (the petals land and the app appears there), so the host's own exit
 * iris only ever opens for a tap.
 */
internal fun petalStormTiming(b: PetalStormBeats): IntroTiming =
    IntroTiming(exit = b.plan.total, total = b.plan.total, still = b.still, hit = b.burst, announce = b.announce)

/** The run (once the host has one) and the last opaque state passed on. */
private class PetalStormGate {
    var run by mutableStateOf<IntroRun?>(null)
    var opaque: Boolean? = null

    /** True while the app develops under the look on the planned path (a tap's exit is the host's iris). */
    fun revealing(b: PetalStormBeats): Boolean {
        val r = run ?: return false
        if (r.ms < b.landStart) return false
        return !(r.exitAt >= 0f && r.exitAt < b.landStart)
    }
}

/**
 * What a screen reader (and the tests) read: the skip button where its pill is (only with [skippable]) and the welcome [line] as
 * the run's one live announcement from [IntroTiming.announce] (at once for reduced motion). Composed once more when the
 * announcement starts, never per frame.
 */
@Composable
private fun PetalStormSemantics(sc: IntroScope, scene: PetalStormScene, timing: IntroTiming, skippable: Boolean, skipLabel: String, line: String) {
    val run = sc.run
    val shown by remember(timing) { derivedStateOf { sc.reduced || run.ms >= timing.announce } }
    val d = sc.density
    fun Modifier.at(r: Rect) = this
        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
        .size(with(d) { r.width.coerceAtLeast(1f).toDp() }, with(d) { r.height.coerceAtLeast(1f).toDp() })
    if (skippable) Box(Modifier.at(scene.skipRect).semantics { text = AnnotatedString(skipLabel); role = Role.Button })
    if (shown) Box(Modifier.at(scene.nameRect).semantics { text = AnnotatedString(line); liveRegion = LiveRegionMode.Polite })
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
 * to the app's edges ([landStart]) and the hand-off ([exitStart]). A [compact] run (below 2.5 s) has no crest: the wave's grains
 * gather straight into the name. Only the two holds stretch with the length, so 8 s waits longer instead of moving slower.
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
    val stickerStart: Float = nameFrom + (if (compact) .55f else .45f) * nameLen
    /**
     * Even a long name's last glyph stands by here: the glyphs start within 240 ms of each other and each is opaque within 60 ms
     * (see drawSticker). From 3 s the name then stays legible at least 600 ms before the petals take it away.
     */
    val nameLegible: Float = stickerStart + 300f * pace
    /** The welcome line is announced once the name is on screen. */
    val announce: Float = stickerStart + 160f * pace
    /** The rings switch on, dot by dot, while the crest gathers. */
    val ringStart: Float = gatherStart + .40f * gatherLen
    val morphStart: Float = if (compact) -1f else plan.at(morph)
    val morphLen: Float = if (compact) 0f else plan.length(morph)
    val landStart: Float = plan.at(land)
    val landLen: Float = plan.length(land)
    val exitStart: Float = plan.at(exit)
    /** The meter and the skip pill are gone before the first petal reaches the edges: they fade over the last of the name hold. */
    val meterOutStart: Float = landStart - 150f * pace
    val meterOutLen: Float = 150f * pace
    /**
     * The meter is full here: a moment after the name stands (it reads 90 % when the name is complete), never later than just
     * before the meter leaves, never before the grains are home.
     */
    val meterFull: Float = max(nameFormed, min(nameLegible + 180f * pace, meterOutStart - 60f * pace))
    /** 準備好了 is typed in as the meter closes on 100 %. */
    val readyAt: Float = meterFull - 100f * pace
    /**
     * The name's solid type turns petal-pink over the first [typeTintLen] of the take-off and is gone within [typeOutLen]: its
     * grains carry it away, and no frame shows the dark ink half faded over pink (that read as a muddy grey-mauve).
     */
    val typeTintLen: Float = 90f * pace
    val typeOutLen: Float = 150f * pace
    /** Every petal has landed on its edge here: the dock's contents only start to show after it (nothing then crosses them). */
    val landed: Float = landStart + .80f * landLen
    /**
     * The look's own ground gives way to the app's from here, as the petals leave the name: without a picture the sky turns into
     * the home's blush, with one the calm field under the figure lifts from the dock's edge upward. At least 400 ms (ease-out),
     * so the app is never swapped in at once and the bar's line never shows a step.
     */
    val liftStart: Float = landStart + .30f * landLen
    val liftLen: Float = plan.total - liftStart
    /** The sky's crossfade to the home's blush (no picture): mostly done before the bar develops above it. */
    val skyLen: Float = 300f * pace
    /** The bar develops where the petals have landed, over [revealLen] from here. */
    val revealAt: Float = landStart + .78f * landLen
    val revealLen: Float = 220f * pace
    /** The dock's inside crossfades evenly (a veil, never a band) once every petal is home, done by [dockAt] + [dockLen]. */
    val dockAt: Float = landed
    val dockLen: Float = 180f * pace
    /** What is left of the look fades into the app over the last [fadeLen] ms (about 250). */
    val fadeLen: Float = min(250f, .85f * plan.length(exit))
    val fadeStart: Float = plan.total - fadeLen
    /** The reduced-motion frame: the welcome, settled, just before the petals would fly off. */
    val still: Float = plan.end(name) - 1f

    fun isHold(i: Int): Boolean = i == crest || i == name

    private val meterAt: FloatArray
    private val meterValue: FloatArray

    init {
        if (compact || nameLegible >= meterFull) {
            meterAt = floatArrayOf(0f, burst, meterFull)
            meterValue = floatArrayOf(0f, .20f, 1f)
        } else {
            // 90 % when the name stands, the last 10 % while it holds.
            meterAt = floatArrayOf(0f, burst, plan.end(gather), plan.end(crest), nameLegible, meterFull)
            meterValue = floatArrayOf(0f, .16f, .46f, .62f, .90f, 1f)
        }
    }

    /** The meter, 0..1: it steps up with each beat, reads 90 % when the name stands and 100 % at [meterFull]. */
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
                // The gather, the morph and the flight are a little brisker than the first cut, so that at 3 s the whole name
                // (even "Christopher Robin") reads for at least 600 ms before the petals take it away.
                PetalStormBeats(
                    IntroPlan.of(
                        total,
                        IntroBeat.move(480f), IntroBeat.move(640f), IntroBeat.hold(1f, 100f), IntroBeat.move(420f),
                        IntroBeat.hold(1.6f, 660f), IntroBeat.move(360f), IntroBeat.move(320f),
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

/**
 * The wave at one moment: its peak ([head]), the foot of its front face ([front]) and its tail (px), how far it has risen, its
 * alpha, spray and sinking, and the clock (s).
 */
private class WaveState {
    var head = 0f
    var front = 0f
    var tail = 0f
    var span = 1f
    var rise = 1f
    var alpha = 0f
    var spray = 0f
    var sink = 0f
    var t = 0f
}

/**
 * Winter's snow for this look: soft white flakes with a thin aqua rim inside a white halo (the aqua only ever touches white),
 * spread over the whole width in even columns and falling with a sway. A flake fades out as it passes behind the name, the meter
 * or the skip pill, so it never sits on type.
 */
private class PetalSnow(private val w: Float, private val h: Float, private val dp: Float, private val count: Int, private val avoid: Array<Rect>) {
    private val rim = Stroke(.7f * dp)
    private val margin = 16f * dp

    fun draw(scope: DrawScope, amb: Float, alpha: Float) = with(scope) {
        if (alpha <= 0f) return@with
        val spanY = h + 2 * margin
        for (i in 0 until count) {
            val hh = ih(i, 150)
            val fall = (24f + 20f * hh) * dp
            val y = ((ih(i, 151) * spanY + amb * fall) % spanY) - margin
            val sway = (5f + 8f * ih(i, 152)) * dp * sin(amb * (.7f + .5f * hh) + 6.28f * ih(i, 153))
            val x = (i + .15f + .7f * ih(i, 154)) / count * w + sway
            var a = alpha * (.75f + .25f * ih(i, 155))
            for (r in avoid) {
                val dx = max(max(r.left - x, x - r.right), 0f)
                val dy = max(max(r.top - y, y - r.bottom), 0f)
                a *= ((hypot(dx, dy) - 4f * dp) / (16f * dp)).coerceIn(0f, 1f)
            }
            if (a <= .02f) continue
            val rr = (1.4f + 1.3f * ih(i, 156)) * dp
            val c = Offset(x, y)
            drawCircle(Sakura.White.copy(alpha = .28f * a), rr * 2.5f, c)
            drawCircle(Sakura.White.copy(alpha = .95f * a), rr * 1.55f, c)
            drawCircle(Sakura.Aqua.copy(alpha = .65f * a), rr * 1.2f, c, style = rim)
            drawCircle(Sakura.White.copy(alpha = a), rr, c)
        }
    }
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
    skip: String,
    date: String,
    private val homeAlpha: Float,
    private val skippable: Boolean,
) {
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val dp = sc.density.density
    private val level = sc.level
    private val reduced = sc.reduced
    private val fx = sc.fx
    private val unit = max(1f, min(w, h))
    private val pace = beats.pace
    private val hasPicture = sc.picture != null
    private val insetTop = sc.insetTop
    private val insetBottom = sc.insetBottom

    // ---- type, measured first (the layout makes room for the meter) ----------------------------------------------------------
    private val s = (min(w / dp / 412f, h / dp / 915f)).coerceIn(.78f, 1.1f)
    private val mm = IntroMeasure(measurer, dp, sc.density.fontScale, s, coverage)
    /**
     * Every Chinese word of the look is in the handwriting (thickened), as in 櫻紋陣: the meter's status at 18 sp and 跳過 at
     * 16 sp, each inside a white halo as wide as 歡迎回來's, so the thin pen reads at that size (at 11–15 sp without a halo it
     * smeared 瓣 into a blob). SKIP, the chevrons and the digits stay in Quicksand.
     */
    private val captionGather = mm.run(gathering, IntroFonts.hand(18f), .04f)
    private val captionReady = mm.run(ready, IntroFonts.hand(18f), .04f)
    private val captionHalo = stickerStroke(captionGather, .16f).let { Stroke(it.width, join = StrokeJoin.Round, cap = StrokeCap.Round) }
    private val captionHaloInner = Stroke(captionHalo.width * .55f, join = StrokeJoin.Round, cap = StrokeCap.Round)
    /** The quiet English line under the ticks: what beat this is (PETALS GATHERING, then READY), typed in. */
    private val subGather = mm.run(SUB_GATHER, IntroFonts.latin(10f, FontWeight.SemiBold), .24f)
    private val subReady = mm.run(SUB_READY, IntroFonts.latin(10f, FontWeight.SemiBold), .24f)
    private val digits = mm.run("0123456789%", IntroFonts.latin(13f, FontWeight.SemiBold), 0f)
    private val digitSlot = (0 until 10).maxOf { digits.advance[it] }
    private val tickW = 2.2f * dp
    private val tickH = 11f * dp
    private val tickGap = 3f * dp
    private val ticksW = TICKS * tickW + (TICKS - 1) * tickGap
    private val counterW = 3 * digitSlot + digits.advance[10]
    private val meterW = ticksW + 10f * dp + counterW
    private val subGap = 7f * dp
    private val meterH = captionGather.height + 8f * dp + tickH + subGap + subGather.height
    private val skipCjk = mm.run(skip, IntroFonts.hand(16f), .04f)
    private val skipHalo = stickerStroke(skipCjk, .16f).let { Stroke(it.width, join = StrokeJoin.Round, cap = StrokeCap.Round) }
    private val skipHaloInner = Stroke(skipHalo.width * .55f, join = StrokeJoin.Round, cap = StrokeCap.Round)
    private val skipLatin = mm.run(IntroLatin.SKIP, IntroFonts.latin(11f, FontWeight.SemiBold), .2f)
    private val hudBrand = mm.run(IntroLatin.BRAND, IntroFonts.latin(11f, FontWeight.SemiBold), .22f)
    private val hudDate = mm.run(date, IntroFonts.latin(11f, FontWeight.Medium), .12f)
    private val hudOutline = Stroke(2.6f * dp, join = StrokeJoin.Round)

    // ---- layout: the app's edges, and the stage between them --------------------------------------------------------------
    private val headerY = PetalEdges.headerY(insetTop, dp)
    private val dock = PetalEdges.dock(w, h, insetBottom, dp)
    private val dockCorner = min(PetalEdges.DOCK_CORNER_DP * dp, dock.height / 2f)
    private val spanTop = headerY + 12f * dp
    private val ringR: Float
    val focus: Offset
    private val meterTop: Float
    /** With a picture: the picture stays clear above this line and gives way to a calm blush field below it (the stage). */
    private val calmY: Float

    init {
        if (hasPicture) {
            // The storm plays in the calm field under the figure, so the crest and the name never sit on a face or a body.
            val meterBottom = dock.top - 22f * dp
            val top = min(.53f * h, meterBottom - meterH - 40f * dp - 200f * dp).coerceAtLeast(spanTop + 8f * dp)
            val r = minOf(.31f * w, 150f * dp, (meterBottom - meterH - 40f * dp - top) / 2f).coerceAtLeast(40f * dp)
            ringR = r
            focus = Offset(w / 2f, top + r)
            meterTop = focus.y + r + 40f * dp
            calmY = top - 4f * dp
        } else {
            val spanBottom = dock.top - 12f * dp
            val span = spanBottom - spanTop
            val r = minOf(.37f * w, 160f * dp, .30f * span).coerceAtLeast(40f * dp)
            ringR = r
            val fy = max(spanTop + .40f * span, spanTop + 20f * dp + r)
            focus = Offset(w / 2f, fy)
            // Low in the lower third, closer to the dock it hands over to than to the rings (no empty band above the dock), and
            // clear of the skip pill.
            meterTop = max(fy + r + 40f * dp, .76f * h - meterH / 2f).coerceAtMost(dock.top - 44f * dp - meterH).coerceAtLeast(fy + r + 32f * dp)
            calmY = h
        }
    }

    private val crestR = .62f * ringR
    private val meterLeft = (w - meterW) / 2f
    private val meterPad = 14f * dp
    private val ticksTop = meterTop + captionGather.height + 8f * dp
    private val meterRect = Rect(meterLeft - meterPad, meterTop - 6f * dp, meterLeft + meterW + meterPad, meterTop + meterH + 8f * dp)
    // The skip pill: the host's own place, at the bottom right.
    private val chevW = 2 * 5f * dp + 2f * dp
    private val skipRight = w - 20f * dp
    private val skipTop = h - insetBottom - 42f * dp
    private val skipLeft = skipRight - chevW - 6f * dp - skipLatin.width - 6f * dp - skipCjk.width
    /** Where the skip pill is (the screen reader's skip button too). */
    val skipRect = Rect(skipLeft - 10f * dp, skipTop - 5f * dp, skipRight + 10f * dp, skipTop + skipCjk.height + 5f * dp)

    // ---- the name ------------------------------------------------------------------------------------------------------------
    /** The name stays inside the rings: at most 80 % of the inner ring's width. */
    private val nameMaxW = .80f * 2f * RING_R[2] * ringR
    /**
     * It shrinks to fit down to about 40 sp, then breaks onto two lines (at the space, for a name that has one); a longer word than
     * two lines of 40 sp hold goes down to 30 sp first, so "Christopher Robin" is two whole words, not cut, inside the rings. A
     * little smaller in the picture's field.
     */
    private val sticker = run {
        val cap = if (hasPicture) 62f else 72f
        // Whole: inside the width, nothing cut off, and a name with a space broken only at a space.
        fun whole(st: IntroSticker): Boolean {
            val text = stickerText.trim()
            val first = st.lines[0].text.joinToString("").trim()
            return st.lines.all { it.width <= nameMaxW + 1f && "…" !in it.text } &&
                (st.lines.size == 1 || ' ' !in text || text.startsWith("$first "))
        }
        val first = IntroSticker(mm, stickerText, nameMaxW, cap, 40f, dp)
        if (whole(first)) first else IntroSticker(mm, stickerText, nameMaxW, cap, 30f, dp)
    }
    private val brow: GlyphRun? = eyebrow?.let { mm.fit(it, IntroFonts.hand(22f), .06f, 22f, 16f, max(nameMaxW, .5f * w)) }
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
    private val blockW = max(sticker.width, brow?.width ?: 0f)
    /** The welcome block (the screen reader's live node, and what snow keeps away from). */
    val nameRect = Rect(focus.x - blockW / 2f - 10f * dp, browTop - 8f * dp, focus.x + blockW / 2f + 10f * dp, stickerTop + sticker.height + 10f * dp)
    /** A name that still reaches past the inner ring (only a very long one): the crest's ghost stays fainter behind it. */
    private val wide = sticker.width > 2f * RING_R[2] * ringR * .92f

    // ---- the backdrop and the season ---------------------------------------------------------------------------------------
    private val backdrop = IntroBackdrop(sc.picture, w, h, sc.align)
    /** The home screen's own scrim over its picture (FogBackground's), so the top of the screen is the home's from the start. */
    private val homeScrim = Brush.verticalGradient(
        0f to Sakura.Background.copy(alpha = .55f), .14f to Sakura.Background.copy(alpha = 0f),
        .80f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = .65f), startY = 0f, endY = h,
    )
    /** The calm field under the figure: the home's blush, almost opaque, fading in over the 13 % above [calmY]. */
    private val calmField: Brush = Sakura.Background.let { c ->
        Brush.verticalGradient(0f to c.copy(alpha = 0f), .55f to c.copy(alpha = .62f), 1f to c.copy(alpha = .90f), startY = calmY - .13f * h, endY = calmY)
    }
    private val season = IntroSeasonFx(w, h, dp, if (fx.season == IntroSeason.SAKURA) IntroSeason.SAKURA else IntroSeason.NONE, introCount(level, fx, 10, 8, 4, reduced), fx)
    private val snow = if (fx.season == IntroSeason.SNOW) PetalSnow(w, h, dp, introCount(level, fx, 22, 16, 10, reduced), arrayOf(nameRect, meterRect, skipRect)) else null
    private val drift = IntroDrift(w, h, dp, introCount(level, fx, 12, 8, 5, reduced), fx, salt = 7)

    // ---- paths, brushes, strokes -------------------------------------------------------------------------------------------
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()
    private val silhouette = sakuraSilhouette()
    private val trace = androidx.compose.ui.graphics.Path().apply {
        moveTo(PetalEdges.DOCK_SIDE_DP * dp, headerY)
        lineTo(w - PetalEdges.DOCK_SIDE_DP * dp, headerY)
        addRoundRect(androidx.compose.ui.geometry.RoundRect(dock, CornerRadius(dockCorner)))
    }
    private val traceGlow = Stroke(5f * dp, cap = StrokeCap.Round)
    private val traceCore = Stroke(1.4f * dp, cap = StrokeCap.Round)
    private val crestInk: Brush = Brush.radialGradient(
        0f to Sakura.White, .55f to Sakura.White.copy(alpha = .95f), .85f to lerpColor(Sakura.Background, Sakura.White, .3f),
        1f to lerpColor(Sakura.Background, Sakura.Primary, .25f), center = Offset.Zero, radius = 1f,
    )
    private val crestLine = Stroke(2f * dp / crestR)
    private val crestInner = Stroke(1f * dp / crestR)
    private val crestVein = Stroke(1.1f * dp / crestR, cap = StrokeCap.Round)
    private val hairline = Stroke(1f * dp)
    private val hairlineUnder = Stroke(2.6f * dp)
    /** The shock rings' strokes: 3 dp of pink, a 1.5 dp white line on its inner half. */
    private val shockPink = Stroke(3f * dp)
    private val shockWhite = Stroke(1.5f * dp)
    private val browPaint = Paint()
    /** The dock's even veil: its whole inside is cut at one alpha, so no band or bar is ever left over its contents. */
    private val cutFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.BLACK
    }
    private val dockBox = android.graphics.RectF(dock.left, dock.top, dock.right, dock.bottom)
    private val dockClip = android.graphics.Path().apply { addRoundRect(dockBox, dockCorner, dockCorner, android.graphics.Path.Direction.CW) }
    /** A soft edge one unit tall (clear above, cut below), scaled to the header's growing edge and the calm field's lifting one. */
    private val fringe = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = 1f)
    /** The calm field's own layer while it lifts (only then: the rest of the run draws it straight). */
    private val calmLayer = Paint()
    /** Where the calm field starts (its fade-in above [calmY]). */
    private val calmTop = calmY - .13f * h
    private val calmSoft = 140f * dp
    /**
     * The name's way out: a colour matrix per step that turns the ink toward petal pink and keeps white white (a straight line
     * per channel through #FFFFFF and #5E4445 → #DC9FB4), applied to the type's layer while its alpha drops. Built once, nine steps.
     */
    private val tint: Array<android.graphics.Paint> = Array(TINT_STEPS + 1) { step ->
        val p = step / TINT_STEPS.toFloat()
        val ink = Sakura.Text
        val to = Sakura.Primary
        fun ch(from: Float, target: Float): Pair<Float, Float> {
            val a = (1f - target) / (1f - from).coerceAtLeast(.01f)
            return lerp(1f, a, p) to lerp(0f, (1f - a) * 255f, p)
        }
        val (ar, br) = ch(ink.red, to.red)
        val (ag, bg) = ch(ink.green, to.green)
        val (ab, bb) = ch(ink.blue, to.blue)
        android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(ar, 0f, 0f, 0f, br, 0f, ag, 0f, 0f, bg, 0f, 0f, ab, 0f, bb, 0f, 0f, 0f, 1f, 0f),
            )
        }
    }
    /** The halo'd labels' fade layer (composed opaque, faded as one: ink half faded over its white halo read grey). */
    private val labelPaint = Paint()

    // ---- the landing targets, shown from the start ----------------------------------------------------------------------------
    private val ghostHeader: FloatArray
    private val ghostDock: FloatArray

    init {
        val step = 7f * dp
        val l = PetalEdges.DOCK_SIDE_DP * dp
        val r = w - PetalEdges.DOCK_SIDE_DP * dp
        val m = ((r - l) / step).toInt().coerceAtLeast(2)
        ghostHeader = FloatArray(m + 1) { lerp(l, r, it / m.toFloat()) }
        val pm = android.graphics.PathMeasure(dockClip, true)
        val k = (pm.length / step).toInt().coerceAtLeast(8)
        val pos = FloatArray(2)
        ghostDock = FloatArray(2 * k)
        for (j in 0 until k) {
            pm.getPosTan(pm.length * j / k, pos, null)
            ghostDock[2 * j] = pos[0]
            ghostDock[2 * j + 1] = pos[1]
        }
    }

    // ---- the grains ------------------------------------------------------------------------------------------------------
    private val n = petalStormCount(level, fx, reduced)
    /** The wave's own extra grains (as many again), only while it lives: several thousand grains make one body of water. */
    private val nx = if (reduced) 0 else n
    private val nw = n + nx
    private val spriteKind = when {
        fx.petals -> 1
        fx.sparkles -> 2
        else -> 0
    }
    private val spriteCount = if (spriteKind == 0) 0 else when (if (reduced) EffectLevel.SAVER else level) {
        EffectLevel.FULL -> 120; EffectLevel.BALANCED -> 80; EffectLevel.SAVER -> 48
    }
    private val radii = floatArrayOf(.55f * dp, .8f * dp, 1.1f * dp, 1.5f * dp, 2f * dp)
    /** Over a picture pink grains get white rims ([HALO]) where the picture shows (not in the calm field: there they wash out). */
    private val rimmed = hasPicture
    private fun rimAt(y: Float): Boolean = rimmed && y < calmY + 8f * dp
    private val dots = DotBatch(nw * 2 + 1600, radii)
    private val rays = when (level) { EffectLevel.FULL -> 12; EffectLevel.BALANCED -> 10; EffectLevel.SAVER -> 8 }

    // The wave's layout per grain: its role (body, crest foam or spray), where along the wave (k), how deep in it (v), and hashes.
    private val role = ByteArray(nw)
    private val wk = FloatArray(nw)
    private val wv = FloatArray(nw)
    private val ws1 = FloatArray(nw) { ih(it, 51) }
    private val ws2 = FloatArray(nw) { ih(it, 52) }
    private val ws3 = FloatArray(nw) { ih(it, 53) }
    // Where each tracked grain was when the wave broke, and the burst's kick.
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
    private val nx2 = FloatArray(n)
    private val ny2 = FloatArray(n)
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
    /** About 70 % of the first cut's size: at 3.4–5.8 dp the burst's petals read as confetti, not petals. */
    private val spriteR = FloatArray(n) { (2.4f + 1.7f * ih(it, 91)) * dp }
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

    // ---- the wave's geometry -----------------------------------------------------------------------------------------------
    /** How long the wave's remains take to subside after the break. (Before init, which lays the wave out.) */
    private val waveAfter = 420f * pace
    /** Where the wave's head is when it breaks: just past the middle, after sweeping in from the left edge. */
    private val breakX = focus.x + .06f * w
    /** The wave's grains thin out to nothing above this line: its lower edge stays 24 dp clear of the meter's card. */
    private val waveFloor = meterRect.top - 24f * dp
    private val waveSoft = 14f * dp
    /** Over a picture's calm field the wave's pink is pale on pale: its grains are 1.4 times as strong there. */
    private val waveGain = if (hasPicture) 1.4f else 1f
    /** The still-water line; set in init so the crest's breaking point sits just above the circle's centre. */
    private var waterY = focus.y + .20f * unit
    /** Where the wave breaks (the burst's centre): the crest at its head at the moment of the break. */
    var breakPoint = Offset.Zero
        private set

    // Stamp timing of the sticker (the same as drawSticker's), so each name grain puffs out as its own glyph lands.
    private val stampStep: Float
    private val glyphCount: Int

    init {
        glyphCount = sticker.lines.sumOf { it.size }
        val stagger = (if (beats.compact) 24f else if (!sticker.latin) 40f else 20f) * pace
        stampStep = if (glyphCount > 1) min(stagger, 240f * pace / (glyphCount - 1)) else 0f
        // The wave's roles: in each group (the tracked grains and the wave's own) 66 % body, 16 % crest foam, 18 % spray.
        for (i in 0 until nw) {
            val g = if (i < n) i else i - n
            val size = if (i < n) n else nx
            val u = (g + .5f) / size
            when {
                u < .66f -> {
                    role[i] = BODY
                    wk[i] = u / .66f
                    // Most grains near the surface, ever fewer deeper down: the body fades out, it has no lower edge.
                    wv[i] = ws1[i] * ws1[i] * 1.25f
                }
                u < .82f -> {
                    role[i] = FOAM
                    wk[i] = 1f - (1f - ((u - .66f) / .16f)).pow(1.8f)
                    wv[i] = 0f
                }
                else -> {
                    role[i] = SPRAY
                    wk[i] = ws1[i]
                    wv[i] = 0f
                }
            }
        }
        // The still-water line, so the head's crest breaks at (breakX, just above the centre).
        val ws = WaveState()
        setWave(beats.burst, ws)
        waterY += (focus.y - .03f * unit) - crestY(breakX, ws)
        breakPoint = Offset(breakX, crestY(breakX, ws))
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
            val ox = nx2[i] - focus.x
            val oy = ny2[i] - (stickerTop + sticker.height / 2f)
            val l = hypot(ox, oy).coerceAtLeast(1f)
            val a = 6.2832f * ih(i, 75)
            val dx = .55f * ox / l + .45f * cos(a)
            val dy = .55f * oy / l + .45f * sin(a)
            val dl = hypot(dx, dy).coerceAtLeast(.01f)
            hx[i] = dx / dl * dist
            hy[i] = dy / dl * dist
        }
        val fromX = FloatArray(n) { nx2[it] + hx[it] }
        val fromY = FloatArray(n) { ny2[it] + hy[it] }
        PetalEdges.allocate(fromX, fromY, n, PetalEdges.segments(w, h, dp, insetTop, insetBottom), ex, ey)
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
        if (ms < beats.burst) {
            val k = (ms / beats.burst.coerceAtLeast(1f)).coerceIn(0f, 1f)
            // In fast from the left edge, slowing as it rises to break.
            val e = 1f - (1f - k) * (1f - k)
            out.head = lerp(-.10f * w, breakX, e)
            out.rise = lerp(.55f, 1f, e)
            out.alpha = (ms / 90f).coerceIn(0f, 1f)
            out.spray = ((e - .30f) / .60f).coerceIn(0f, 1f)
            out.sink = 0f
        } else {
            val k = ((ms - beats.burst) / waveAfter).coerceIn(0f, 1f)
            out.head = breakX + .24f * w * k
            out.rise = 1f - .35f * k
            out.alpha = (1f - k) * (1f - k)
            out.spray = 1f
            out.sink = .05f * unit * k
        }
        // The wave fills the screen from the left edge to its peak (a short stretch off screen while it enters); its front face
        // falls steeply ahead of the peak.
        out.tail = min(-.03f * w, out.head - .55f * w)
        out.front = out.head + FACE * w
        out.span = max(1f, out.front - out.tail)
    }

    /** x in the source's desktop pixels (a 1080 px tall screen), so its harmonics keep their wavelength on a phone. */
    private fun xu(x: Float) = x / unit * 1080f
    private fun reach(x: Float, ws: WaveState) = ((x - ws.tail) / ws.span).coerceIn(0f, 1f)

    /**
     * How high the water stands above its still line at [x]: a long back that swells (flat, then rising, rounding off at the
     * peak), and in front of the peak a steep face falling to the foot. A wave's profile, not a ramp or a fin.
     */
    private fun amp(x: Float, ws: WaveState): Float {
        val a = if (x <= ws.head) {
            val r = ((x - ws.tail) / max(1f, ws.head - ws.tail)).coerceIn(0f, 1f)
            val sm = r * r * (3f - 2f * r)
            .06f + .19f * sm * sqrt(sm)
        } else {
            val f = ((x - ws.head) / (FACE * w)).coerceIn(0f, 1f)
            .25f * cos(f * PI.toFloat() / 2f).coerceAtLeast(0f).pow(.85f)
        }
        return unit * ws.rise * a
    }

    /** The crest line: the swell rising toward the head, on three summed harmonics travelling at their own speeds. */
    private fun crestY(x: Float, ws: WaveState): Float {
        val k = reach(x, ws)
        val u = xu(x)
        val t = ws.t
        // The rolling swells ride the back, not the face (there the water falls).
        val back = if (x <= ws.head) 1f else .35f
        return waterY + ws.sink - amp(x, ws) + back * (sin(u * .0082f + t * 1.15f) * unit * .030f * (.35f + .65f * k) +
            sin(u * .0181f - t * 1.85f + .8f) * unit * .024f * (.4f + .6f * k)) + sin(u * .0410f + t * 2.60f + 1.7f) * unit * .006f
    }

    private fun troughY(x: Float, ws: WaveState): Float {
        val k = reach(x, ws)
        val u = xu(x)
        val t = ws.t
        return waterY + ws.sink + amp(x, ws) * .30f + unit * .09f + sin(u * .0077f + t * 1.15f + 1.3f) * unit * .018f * (.3f + k) +
            sin(u * .0197f - t * 1.95f + 2.1f) * unit * .007f * (.22f + k)
    }

    /**
     * Grain [i] as part of the wave at [ws], only grains (no filled body): the body between the crest and the trough (pink near
     * the top, blush deeper down, each grain turning on a small circle like water), the white foam along the crest, and the spray
     * the crest throws forward off its last stretch, white turning aqua as it flies (aqua on a white halo when drawn).
     */
    private fun waveGrain(i: Int, ws: WaveState) {
        val t = ws.t
        val s1 = ws1[i]
        val s2 = ws2[i]
        val s3 = ws3[i]
        val k = wk[i]
        when (role[i]) {
            BODY -> {
                val v = wv[i]
                val bx = ws.tail + ws.span * k + (s3 - .5f) * unit * .008f
                val orb = ((1f - v).coerceAtLeast(0f) * 3.2f + 1f) * dp
                val ph = t * (4.2f + 1.6f * s3) + s2 * 6.2832f
                val c = crestY(bx, ws)
                qx = bx + cos(ph) * orb
                qy = c + (troughY(bx, ws) - c) * v + sin(ph) * orb
                val tw = .62f + .38f * sin(t * 2.6f + s3 * 6.2832f)
                // Deep grains thin out below the trough, so the body has no hard lower edge.
                val deep = 1f - ((v - .55f) / .70f).coerceIn(0f, 1f)
                // Down the face the grains thin out toward its foot: the spray carries the leading edge, not a wall of dots.
                val face = if (bx > ws.head) 1f - .85f * ((bx - ws.head) / (FACE * w)).coerceIn(0f, 1f) else 1f
                qa = (ws.alpha * (.30f + .62f * (1f - v).coerceAtLeast(0f)) * (.50f + .50f * k) * tw * deep * face).coerceAtMost(1f)
                qc = when {
                    s3 > .95f -> WHITE
                    s1 * 1.05f > v -> PINK
                    else -> BLUSH
                }
                qs = (if (s2 > .55f) 2 else 1) + (if (k > .55f && s2 > .3f) 1 else 0)
            }
            FOAM -> {
                // Along the top of the swell up to the peak (none down the face: a white line there drew an outline).
                val x = ws.tail + (ws.head + .01f * w - ws.tail) * k + sin(t * 1.4f + s2 * 6.2832f) * unit * .004f
                // The last of it pitches forward over the face as an open lip, which the spray then carries on.
                val lip = ((k - .90f) / .10f).coerceIn(0f, 1f)
                qx = x + lip * lip * unit * .07f * ws.spray
                qy = crestY(x, ws) + (s1 - .5f) * unit * .012f * (.3f + k) - unit * .004f + lip * lip * lip * unit * .05f * ws.spray
                val tw = .55f + .45f * sin(t * 3.1f + s1 * 6.2832f)
                qa = (ws.alpha * (.35f + .65f * k) * tw).coerceIn(0f, 1f)
                qc = WHITE
                qs = 2 + (if (k > .6f) 1 else 0) + (if (s2 > .75f) 1 else 0)
            }
            else -> {
                // Thrown forward and up off the crest just behind the peak, arcing over the face and falling in front of it: an
                // open plume, never a closed curl.
                val phase = (t * 1.1f + s2) % 1f
                val ox = ws.head + unit * (.05f - .08f * k * k)
                qx = ox + unit * (.08f + .22f * s3) * phase
                qy = crestY(ws.head, ws) - unit * (.02f + .08f * s1) * phase + unit * .26f * phase * phase
                qa = ws.alpha * ws.spray * (1f - phase * phase * phase) * (phase / .08f).coerceAtMost(1f) * .95f
                // Mostly white water; a share turns aqua as it flies (on a white halo when drawn).
                qc = if (phase > .40f && s3 > .55f) AQUA else WHITE
                qs = if (phase < .45f) 2 + (if (s1 > .6f) 1 else 0) else 1
            }
        }
        qa = (qa * waveGain).coerceAtMost(1f) * ((waveFloor - qy) / waveSoft).coerceIn(0f, 1f)
    }

    /** Where each tracked grain was when the wave broke, and the burst's kick that throws it out from the breaking point. */
    private fun buildWaveStart() {
        val ws = WaveState()
        setWave(beats.burst, ws)
        // At the break the wave is at full strength (the remains fade from here).
        ws.alpha = 1f
        for (i in 0 until n) {
            waveGrain(i, ws)
            w0x[i] = qx
            w0y[i] = qy
            w0c[i] = qc.toByte()
            // Tracked grains are seen leaving the break (at least .35), except those the meter's margin hides.
            w0a[i] = if (qy > waveFloor - waveSoft) qa else max(qa, .35f)
            w0s[i] = qs.toByte()
            val dx = qx - breakPoint.x
            val dy = qy - breakPoint.y
            val l = hypot(dx, dy)
            var a = if (l > unit * .02f) atan2(dy, dx) else 6.2832f * ih(i, 63)
            a += (ih(i, 64) - .5f) * .7f
            val mag = unit * (.06f + .36f * ih(i, 65).pow(1.6f))
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
            nx2[i] = px[src]
            ny2[i] = py[src]
            nGlyph[i] = gi[src]
            if (ih(i, 105) < .55f) { nCol[i] = ROSE.toByte(); nA[i] = .9f; nS[i] = 2 } else { nCol[i] = PINK.toByte(); nA[i] = .95f; nS[i] = 2 }
        }
    }

    // ---- one grain at one moment ----------------------------------------------------------------------------------------

    private fun breath(amb: Float) = .5f + .5f * sin(6.2832f * .32f * (amb - .9f) - 1.1f)

    /** How far grain [i]'s glyph has puffed it out into the halo (0 until its glyph lands). */
    private fun puff(i: Int, ms: Float): Float {
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
            tx = nx2[i] + hx[i] * p + drift(i, amb, true)
            ty = ny2[i] + hy[i] * p + drift(i, amb, false)
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
        val nxx = nx2[i] + hx[i] * p + drift(i, amb, true)
        val nyy = ny2[i] + hy[i] * p + drift(i, amb, false)
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

    /** A grain's slow drift while it rests (px), from the ambient clock. */
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

    /** A tap before the petals took off: the host's iris is the exit, and they fly quickly. */
    private fun tapped(run: IntroRun): Boolean = run.exitAt >= 0f && run.exitAt < beats.landStart

    private fun flight(run: IntroRun): Flight {
        if (!tapped(run)) return planned
        val at = run.exitAt
        val f = tapFlight
        if (f != null && f.from == at) return f
        return Flight(at, .70f * run.exitDur, true).also { tapFlight = it }
    }

    /** True once the petals have taken off for the edges (they then draw over everything). */
    fun flying(run: IntroRun): Boolean = !reduced && (run.ms >= beats.landStart || tapped(run))

    /** Grain [i] at [ms], all the way to its edge: [grain], then the flight along a gentle curve, then resting on the edge. */
    private fun petalAt(i: Int, ms: Float, amb: Float, fl: Flight) {
        if (ms < fl.from) { grain(i, ms, amb, waveNow); return }
        grain(i, fl.from, fl.from / 1000f, waveFrom)
        val sx = qx
        val sy = qy
        val sa = qa
        val sc = qc
        val ss = qs
        // Every grain is home by 80 % of the flight ([PetalStormBeats.landed]): none crosses the dock once its contents show.
        val start = fl.from + ld[i] * .22f * fl.dur
        val k = IntroEase.CubicInOut.transform(((ms - start) / (.58f * fl.dur)).coerceIn(0f, 1f))
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

    /**
     * The sky (or the picture as the home screen shows it, under the home's own scrim, with the calm field under the figure),
     * the loose petals and the season.
     */
    fun drawBackdrop(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val amb = run.amb
        val planned = !reduced && !tapped(run)
        if (hasPicture) {
            drawRect(Sakura.Background)
            // The push settles on the home screen's own crop before the calm field starts to lift off it.
            backdrop.draw(this, backdrop.zoom(ms, beats.liftStart, reduced = reduced), homeAlpha)
            drawRect(homeScrim)
            // A gentle ease-out (quadratic): the field lifts over the whole stretch, not mostly in its first 200 ms.
            drawCalm(this, if (planned) seg(ms, beats.liftStart, beats.liftLen).let { 1f - (1f - it) * (1f - it) } else 0f)
        } else {
            backdrop.draw(this, 1f)
            // The sky turns into the home's own blush as the petals leave the name, so neither the bar's line nor the end of
            // the run shows a step between the look's ground and the app's.
            val home = if (planned) seg(ms, beats.liftStart, beats.skyLen, IntroEase.CubicOut) else 0f
            if (home > 0f) drawRect(Sakura.Background, alpha = home)
        }
        if (!reduced) {
            // A few loose petals once the wave has broken (the storm's leftovers); over a picture only in the calm field (pink
            // petal edges on the picture's aqua would blend to grey).
            // Gone once the petals are home: nothing drifts over the dock while it develops.
            val loose = .8f * seg(ms, beats.burst, 600f * pace) * (1f - seg(ms, beats.landStart, beats.landed - beats.landStart))
            if (loose > 0f) {
                if (hasPicture) clipRect(top = calmY) { drift.draw(this, amb, loose) } else drift.draw(this, amb, loose)
            }
        }
        val air = if (reduced) 1f else 1f - seg(ms, beats.fadeStart - 120f * pace, 120f * pace)
        season.draw(this, amb, air)
        snow?.draw(this, amb, air)
    }

    /**
     * The calm field under the figure; while it [lift]s, it gives way from the bottom (the dock) upward behind a soft edge, so the
     * picture develops from where the petals land instead of appearing all at once at the end. In a layer of its own only then.
     */
    private fun drawCalm(scope: DrawScope, lift: Float) = with(scope) {
        if (lift <= 0f) { drawRect(calmField); return@with }
        if (lift >= 1f) return@with
        // Below [edge] the field is gone; above it, it fades back in over [calmSoft].
        val edge = lerp(h + calmSoft, calmTop, lift)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, calmTop - 1f, w, h), calmLayer)
            drawRect(calmField)
            withTransform({ translate(0f, edge - calmSoft); scale(1f, calmSoft, Offset.Zero) }) {
                drawRect(fringe, Offset.Zero, Size(w, 1f), blendMode = BlendMode.DstOut)
            }
            if (edge < h) drawRect(Color.Black, Offset(0f, edge), Size(w, h - edge), blendMode = BlendMode.DstOut)
            canvas.restore()
        }
    }

    /**
     * The landing targets, faint from the start: a small HUD row (DSH MOBILE and the date) in the top bar's place, a dotted line
     * where the bar ends and a dotted outline where the dock sits. The petals complete them at the end.
     */
    fun drawGhosts(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val tap = tapped(run)
        val on = if (reduced) 1f else seg(ms, 80f * pace, 320f * pace)
        if (on <= 0f) return@with
        // The HUD row types in at full strength and, as the petals take off, both labels un-type from the right together, letter
        // by letter, ending empty on the same frame (a tap hides it). Faded instead, the half-transparent ink over the picture's
        // aqua blended to grey; wiped, a fragment of the date was left on its own.
        val shown = if (reduced) Int.MAX_VALUE else ((ms - 80f * pace) / (26f * pace)).toInt()
        val left = if (reduced) 1f else 1f - seg(ms, beats.landStart, .30f * beats.landLen)
        val brandN = min(shown, ceil(left * hudBrand.size).toInt())
        val dateN = min(shown, ceil(left * hudDate.size).toInt())
        if (!tap && (brandN > 0 || dateN > 0)) {
            val top = insetTop + 20f * dp
            val x0 = 20f * dp
            val right = w - 20f * dp - hudDate.width
            for (i in 0 until brandN) drawGlyph(hudBrand, i, Offset(x0 + hudBrand.x[i], top), Sakura.White, 1f, stroke = hudOutline)
            for (i in 0 until dateN) drawGlyph(hudDate, i, Offset(right + hudDate.x[i], top), Sakura.White, 1f, stroke = hudOutline)
            for (i in 0 until brandN) drawGlyph(hudBrand, i, Offset(x0 + hudBrand.x[i], top), Sakura.TextSecondary, 1f)
            for (i in 0 until dateN) drawGlyph(hudDate, i, Offset(right + hudDate.x[i], top), Sakura.TextSecondary, 1f)
        }
        val ghost = on * (if (tap) run.exitFade(0f) else 1f - seg(ms, beats.landStart + .70f * beats.landLen, .30f * beats.landLen))
        if (ghost > 0f) {
            val b = if (reduced) .5f else breath(run.amb)
            val a = ghost * (.62f + .14f * b)
            // Over a picture the bar's line gets a full white rim (pink edges on its aqua would blend to grey).
            val rim = if (rimAt(headerY)) (a * 1.5f).coerceAtMost(1f) else a * .7f
            for (x in ghostHeader) { dots.add(x, headerY, 2, rim, HALO); dots.add(x, headerY, 1, a, PINK) }
            var j = 0
            while (j < ghostDock.size) {
                dots.add(ghostDock[j], ghostDock[j + 1], 2, a * .7f, HALO)
                dots.add(ghostDock[j], ghostDock[j + 1], 1, a, PINK)
                j += 2
            }
            dots.flush(this)
        }
    }

    /** How far the stage (rings, crest, bloom) has left: from the land beat, or from a tap's exit. */
    private fun leave(ms: Float, run: IntroRun): Float {
        val planned = seg(ms, beats.landStart, LEAVE * beats.landLen)
        val tap = if (tapped(run)) seg(ms, run.exitAt, .35f * run.exitDur) else 0f
        return max(planned, tap)
    }

    /** The bloom behind the rings, the crest's crisp mark, the rings and the burst. */
    fun drawStage(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val amb = run.amb
        val b = if (reduced) .5f else breath(amb)
        val alive = .55f + .45f * b
        val gone = 1f - leave(ms, run)
        val ringsOn = if (reduced) 1f else seg(ms, beats.ringStart - 120f * pace, 400f * pace)
        // A soft white light behind the circle, so the crest and the name read on any ground.
        val bloom = ringsOn * gone * (.42f + .12f * b)
        if (bloom > 0f) withTransform({ translate(focus.x, focus.y); scale(1.25f * ringR, 1.25f * ringR, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = bloom)
        }
        drawCrestInk(this, ms, b, gone)
        // The name fits inside the inner ring, so the rings stay at full strength round it (they frame it, they do not cross it).
        drawRings(this, ms, b, alive, gone * ringsOn)
        if (!reduced) drawBurst(this, ms)
    }

    /**
     * The crest's crisp mark: it bleeds in under the grains as they lock, then stays as a faint watermark behind the name. A
     * compact run has no crest beat, so no mark either (a half-formed crest flashed past behind the name).
     */
    private fun drawCrestInk(scope: DrawScope, ms: Float, b: Float, gone: Float) = with(scope) {
        val ghost = if (wide) .08f else .22f
        val ink = when {
            reduced -> ghost
            beats.compact -> 0f
            else -> seg(ms, beats.crestLock - 60f * pace, 520f * pace) * lerp(1f, ghost, seg(ms, beats.morphStart, .6f * beats.morphLen))
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

    /** A dot; over a picture a pink, rose or blush one sits on a white rim ([HALO]). */
    private fun dot(x: Float, y: Float, size: Int, alpha: Float, colour: Int) {
        // The rim is more opaque than the dot, so the dot's soft edge falls on white, not on the picture.
        if (rimAt(y) && (colour == PINK || colour == ROSE || colour == BLUSH)) dots.add(x, y, (size + 1).coerceAtMost(4), (alpha * 1.6f).coerceAtMost(1f), HALO)
        dots.add(x, y, size, alpha, colour)
    }

    /**
     * Three dotted rings that switch on dot by dot and counter-rotate, a comet round the outer one, all on one breath. Every dot
     * and hairline has a thin white line under it, so the rings read on any ground.
     */
    private fun drawRings(scope: DrawScope, ms: Float, b: Float, alive: Float, vis: Float) = with(scope) {
        if (vis <= .01f) return@with
        val ringT = if (reduced) 10f else (ms - beats.ringStart) / (1000f * pace)
        val spinT = if (reduced) 0f else (ms - beats.ringStart) / 1000f
        if (ringT < -.05f) return@with
        val swell = 1f + .06f * (1f - vis).coerceAtMost(1f) * (if (wide) 0f else 1f)
        for (s in 0 until 3) {
            val r = RING_R[s] * ringR * (1f + .014f * b) * swell
            val count = RING_N[s]
            for (i in 0 until count) {
                val appear = ((ringT - (i.toFloat() / count) * .5f) / .24f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                if (appear <= .01f) continue
                val a = (i.toFloat() / count) * 6.2832f + RING_SPIN[s] * spinT + .35f * s
                val x = focus.x + cos(a) * r
                val y = focus.y + sin(a) * r
                val size = RING_SIZE[s] - (if (appear < .5f) 1 else 0)
                val al = RING_A[s] * appear * alive * vis
                dots.add(x, y, size + 1, (al * 1.5f).coerceAtMost(1f), HALO)
                dots.add(x, y, size, al, RING_C[s])
            }
        }
        // The comet: 20 dots racing round the outer ring, white over a pink under-dot (none in the still: nothing in flight).
        if (!reduced) {
            val head = spinT * 1.5f
            val cr = ringR * (1f + .014f * b) * swell
            val on = (ringT / .4f).coerceIn(0f, 1f)
            for (i in 0 until 20) {
                val a = head - i * .032f
                val fade = (1f - i / 20f) * .95f * on * vis
                val x = focus.x + cos(a) * cr
                val y = focus.y + sin(a) * cr
                if (i < 3) {
                    dot(x, y, 4, fade * .35f, PINK)
                    dots.add(x, y, 3, fade, WHITE)
                } else {
                    dots.add(x, y, (if (i < 9) 3 else 2) + 1, fade * .9f, HALO)
                    dots.add(x, y, if (i < 9) 3 else 2, fade * .85f, PINK)
                }
            }
        }
        dots.flush(this)
        val all = if (reduced) 1f else (ringT / .3f).coerceIn(0f, 1f)
        for (s in 0 until 2) {
            val a = .22f * alive * all * vis
            drawCircle(Sakura.White, RING_R[s] * ringR * swell, focus, alpha = (a * 2.4f).coerceAtMost(.7f), style = hairlineUnder)
            drawCircle(Sakura.Primary, RING_R[s] * ringR * swell, focus, alpha = a, style = hairline)
        }
    }

    /**
     * The break, at the crest's breaking point: the run's one hit. A white core flash (.8, gone in 180 ms), two shock rings 3 dp
     * thick (petal pink outside, a white line inside) racing out with an ease-out, and short petal-stroke rays (pink with a white
     * core) shooting out and fading within 220 ms.
     */
    private fun drawBurst(scope: DrawScope, ms: Float) = with(scope) {
        val bt = ms - beats.burst
        if (bt < 0f || bt > BURST_MS) return@with
        val punch = if (level == EffectLevel.SAVER) .8f else 1f
        val o = breakPoint
        val kf = bt / FLASH_MS
        if (kf < 1f) {
            // A wide white bloom and a hot core inside it.
            val r = unit * (.09f + .19f * IntroEase.CubicOut.transform(kf))
            val fa = .8f * (1f - kf).pow(1.4f) * punch
            withTransform({ translate(o.x, o.y); scale(r, r, Offset.Zero) }) {
                drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = fa)
                drawCircle(IntroUnitBloom, .42f, Offset.Zero, alpha = fa)
            }
        }
        for (ring in 0 until 2) {
            val kr = ((bt - ring * 50f) / (BURST_MS - 50f)).coerceIn(0f, 1f)
            if (kr <= 0f || kr >= 1f) continue
            val rr = unit * (.03f + (.30f - .06f * ring) * IntroEase.CubicOut.transform(kr))
            val a = (1f - kr).pow(1.2f) * punch
            // 3 dp of pink with a white line on its inner half: the white always lies on the pink, never on the sky alone.
            drawCircle(Sakura.Primary, rr, o, alpha = .95f * a, style = shockPink)
            drawCircle(Sakura.White, rr - .75f * dp, o, alpha = a, style = shockWhite)
        }
        val kRay = bt / BURST_MS
        val fade = (1f - kRay).pow(1.3f) * punch
        if (fade > .01f) {
            val out = IntroEase.CubicOut.transform(kRay)
            for (i in 0 until rays) {
                val ang = (i.toFloat() / rays) * 6.2832f + .21f + (ih(i, 121) - .5f) * .45f
                val reach = unit * (.05f + (.15f + .12f * ih(i, 122)) * out)
                // Long while they shoot, shorter as they slow.
                val len = (16f + 10f * ih(i, 123)) * dp * (1f - .55f * out)
                val cx0 = cos(ang)
                val sy0 = sin(ang)
                // A streak: its head (outer half) full width, its tail thinner and fainter; pink and white ones in turn.
                val tail = Offset(o.x + cx0 * reach, o.y + sy0 * reach)
                val mid = Offset(o.x + cx0 * (reach + .5f * len), o.y + sy0 * (reach + .5f * len))
                val head = Offset(o.x + cx0 * (reach + len), o.y + sy0 * (reach + len))
                val c = if (i % 2 == 0) Sakura.Primary else Sakura.White
                drawLine(c.copy(alpha = .55f * fade), tail, mid, 1.6f * dp, StrokeCap.Round)
                drawLine(c.copy(alpha = fade), mid, head, 3f * dp, StrokeCap.Round)
            }
        }
    }

    /** How strong the landed petals are: they fade out with the rest of the look (a tap's a little later: still arriving). */
    private fun landedOut(ms: Float, run: IntroRun, fl: Flight): Float = when {
        fl.tap -> 1f - seg(ms, run.exitAt + .55f * run.exitDur, .40f * run.exitDur)
        else -> 1f - seg(ms, beats.fadeStart + .1f * beats.fadeLen, .9f * beats.fadeLen)
    }

    /** Every grain this frame: the dots in batches, then the petal (or sparkle) sprites, and the edges' light once they land. */
    fun drawPetals(scope: DrawScope, run: IntroRun) = with(scope) {
        if (reduced) { drawResting(this); return@with }
        val ms = run.ms
        val amb = run.amb
        val fl = flight(run)
        if (ms >= fl.from) setWave(fl.from, waveFrom)
        val waveLives = ms < beats.burst + waveAfter
        if (waveLives) setWave(ms, waveNow)
        val out = landedOut(ms, run, fl)
        if (out <= 0f) return@with
        if (waveLives && hasPicture) drawWaveGlow(this, waveNow)
        drawTrace(this, ms, fl, out)
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
                // Over a picture's bare aqua rose-brown blends to grey even on a rim: the flight is pink.
                dot(qx, qy, qs, a, if (rimmed && qc == ROSE && ms >= fl.from) PINK else qc)
            }
        }
        // The wave's own grains, while it lives (they make it a body of water; they subside after the break).
        if (waveLives) for (i in n until nw) {
            waveGrain(i, waveNow)
            if (qa <= .02f) continue
            if (qc == AQUA) {
                dots.add(qx, qy, (qs + 1).coerceAtMost(4), qa, WHITE)
                dots.add(qx, qy, qs, qa, qc)
            } else {
                dot(qx, qy, qs, qa, qc)
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
                    if (rimAt(spY[j]) && !spriteWhite[i]) withTransform({ scale(1.3f, 1.18f, Offset.Zero) }) { drawPath(petal, Sakura.White, alpha = spA[j] * .85f) }
                    drawPath(petal, col, alpha = spA[j] * (if (spriteWhite[i]) .95f else .9f))
                }
            } else {
                introSparkle(sparkle, Offset(spX[j], spY[j]), r * .8f, if (spriteWhite[i]) Sakura.White else Sakura.Aqua, spA[j], spin * .3f)
            }
        }
    }

    /**
     * Over a picture a soft white light lies behind the wave's body (a row of blooms along it), so its pink grains read on the
     * calm field's blush instead of pale on pale. Kept above the meter like the grains.
     */
    private fun drawWaveGlow(scope: DrawScope, ws: WaveState) = with(scope) {
        if (ws.alpha <= .02f) return@with
        val right = min(ws.head + .04f * w, w + 20f * dp)
        val from = max(ws.tail, -20f * dp)
        if (right <= from) return@with
        for (j in 0 until GLOW_N) {
            val x = lerp(from, right, (j + .5f) / GLOW_N)
            val top = crestY(x, ws)
            val bottom = min(troughY(x, ws), waveFloor - waveSoft)
            if (bottom <= top) continue
            val r = (bottom - top) * .75f + 26f * dp
            val y = (top + bottom) / 2f
            val a = .42f * ws.alpha * (.45f + .55f * reach(x, ws))
            withTransform({ translate(x, y); scale(r, r * .62f, Offset.Zero) }) { drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = a) }
        }
    }

    /** The still's few resting petals: lying on the lower arc of the outer ring, nothing in flight. */
    private fun drawResting(scope: DrawScope) = with(scope) {
        if (spriteKind != 1) return@with
        for (j in 0 until 5) {
            val ang = (RESTING[j] * PI.toFloat() / 180f)
            val x = focus.x + cos(ang) * ringR * 1.06f
            val y = focus.y + sin(ang) * ringR * 1.06f
            val r = (4.4f + 1.2f * ih(j, 131)) * dp
            val white = j % 2 == 1
            withTransform({ translate(x, y); rotate(RESTING[j] + 90f + 40f * (ih(j, 132) - .5f), Offset.Zero); scale(.72f * r, r, Offset.Zero) }) {
                if (rimAt(y) && !white) withTransform({ scale(1.3f, 1.18f, Offset.Zero) }) { drawPath(petal, Sakura.White, alpha = .85f) }
                drawPath(petal, if (white) Sakura.White else Sakura.Primary, alpha = .92f)
            }
        }
    }

    /** A thin light along the edges, drawn once the grains have landed, under them. */
    private fun drawTrace(scope: DrawScope, ms: Float, fl: Flight, out: Float) = with(scope) {
        val on = seg(ms, fl.from + .80f * fl.dur, .25f * fl.dur) * out
        if (on <= .01f) return@with
        drawPath(trace, Sakura.Primary, alpha = .35f * on, style = traceGlow)
        drawPath(trace, Sakura.White, alpha = .95f * on, style = traceCore)
    }

    /**
     * The hand-off: the app appears where the petals landed. The top bar grows up out of the petal line (a soft edge rising to the
     * top of the screen); once every petal is home, the dock's inside crossfades evenly inside its petal outline (cut by inward
     * bands, the last of the look was left as a pink bar across the composer); then the rest of the look fades into the app over
     * the last ~250 ms. Drawn as DstOut cuts into the layer round the host (see PetalStormIntro), so they show the app underneath.
     * A tap's exit is the host's iris instead; the still has none.
     */
    fun drawReveal(scope: DrawScope, run: IntroRun) = with(scope) {
        if (reduced || tapped(run)) return@with
        val ms = run.ms
        // As the petals land the dock's inside frosts white, so its crossfade into the app passes through white: from the blush,
        // the app's aqua button came up as a cool grey-lilac.
        val frostFrom = beats.landStart + .55f * beats.landLen
        val frost = seg(ms, frostFrom, beats.landed - frostFrom)
        if (frost > 0f) drawRoundRect(Sakura.White, Offset(dock.left, dock.top), dock.size, CornerRadius(dockCorner), alpha = .88f * frost)
        if (ms < beats.revealAt) return@with
        val kh = IntroEase.CubicOut.transform(seg(ms, beats.revealAt, beats.revealLen))
        if (kh > 0f) {
            val soft = 22f * dp
            val edge = lerp(headerY, -soft, kh)
            drawRect(Color.Black, Offset(0f, edge), Size(w, headerY + 1f * dp - edge), blendMode = BlendMode.DstOut)
            withTransform({ translate(0f, edge - soft); scale(1f, soft, Offset.Zero) }) {
                drawRect(fringe, Offset.Zero, Size(w, 1f), blendMode = BlendMode.DstOut)
            }
        }
        val kd = seg(ms, beats.dockAt, beats.dockLen, IntroEase.SineInOut)
        if (kd > 0f) drawIntoCanvas { canvas ->
            // One even cut over the whole inside: the dock's pill, buttons and placeholder come up together, under nothing.
            cutFill.alpha = (kd * 255f).roundToInt()
            canvas.nativeCanvas.drawRoundRect(dockBox, dockCorner, dockCorner, cutFill)
        }
        val f = seg(ms, beats.fadeStart, beats.fadeLen, IntroEase.SineInOut)
        if (f > 0f) drawRect(Color.Black, alpha = f, blendMode = BlendMode.DstOut)
    }

    /** Where the name's type is drawn, with room for its outline and shadow (its tint layer's bounds). */
    private val typeBounds = android.graphics.RectF(
        nameRect.left - .3f * sticker.fontPx, nameRect.top - .3f * sticker.fontPx, nameRect.right + .3f * sticker.fontPx, nameRect.bottom + .3f * sticker.fontPx,
    )

    /**
     * 歡迎回來 and the name sticker. As the petals take off (or a tap's exit starts) the type turns petal pink at once and fades
     * out within [PetalStormBeats.typeOutLen]: its grains are already carrying the name away, and the ink never shows half
     * faded over the pink as a muddy grey-mauve.
     */
    fun drawType(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val tap = tapped(run)
        val p = when {
            reduced -> 0f
            tap -> seg(ms, run.exitAt, 60f)
            else -> seg(ms, beats.landStart, beats.typeTintLen)
        }
        val a = when {
            reduced -> 1f
            tap -> run.exitFade(40f)
            else -> 1f - seg(ms, beats.landStart + 40f * pace, beats.typeOutLen - 40f * pace)
        }
        if (a <= 0f) return@with
        if (p <= 0f && a >= 1f) { drawTypeBody(this, ms); return@with }
        drawIntoCanvas { canvas ->
            val paint = tint[(p * TINT_STEPS).roundToInt().coerceIn(0, TINT_STEPS)]
            paint.alpha = (a * 255f).roundToInt().coerceIn(0, 255)
            canvas.nativeCanvas.saveLayer(typeBounds, paint)
            drawTypeBody(this, ms)
            canvas.nativeCanvas.restore()
        }
    }

    private fun drawTypeBody(scope: DrawScope, ms: Float) = with(scope) {
        drawBrow(this, ms)
        drawSticker(sticker, focus.x, stickerTop, ms, beats.stickerStart, pace, beats.compact, reduced, beats.landStart)
    }

    /** The meter's and the skip pill's alpha on the way out: gone before the first petal reaches the edges. */
    private fun chromeOut(run: IntroRun): Float = when {
        reduced -> 1f
        tapped(run) -> run.exitFade(0f)
        else -> 1f - seg(run.ms, beats.meterOutStart, beats.meterOutLen)
    }

    /**
     * [run] in the handwriting inside its white halo ([halo], then the narrower [inner] that fills the halo's inside corners), ink
     * on top: the first [count] glyphs, glyph [i] at [glyphAlpha]. Composed opaque and faded as one layer when [alpha] < 1, so
     * the ink never shows through a half-faded halo as grey.
     */
    private inline fun drawHaloRun(
        scope: DrawScope, run: GlyphRun, left: Float, top: Float, alpha: Float, halo: Stroke, inner: Stroke, count: Int = run.size,
        glyphAlpha: (Int) -> Float = { 1f },
    ) = with(scope) {
        if (alpha <= 0f || count <= 0) return@with
        val layered = alpha < 1f
        if (layered) drawIntoCanvas { canvas ->
            labelPaint.alpha = alpha
            val pad = halo.width + .3f * run.fontPx
            canvas.saveLayer(Rect(left - pad, top - pad, left + run.width + pad, top + run.height + pad), labelPaint)
        }
        val n = min(count, run.size)
        for (i in 0 until n) {
            val ga = glyphAlpha(i)
            drawGlyph(run, i, Offset(left + run.x[i], top), Sakura.White, ga, stroke = halo)
            drawGlyph(run, i, Offset(left + run.x[i], top), Sakura.White, ga, stroke = inner)
        }
        for (i in 0 until n) drawGlyph(run, i, Offset(left + run.x[i], top), Sakura.Text, glyphAlpha(i))
        if (layered) drawIntoCanvas { it.restore() }
    }

    /**
     * [run] typed in with its halo from [start], [perChar] apart (each glyph fades in over 40 ms), behind a pink block cursor that
     * blinks for [hold] ms once it is typed (as [drawTyped] does for plain type). [reduced] shows it whole.
     */
    private fun drawHaloTyped(
        scope: DrawScope, run: GlyphRun, left: Float, top: Float, alpha: Float, ms: Float, start: Float, perChar: Float, hold: Float,
        halo: Stroke, inner: Stroke,
    ) = with(scope) {
        if (alpha <= 0f || run.size == 0) return@with
        if (reduced) { drawHaloRun(this, run, left, top, alpha, halo, inner); return@with }
        val t = ms - start
        if (t < 0f) return@with
        val shown = (0 until run.size).count { t - perChar * it > 0f }
        drawHaloRun(this, run, left, top, alpha, halo, inner, shown) { i -> ((t - perChar * i) / 40f).coerceIn(0f, 1f) }
        val typed = perChar * run.size
        if (t > typed + hold || (t > typed && ((t - typed) % 320f) > 160f)) return@with
        val x = if (shown == 0) left else left + run.x[shown - 1] + run.advance[shown - 1] + .12f * run.fontPx
        val ch = .78f * run.fontPx
        drawRect(Sakura.Primary.copy(alpha = alpha), Offset(x, top + run.baseline - ch), Size(.12f * run.fontPx, ch))
    }

    /**
     * The meter on a soft white card: its status in the handwriting (花瓣集合中 with three blinking dots, then 準備好了 typed in as
     * it closes on 100 %), the 44 ticks with the counter, and under them a quiet English line saying the same (PETALS GATHERING,
     * then READY), typed in.
     */
    fun drawMeter(scope: DrawScope, run: IntroRun) = with(scope) {
        val ms = run.ms
        val on = (if (reduced) 1f else seg(ms, 160f * pace, 260f * pace)) * chromeOut(run)
        if (on <= 0f) return@with
        val b = if (reduced) .5f else breath(run.amb)
        val p = if (reduced) 1f else beats.meter(ms)
        drawRoundRect(
            Sakura.White.copy(alpha = (if (hasPicture) .70f else .50f) * on), Offset(meterRect.left, meterRect.top),
            Size(meterRect.width, meterRect.height), CornerRadius(16f * dp),
        )
        val gatherA = on * (if (reduced) 0f else 1f - seg(ms, beats.readyAt - 100f * pace, 100f * pace))
        val left = meterLeft
        if (gatherA > 0f) {
            drawHaloRun(this, captionGather, left, meterTop, gatherA, captionHalo, captionHaloInner)
            val dotY = meterTop + captionGather.baseline - .22f * captionGather.fontPx
            val lit = ((ms / 240f).toInt() % 4)
            for (d in 0 until 3) {
                val x = left + captionGather.width + captionHalo.width / 2f + (5f + 6f * d) * dp
                drawCircle(Sakura.Primary, 1.9f * dp, Offset(x, dotY), alpha = gatherA * (if (d < lit) 1f else .3f))
            }
        }
        if (reduced || ms >= beats.readyAt) {
            drawHaloTyped(this, captionReady, left, meterTop, on, ms, beats.readyAt, 45f * pace, 300f, captionHalo, captionHaloInner)
        }
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
        // The English line under the ticks, wide-tracked in the secondary ink.
        val subTop = ticksTop + tickH + subGap
        if (gatherA > 0f) drawTyped(subGather, left, subTop, Sakura.TextSecondary, gatherA, ms, 300f * pace, 24f * pace, 0f, reduced)
        if (reduced || ms >= beats.readyAt) {
            drawTyped(subReady, left, subTop, Sakura.TextSecondary, on, ms, beats.readyAt + 80f * pace, 30f * pace, 300f, reduced)
        }
    }

    /** 跳過 SKIP ›› at the bottom right (only when skippable), 跳過 in the handwriting, gone before the petals fly to the dock. */
    fun drawSkip(scope: DrawScope, run: IntroRun) = with(scope) {
        if (!skippable) return@with
        val a = (if (reduced) 1f else seg(run.ms, 200f, 200f)) * chromeOut(run)
        if (a <= 0f) return@with
        drawRoundRect(Sakura.White.copy(alpha = .62f * a), Offset(skipRect.left, skipRect.top), Size(skipRect.width, skipRect.height), CornerRadius(14f * dp))
        var x = skipLeft
        drawHaloRun(this, skipCjk, x, skipTop, a, skipHalo, skipHaloInner)
        x += skipCjk.width + 6f * dp
        drawRun(skipLatin, x, skipTop + (skipCjk.baseline - skipLatin.baseline), Sakura.Text, a)
        x += skipLatin.width + 6f * dp
        val cyy = skipTop + skipCjk.baseline - 4.5f * dp
        for (j in 0 until 2) {
            val cxx = x + j * 7f * dp
            drawLine(Sakura.Primary.copy(alpha = a), Offset(cxx, cyy - 4f * dp), Offset(cxx + 5f * dp, cyy), 1.5f * dp, StrokeCap.Round)
            drawLine(Sakura.Primary.copy(alpha = a), Offset(cxx + 5f * dp, cyy), Offset(cxx, cyy + 4f * dp), 1.5f * dp, StrokeCap.Round)
        }
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
         * straight on the picture's aqua blend to grey), then the blush and the pinks, then white, and aqua last, only ever on a
         * white halo.
         */
        val PALETTE = arrayOf(Sakura.White, Sakura.Background, Sakura.MilkTea, Sakura.Primary, Sakura.RoseBrown, Sakura.White, Sakura.Aqua)
        const val HALO = 0
        const val BLUSH = 1
        const val PINK = 3
        const val ROSE = 4
        const val WHITE = 5
        const val AQUA = 6
        const val TICKS = 44
        private const val BODY: Byte = 0
        private const val FOAM: Byte = 1
        private const val SPRAY: Byte = 2
        private const val T_OUTLINE: Byte = 0
        private const val T_VEIN: Byte = 1
        private const val T_RING: Byte = 2
        private const val T_FILL: Byte = 3
        /** The wave's front face, from the peak to its foot, as a share of the width. */
        private const val FACE = .15f
        /** The burst's shock rings and rays are gone within this (ms); its white core flash within [FLASH_MS]. */
        const val BURST_MS = 220f
        const val FLASH_MS = 180f
        /** The steps of the name's tint on its way out (see [tint]). */
        private const val TINT_STEPS = 8
        /** How many blooms light the wave from behind over a picture. */
        private const val GLOW_N = 12
        /** The meter's English line: what beat this is. */
        private const val SUB_GATHER = "PETALS GATHERING"
        private const val SUB_READY = "READY"
        /** The circle (rings, crest, bloom) leaves in this share of the flight. */
        private const val LEAVE = .35f
        private val RING_R = floatArrayOf(1f, .86f, .72f)
        private val RING_N = intArrayOf(96, 72, 120)
        private val RING_SPIN = floatArrayOf(.26f, -.34f, .44f)
        private val RING_SIZE = intArrayOf(3, 2, 2)
        private val RING_A = floatArrayOf(.92f, .72f, .56f)
        private val RING_C = intArrayOf(PINK, ROSE, PINK)
        /** Where the still's resting petals lie on the outer ring (degrees, 90 = straight down). */
        private val RESTING = floatArrayOf(28f, 62f, 104f, 141f, 166f)
    }
}
