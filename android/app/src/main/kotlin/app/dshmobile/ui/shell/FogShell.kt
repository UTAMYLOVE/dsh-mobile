package app.dshmobile.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.dshmobile.ui.fog.LocalBackdrop
import app.dshmobile.ui.fog.LocalIdle
import app.dshmobile.ui.fog.LocalPageActive
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.BlurFloor
import app.dshmobile.ui.fog.FogBackground
import app.dshmobile.ui.fog.LocalBlurFloor
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.FogMotion
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.fog.rememberBackdrop
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.tan

/**
 * The padding every page lays its scrolling content out with: clear of the status bar and the header on top, 16 dp at the
 * sides, and at the bottom enough that the page's last control, scrolled to the end, sits fully above everything floating at
 * the bottom (the dock, its attach menu, the reconnecting chip). Provided by [FogShell].
 */
val LocalPagePadding = compositionLocalOf { PaddingValues(0.dp) }

/** The band above the floating dock over which a page fades out, so nothing shows sharp through the dock's glass. */
private val DockFade = 24.dp
/** Gap between a page's last control and the top of the floating dock: the fade band plus a little air. */
private val DockGap = DockFade + 6.dp
/** The fade at the top of the page viewport, under the header (the mockup's `#stage::after`). */
private val TopFade = 12.dp
private val PageSide = 16.dp
private val PageTop = 8.dp
private val Round = RoundedCornerShape(50)

/** Where the floating stack's top and the page viewport's top are, in root pixels. Read only while drawing (never composition). */
@Stable
private class FloatingEdge {
    var stackTop by mutableFloatStateOf(Float.NaN)
    var stageTop by mutableFloatStateOf(0f)
}

/**
 * The native FOGCITY shell: the animated background, the header, the current page and the floating glass dock (composer and
 * the five tabs), always on screen. Pages that need dsh (chat, agents) show [StatusPanel] while dsh is not [MainUiState.Ready];
 * the overview and settings always work, so settings can repair a dsh that does not start. The library shows its stage-one
 * note. [connection] RECONNECTING for longer than [reconnectChipDelayMs] shows a small chip above the dock. [overlay] draws
 * above everything (later: permission and question cards). The root exposes test tags as resource ids for UiAutomator.
 *
 * Pages live in a viewport below the header, clipped there with a short fade at its top; above the floating stack (dock, attach
 * menu, reconnecting chip) they fade out, at every effect level, so text never shows sharp through the glass.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun FogShell(
    status: MainUiState,
    connection: MuxState?,
    nav: ShellNav,
    composer: ComposerUi,
    onRetry: () -> Unit,
    onExportErrorLog: () -> Unit,
    overlay: @Composable () -> Unit = {},
    /** True while [overlay] is a modal card: everything under it is hidden from accessibility services. */
    modalOverlay: Boolean = false,
    reconnectChipDelayMs: Long = 2_000,
    page: @Composable (ShellTab) -> Unit,
) {
    val backdrop = rememberBackdrop()
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // RESUMED <-> STARTED (a dialog or the shade over the screen) must not recompose the shell: only the STARTED threshold counts.
    val started by remember { derivedStateOf { lifecycleState.value.isAtLeast(Lifecycle.State.STARTED) } }
    val idle = rememberIdle(started)
    val density = LocalDensity.current
    // Composition reads only insets that do not animate frame by frame: the navigation bar, and the keyboard's target (it
    // changes once when the keyboard starts to show or hide). The dock itself follows the keyboard in layout.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeTarget = WindowInsets.imeAnimationTarget.asPaddingValues().calculateBottomPadding()
    // The floating stack's own height (chips, menu, chip and dock; no insets): changes when those change, not with the keyboard.
    var stackHeight by remember { mutableIntStateOf(0) }
    val edge = remember { FloatingEdge() }
    val blurFloor = remember { BlurFloor() }
    var attachMenu by remember { mutableStateOf(false) }
    val showReconnecting = rememberReconnectingChip(connection, reconnectChipDelayMs)
    BackHandler(enabled = attachMenu) { attachMenu = false }

    val stack: Dp = with(density) { stackHeight.toDp() }
    val padding = PaddingValues(
        start = PageSide,
        end = PageSide,
        top = PageTop,
        bottom = max(navBottom, imeTarget) + max(FogDimens.DockHeight, stack + 12.dp + DockGap),
    )
    val ready = status == MainUiState.Ready

    // Real blur on (the backdrop is provided for every glass surface); pages animate only while current and the screen is started;
    // after eight quiet seconds the ambient motion slows down.
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides started, LocalIdle provides idle.state) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .then(idle.observer),
    ) {
        FogBackground(backdrop, Modifier.fillMaxSize().testTag(Tags.SHELL_BACKGROUND))
        val hidden = if (modalOverlay) Modifier.clearAndSetSemantics {} else Modifier
        Column(Modifier.fillMaxSize().then(hidden)) {
            Header(
                nav.tab,
                Modifier
                    .testTag(Tags.SHELL_HEADER)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(Tags.SHELL_STAGE)
                    .clipToBounds()
                    .onGloballyPositioned { edge.stageTop = it.positionInRoot().y }
                    .fadeAbove(edge),
            ) {
                // A page's glass under the floating stack is faded out: it blurs only what shows above it.
                CompositionLocalProvider(LocalPagePadding provides padding, LocalBlurFloor provides blurFloor) {
                    Pages(nav.tab) { tab ->
                      // Only the current page animates; the one sliding out is already still.
                      CompositionLocalProvider(LocalPageActive provides (started && tab == nav.tab)) {
                        when {
                            tab == ShellTab.LIBRARY -> LibraryPlaceholder()
                            tab.needsDsh && !ready -> StatusPage(status, onRetry, onExportErrorLog)
                            else -> page(tab)
                        }
                      }
                    }
                }
                PageSweep(nav.tab)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TopFade)
                        .background(Brush.verticalGradient(listOf(Sakura.Background.copy(alpha = .9f), Sakura.Background.copy(alpha = 0f)))),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = FogDimens.DockSide, end = FogDimens.DockSide, bottom = 12.dp)
                .then(hidden)
                .testTag(Tags.DOCK)
                .onSizeChanged { stackHeight = it.height }
                .onGloballyPositioned {
                    edge.stackTop = it.positionInRoot().y
                    blurFloor.y = it.positionInWindow().y
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showReconnecting) ReconnectingChip(Modifier.align(Alignment.CenterHorizontally))
            if (attachMenu) {
                AttachMenu(
                    onImage = { attachMenu = false; composer.onAttachImage() },
                    onFile = { attachMenu = false; composer.onAttachFile() },
                )
            }
            Dock(
                composer = composer,
                enabled = composer.enabled && ready,
                tab = nav.tab,
                onSelect = nav::select,
                onToggleAttachMenu = { attachMenu = !attachMenu },
            )
        }
        overlay()
    }
    }
}

/** How long without a touch before the ambient motion slows down. */
private const val IdleAfterMs = 8_000L

/** [value] is true after [IdleAfterMs] without a touch (never while the screen is stopped); [observer] watches touches without consuming them. */
@Stable
private class IdleWatch {
    /** Handed to the pages as a State: only the clocks read it (never a composition), so going idle recomposes nothing. */
    val state = mutableStateOf(false)
    var value by state
    var touches = 0
    val observer: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial) // looks, never consumes
                touches++
                if (value) value = false
            }
        }
    }
}

/** One check a second, counted in the composition's own clock; the state flips only when idleness starts or ends, so nothing recomposes meanwhile. */
@Composable
private fun rememberIdle(started: Boolean): IdleWatch {
    val watch = remember { IdleWatch() }
    LaunchedEffect(started) {
        watch.value = false
        if (!started) return@LaunchedEffect
        var seen = watch.touches
        var quiet = 0L
        while (true) {
            delay(1_000)
            if (watch.touches != seen) {
                seen = watch.touches
                quiet = 0
            } else {
                quiet += 1_000
            }
            val idle = quiet >= IdleAfterMs
            if (watch.value != idle) watch.value = idle
        }
    }
    return watch
}

/**
 * Fades the content out over [DockFade] ending at the floating stack's top, transparent below it. The content is recorded once
 * into a layer: above the band it is drawn as is, and only the band itself goes through an offscreen save-layer for the mask
 * (destination-in), so it works the same with or without blur without a full-screen offscreen layer. The positions are read
 * here, at draw time, so the keyboard's animation only redraws; the mask's brush is rebuilt only when the band moves.
 */
private fun Modifier.fadeAbove(edge: FloatingEdge): Modifier = this.drawWithCache {
    val content = obtainGraphicsLayer()
    var brushFor = Float.NaN
    var brush: Brush = SolidColor(Color.Black)
    val maskPaint = Paint()
    onDrawWithContent {
        val stackTop = edge.stackTop
        if (stackTop.isNaN()) {
            drawContent()
            return@onDrawWithContent
        }
        val end = stackTop - edge.stageTop
        val start = end - DockFade.toPx()
        if (start != brushFor) {
            brushFor = start
            brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = start, endY = end)
        }
        content.record { this@onDrawWithContent.drawContent() }
        clipRect(bottom = start.coerceAtLeast(0f)) { drawLayer(content) }
        if (end > 0f) {
            val band = Rect(0f, start.coerceAtLeast(0f), size.width, end.coerceAtMost(size.height))
            if (!band.isEmpty) {
                drawContext.canvas.saveLayer(band, maskPaint)
                clipRect(band.left, band.top, band.right, band.bottom) {
                    drawLayer(content)
                    drawRect(brush, topLeft = band.topLeft, size = band.size, blendMode = BlendMode.DstIn)
                }
                drawContext.canvas.restore()
            }
        }
    }
}

/** True once [connection] has been RECONNECTING for [delayMs] without a break; a short blip never shows the chip. */
@Composable
private fun rememberReconnectingChip(connection: MuxState?, delayMs: Long): Boolean {
    val reconnecting = connection == MuxState.RECONNECTING
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(reconnecting, delayMs) {
        shown = false
        if (reconnecting) {
            delay(delayMs)
            shown = true
        }
    }
    return shown && reconnecting
}

/**
 * The current page; switching slides in depth (or fades, or cuts) as the effect level allows. The fade is the page's own alpha
 * on the switch's transition, applied to each thing the page draws (ModulateAlpha) rather than through a page-sized offscreen
 * layer for each of the two pages on screen.
 */
@Composable
private fun Pages(tab: ShellTab, content: @Composable (ShellTab) -> Unit) {
    val effects = LocalEffects.current
    AnimatedContent(
        targetState = tab,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val ms = effects.pageMs
            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            when {
                ms == 0 -> ContentTransform(EnterTransition.None, ExitTransition.None)
                effects.depthTransitions ->
                    slideInHorizontally(tween(ms, easing = FogMotion.EaseOut)) { w -> dir * w * 72 / 100 } togetherWith
                        (slideOutHorizontally(tween(ms, easing = FogMotion.EaseOut)) { w -> -dir * w * 18 / 100 } +
                            scaleOut(tween(ms, easing = FogMotion.EaseOut), targetScale = .9f))
                else -> ContentTransform(EnterTransition.None, ExitTransition.None)
            }
        },
        label = "page",
    ) { current ->
        val ms = effects.pageMs
        val alpha = transition.animateFloat(
            transitionSpec = { if (ms == 0) snap() else tween(ms, easing = if (effects.depthTransitions) FogMotion.EaseOut else LinearEasing) },
            label = "page-alpha",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                this.alpha = alpha.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) { content(current) }
    }
}

/**
 * The mockup's sweep: on every tab change (not the first show) one 2 dp white line with a white and pink glow, skewed -12°,
 * crosses the page viewport in [FogMotion.SWEEP_MS], fading in over the first 15 % and out over the rest. Only with depth
 * transitions (full and balanced; never in saver or with the system's animations off). The line exists only while it runs and
 * the frames only redraw it (the progress is read while drawing).
 */
@Composable
private fun PageSweep(tab: ShellTab) {
    val depth = LocalEffects.current.depthTransitions
    val allowed by rememberUpdatedState(depth)
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab == shown) return@LaunchedEffect
        shown = tab
        if (!allowed) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FogMotion.SWEEP_MS, easing = FogMotion.EaseOut))
    }
    LaunchedEffect(depth) { if (!depth) progress.snapTo(1f) }
    if (!depth || !progress.isRunning) return
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { testTag = Tags.PAGE_SWEEP }
            .drawWithCache {
                val look = SweepLook(density)
                onDrawBehind { look.draw(this, progress.value) }
            },
    )
}

/**
 * The sweep line's look, built once per density: a 2 dp white core and two glows (white, near; pink, far). The glows are
 * horizontal gradients sampled from a blurred band's profile (the mockup's box-shadows), so drawing them costs one gradient
 * rect each; no blur mask filter, which under the skew would be re-rasterised every frame.
 */
internal class SweepLook(density: Float) {
    private val line = 2f * density
    private val core = Brush.verticalGradient(0f to Color.Transparent, .2f to Color.White, .8f to Color.White, 1f to Color.Transparent)
    private val near = GlowBand(Color.White.copy(alpha = .9f), halfWidth = 6f * density + line / 2f, blur = 18f * density)
    private val far = GlowBand(Sakura.Primary.copy(alpha = .45f), halfWidth = 12f * density + line / 2f, blur = 40f * density)
    private val start = -10f * density
    private val skew = tan(Math.toRadians(-12.0)).toFloat()

    /** Draws the line [p] of the way across ([p] 0..1): fading in over the first 15 %, out over the rest. */
    fun draw(scope: DrawScope, p: Float) = with(scope) {
        val alpha = if (p < .15f) p / .15f else (1f - p) / .85f
        if (alpha <= 0f) return@with
        val x = start + (size.width - start) * p
        val canvas = drawContext.canvas
        canvas.save()
        // skewX about the line's own middle, like CSS transform-origin: center
        canvas.translate(x, size.height / 2f)
        canvas.skew(skew, 0f)
        canvas.translate(-x, -size.height / 2f)
        val mid = x + line / 2f
        far.draw(this, mid, alpha)
        near.draw(this, mid, alpha)
        drawRect(core, topLeft = Offset(x, 0f), size = Size(line, size.height), alpha = alpha)
        canvas.restore()
    }
}

/**
 * A vertical band of [color], [halfWidth] each side of its centre, blurred like CSS `blur` (Gaussian, sigma = blur / 2), as a
 * pre-built horizontal gradient: [Stops] samples of the blurred profile across the band's reach.
 */
private class GlowBand(color: Color, halfWidth: Float, blur: Float) {
    private val reach = halfWidth + blur * 1.25f
    private val brush: Brush

    init {
        val sigma = blur / 2f
        val stops = Array(Stops) { i ->
            val t = i / (Stops - 1f)
            val d = -reach + 2f * reach * t
            val a = .5f * (erf((halfWidth - d) / (sigma * SQRT2)) + erf((halfWidth + d) / (sigma * SQRT2)))
            t to color.copy(alpha = color.alpha * a.coerceIn(0f, 1f))
        }
        brush = Brush.horizontalGradient(*stops, startX = -reach, endX = reach)
    }

    fun draw(scope: DrawScope, centre: Float, alpha: Float) = with(scope) {
        translate(left = centre) { drawRect(brush, topLeft = Offset(-reach, 0f), size = Size(2f * reach, size.height), alpha = alpha) }
    }

    private companion object {
        const val Stops = 17
        const val SQRT2 = 1.4142135f
    }
}

/** The error function (Abramowitz and Stegun 7.1.26, error under 1.5e-7): the blur profile of a band. */
private fun erf(x: Float): Float {
    val sign = if (x < 0f) -1f else 1f
    val ax = kotlin.math.abs(x)
    val t = 1f / (1f + .3275911f * ax)
    val y = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - .284496736f) * t + .254829592f) * t * kotlin.math.exp(-ax * ax)
    return sign * y
}

@Composable
private fun StatusPage(status: MainUiState, onRetry: () -> Unit, onExportErrorLog: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current)) {
        StatusPanel(status, onRetry, onExportErrorLog)
    }
}

/** Stage one has no library yet: only the note of what is coming. */
@Composable
private fun LibraryPlaceholder() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current)) {
        app.dshmobile.ui.fog.FogPanel(title = ShellTab.LIBRARY.zh, en = ShellTab.LIBRARY.en) {
            Text(
                stringResource(R.string.shell_library_placeholder),
                color = Sakura.Text, fontSize = 15.sp, lineHeight = 24.sp,
                modifier = Modifier.testTag(Tags.LIBRARY_PLACEHOLDER),
            )
        }
    }
}

@Composable
private fun ReconnectingChip(modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(Tags.RECONNECTING)
            .semantics(mergeDescendants = true) {}
            .glass(Round)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).glow(Sakura.Aqua, 6.dp, CircleShape).background(Sakura.Aqua, CircleShape))
        Text(stringResource(R.string.shell_reconnecting), color = Sakura.Text, fontSize = 12.sp, letterSpacing = 0.06.em)
    }
}

/** "DSH MOBILE" and the time on the left; the page's name and five dots (the current one long and glowing) on the right. */
@Composable
private fun Header(tab: ShellTab, modifier: Modifier = Modifier) {
    val now = rememberMinuteClock()
    Row(
        modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.shell_brand), style = FogText.Heading.copy(fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.shell_brand_sub), style = FogText.Label.copy(fontSize = 9.sp, letterSpacing = 0.26.em), modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(now, style = FogText.Label.copy(fontFamily = FogFonts.ChakraPetch, letterSpacing = 0.12.em), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tab.zh, color = Sakura.Text, fontSize = 15.sp, letterSpacing = 0.12.em, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ShellTab.entries.forEach { t ->
                    val on = t == tab
                    Box(
                        Modifier
                            .size(width = if (on) 22.dp else 8.dp, height = 4.dp)
                            .then(if (on) Modifier.glow(Sakura.Primary, 6.dp, Round) else Modifier)
                            .background(if (on) Sakura.Primary else Sakura.RoseBrown.copy(alpha = .3f), Round),
                    )
                }
            }
        }
    }
}

private val HeaderTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm")

/** The local time, refreshed at each minute's turn. */
@Composable
private fun rememberMinuteClock(): String {
    var text by remember { mutableStateOf(LocalDateTime.now().format(HeaderTime)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            text = now.format(HeaderTime)
            delay((60 - now.second) * 1_000L - now.nano / 1_000_000)
        }
    }
    return text
}
