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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
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
import app.dshmobile.ui.fog.LocalLook
import app.dshmobile.ui.fog.LookPictures
import app.dshmobile.ui.fog.rememberLookPicture
import app.dshmobile.ui.theme.LocalPictureBehind
import app.dshmobile.ui.theme.LocalTheme
import app.dshmobile.ui.theme.LocalThemeClock
import app.dshmobile.ui.theme.ShellGeometry
import app.dshmobile.ui.theme.Sweep
import app.dshmobile.ui.theme.ThemeClock
import app.dshmobile.ui.theme.ThemeClockDriver
import app.dshmobile.ui.theme.themeDrawing
import androidx.compose.runtime.SideEffect
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

/** A shown picture whose 5th-percentile luminance is below this is dark: the frames floor their light fills over it (plan D9). */
private const val DarkPicture = .20f
/** The band above the floating dock over which a page fades out, so nothing shows sharp through the dock's glass. */
private val DockFade = 24.dp
/** Gap between a page's last control and the top of the floating dock: the fade band plus a little air. */
private val DockGap = DockFade + 6.dp
/** The fade at the top of the page viewport, under the header (the mockup's `#stage::after`). */
private val TopFade = 16.dp
private val PageSide = 16.dp
private val PageTop = 12.dp
private val Round = RoundedCornerShape(50)

/**
 * Where the shell's parts are, in root pixels (the shell fills the window, so these are window pixels too): the floating stack's
 * top and bottom, the page viewport's top, the header's bottom, the safe-drawing insets and the navigation bar. Written by layout
 * callbacks, read only while drawing (never composition): [fadeAbove] and, through [geometry], the theme's backdrop and screen
 * drawings. [stackTop] is NaN until the dock is laid out.
 */
@Stable
private class FloatingEdge {
    private val stackTopState = mutableFloatStateOf(Float.NaN)
    private val stageTopState = mutableFloatStateOf(0f)
    var stackTop by stackTopState
    var stageTop by stageTopState
    val stackBottom = mutableFloatStateOf(Float.NaN)
    val barBottom = mutableFloatStateOf(0f)
    val insetTop = mutableFloatStateOf(0f)
    val insetBottom = mutableFloatStateOf(0f)
    val navInset = mutableFloatStateOf(0f)

    /** The same positions for the theme's drawings (plan §2.4.5). */
    val geometry = ShellGeometry(
        insetTop = insetTop, insetBottom = insetBottom, barBottom = barBottom,
        stackTop = stackTopState, stackBottom = stackBottom, navInset = navInset, stageTop = stageTopState,
    )
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
    /** 教練模式: the coach page and its settings page; shown over the chat tab while [ShellNav.coach] / [ShellNav.coachSettings]. */
    coach: (@Composable () -> Unit)? = null,
    coachSettings: (@Composable () -> Unit)? = null,
    /** The company's name for the coach bar (公司 settings); null shows the plain 公司. */
    coachTitle: String? = null,
    page: @Composable (ShellTab) -> Unit,
) {
    val backdrop = rememberBackdrop()
    val theme = LocalTheme.current
    // The one picture the whole shell shows (eng E20, E21): the background draws it and the frames know whether it is dark, so
    // the picture and the text floors change in the same frame, and a theme switch never reloads it.
    val picture = rememberLookPicture(LocalLook.current)
    val pictureBehind = picture != null && (LookPictures.toneOf(picture)?.p05 ?: 0f) < DarkPicture
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // RESUMED <-> STARTED (a dialog or the shade over the screen) must not recompose the shell: only the STARTED threshold counts.
    val started by remember { derivedStateOf { lifecycleState.value.isAtLeast(Lifecycle.State.STARTED) } }
    val idle = rememberIdle(started)
    // The theme's one frame clock (R12): driven next to the idle watch, ticking only while a drawing holds it.
    val clock = remember { ThemeClock() }
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

    // The navigation bar's height for the theme's drawings (it does not animate frame by frame; read in composition above).
    SideEffect { edge.navInset.floatValue = with(density) { navBottom.toPx() } }
    val stack: Dp = with(density) { stackHeight.toDp() }
    val padding = PaddingValues(
        start = PageSide,
        end = PageSide,
        top = PageTop,
        bottom = max(navBottom, imeTarget) + max(FogDimens.DockHeight, stack + 12.dp + DockGap),
    )
    val ready = status == MainUiState.Ready

    // Real blur on (the backdrop is provided for every glass surface) when the theme blurs: CLASSIC, or a theme with a glass frame
    // (one provider call whose value depends on it, so the switch never changes the tree); pages animate only while current and
    // the screen is started; after eight quiet seconds the ambient motion slows down.
    // An outer "not active" (the startup animation covering the screen) pauses the pages too.
    CompositionLocalProvider(
        LocalBackdrop provides (if (theme.blurs) backdrop else null),
        LocalPageActive provides (started && LocalPageActive.current),
        LocalIdle provides idle.state,
        LocalThemeClock provides clock,
        LocalPictureBehind provides pictureBehind,
    ) {
    ThemeClockDriver(clock, theme.backdrop, idle.state)
    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .then(idle.observer),
    ) {
        // The safe-drawing insets for the theme's drawings: an empty box inside them (no semantics, draws nothing).
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).onGloballyPositioned {
                val top = it.positionInRoot().y
                edge.insetTop.floatValue = top
                edge.insetBottom.floatValue = (it.findRootCoordinates().size.height - top - it.size.height).coerceAtLeast(0f)
            },
        )
        FogBackground(backdrop, Modifier.fillMaxSize().testTag(Tags.SHELL_BACKGROUND), picture, edge.geometry)
        val hidden = if (modalOverlay) Modifier.clearAndSetSemantics {} else Modifier
        val headerBottom = Modifier.onGloballyPositioned { edge.barBottom.floatValue = it.positionInRoot().y + it.size.height }
        Column(Modifier.fillMaxSize().then(hidden)) {
            if (!nav.more || (nav.coach && coach != null)) SimpleBar(
                nav, coach != null, coachTitle,
                Modifier
                    .testTag(Tags.SHELL_HEADER)
                    .then(headerBottom)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) else Header(
                nav.tab, nav.tabs,
                Modifier
                    .testTag(Tags.SHELL_HEADER)
                    .then(headerBottom)
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
                    val coachPage = if (nav.coachSettings) coachSettings else if (nav.coach) coach else null
                    if (coachPage != null) {
                        CompositionLocalProvider(LocalPageActive provides started) { coachPage() }
                    } else Pages(nav.tab) { tab ->
                      // Only the current page animates; the one sliding out is already still.
                      CompositionLocalProvider(LocalPageActive provides (started && tab == nav.tab)) {
                        when {
                            tab.needsDsh && !ready -> StatusPage(status, onRetry, onExportErrorLog)
                            else -> page(tab)
                        }
                      }
                    }
                }
                PageSweep(nav.tab)
            }
        }
        // The theme's screen frame (neon's lens ring, access's corner marks): over the pages, under the floating stack, no touch.
        // A leaf: CLASSIC and most themes have none.
        theme.screen?.let { screen ->
            val drawing = remember(theme, edge.geometry) { screen(edge.geometry) }
            Box(Modifier.fillMaxSize().clearAndSetSemantics {}.themeDrawing(drawing, null))
        }
        // The ＋ menu folds on a tap anywhere outside it (the owner: 「按空白處介面不會縮回去」).
        if (attachMenu) {
            Box(
                Modifier.fillMaxSize().testTag(Tags.ATTACH_SCRIM)
                    .pointerInput(Unit) { detectTapGestures(onTap = { attachMenu = false }) },
            )
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
                    edge.stackBottom.floatValue = it.positionInRoot().y + it.size.height
                    blurFloor.y = it.positionInWindow().y
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showReconnecting) ReconnectingChip(Modifier.align(Alignment.CenterHorizontally))
            if (attachMenu) {
                PlusSheet(
                    mode = composer.mode, assistants = composer.assistants,
                    onImage = { attachMenu = false; composer.onAttachImage() },
                    onCamera = { attachMenu = false; composer.onCamera() },
                    onFile = { attachMenu = false; composer.onAttachFile() },
                    onMode = { attachMenu = false; composer.onMode(it) },
                    onAssistant = { attachMenu = false; composer.onAssistant(it) },
                    onNewAssistant = { attachMenu = false; composer.onAssistant("") },
                )
            }
            // 教練設定 has no composer: text typed there could only go to the chat, never to the coach.
            if (!(nav.coachSettings && coachSettings != null)) Dock(
                composer = composer,
                enabled = composer.enabled && (ready || (nav.coach && coach != null)),
                tab = nav.tab,
                onSelect = nav::select,
                tabs = nav.tabs,
                onToggleAttachMenu = { if (!nav.coach) attachMenu = !attachMenu },
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
 * Fades the content in over [TopFade] under the header and out over [DockFade] ending at the floating stack's top, transparent
 * below it. The content is recorded once into a layer: between the bands it is drawn as is, and only the bands themselves go
 * through an offscreen save-layer for the mask (destination-in), so it works the same with or without blur without a
 * full-screen offscreen layer. The top band masks the content itself rather than painting blush over it: a blush band cut the
 * pink glow behind the header with a hard line. The positions are read here, at draw time, so the keyboard's animation only
 * redraws; the bottom mask's brush is rebuilt only when the band moves.
 */
private fun Modifier.fadeAbove(edge: FloatingEdge): Modifier = this.drawWithCache {
    val content = obtainGraphicsLayer()
    var brushFor = Float.NaN
    var brush: Brush = SolidColor(Color.Black)
    val top = TopFade.toPx()
    val topBrush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = top)
    val maskPaint = Paint()
    fun DrawScope.masked(band: Rect, mask: Brush) {
        if (band.isEmpty) return
        drawContext.canvas.saveLayer(band, maskPaint)
        clipRect(band.left, band.top, band.right, band.bottom) {
            drawLayer(content)
            drawRect(mask, topLeft = band.topLeft, size = band.size, blendMode = BlendMode.DstIn)
        }
        drawContext.canvas.restore()
    }
    onDrawWithContent {
        val stackTop = edge.stackTop
        val end = if (stackTop.isNaN()) Float.POSITIVE_INFINITY else stackTop - edge.stageTop
        val start = end - DockFade.toPx()
        if (!stackTop.isNaN() && start != brushFor) {
            brushFor = start
            brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = start, endY = end)
        }
        content.record { this@onDrawWithContent.drawContent() }
        val midBottom = start.coerceAtMost(size.height)
        if (midBottom > top) clipRect(top = top, bottom = midBottom) { drawLayer(content) }
        masked(Rect(0f, 0f, size.width, top.coerceAtMost(midBottom.coerceAtLeast(0f))), topBrush)
        if (end > 0f && end.isFinite()) masked(Rect(0f, start.coerceAtLeast(top), size.width, end.coerceAtMost(size.height)), brush)
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
 * the frames only redraw it (the progress is read while drawing). The worn theme's [Sweep] picks the look: [Sweep.Glow] is
 * this line with the theme's far glow colour (CLASSIC: today's pink), [Sweep.Custom] the theme's drawing over its own duration
 * with the progress as its t, [Sweep.None] nothing.
 */
@Composable
private fun PageSweep(tab: ShellTab) {
    val sweep = LocalTheme.current.sweep
    val depth = LocalEffects.current.depthTransitions
    val allowed by rememberUpdatedState(depth && sweep != Sweep.None)
    val spec by rememberUpdatedState(
        if (sweep is Sweep.Custom) tween<Float>(sweep.ms, easing = LinearEasing) else tween(FogMotion.SWEEP_MS, easing = FogMotion.EaseOut),
    )
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab == shown) return@LaunchedEffect
        shown = tab
        if (!allowed) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    LaunchedEffect(depth) { if (!depth) progress.snapTo(1f) }
    if (!depth || !progress.isRunning) return
    val line = Modifier.fillMaxSize().clearAndSetSemantics { testTag = Tags.PAGE_SWEEP }
    when (sweep) {
        is Sweep.Glow -> Box(
            line.drawWithCache {
                val look = SweepLook(density, sweep.far)
                onDrawBehind { look.draw(this, progress.value) }
            },
        )
        is Sweep.Custom -> Box(line.themeDrawing(sweep.drawing, progress.asState()))
        Sweep.None -> Unit
    }
}

/**
 * The sweep line's look, built once per density: a 2 dp white core and two glows (white, near; [far], further out: today's pink
 * unless the theme's [Sweep.Glow] names another). The glows are horizontal gradients sampled from a blurred band's profile (the
 * mockup's box-shadows), so drawing them costs one gradient rect each; no blur mask filter, which under the skew would be
 * re-rasterised every frame.
 */
internal class SweepLook(density: Float, far: Color = Sakura.Primary.copy(alpha = .45f)) {
    private val line = 2f * density
    private val core = Brush.verticalGradient(0f to Color.Transparent, .2f to Color.White, .8f to Color.White, 1f to Color.Transparent)
    private val near = GlowBand(Color.White.copy(alpha = .9f), halfWidth = 6f * density + line / 2f, blur = 18f * density)
    private val far = GlowBand(far, halfWidth = 12f * density + line / 2f, blur = 40f * density)
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

@Composable
private fun ReconnectingChip(modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(Tags.RECONNECTING)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite } // announced when it appears
            .glass(Round)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).glow(Sakura.Aqua, 6.dp, CircleShape).background(Sakura.Aqua, CircleShape))
        Text(stringResource(R.string.shell_reconnecting), color = Sakura.Text, fontSize = 12.sp, letterSpacing = 0.06.em)
    }
}

/**
 * The simple shell's top bar (0.8.0, the owner: 「更簡單、更好用」): 對話紀錄 on the left, the page's name, and on the right the
 * war room and 新對話. It replaces the brand header and the dock's tab row, so the conversation gets the height of both. On
 * the war room or Settings the right side is one 回對話.
 */
@Composable
private fun SimpleBar(nav: ShellNav, hasCoach: Boolean = false, coachTitle: String? = null, modifier: Modifier = Modifier) {
    val onChat = nav.tab == ShellTab.CHAT
    if (hasCoach && (nav.coach || nav.coachSettings)) {
        CoachBar(nav, coachTitle, modifier)
        return
    }
    // With the coach, the chat bar carries one more button (教練): every button is compact so four fit at 320 dp.
    val compact = hasCoach && onChat
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        if (onChat) BarButton(stringResource(R.string.bar_list), Tags.BAR_LIST, compact = compact) { nav.listOpen = true }
        Text(
            if (onChat) "" else nav.tab.zh, color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em,
            maxLines = 1, modifier = Modifier.weight(1f).padding(start = if (onChat) 0.dp else 8.dp),
        )
        if (onChat) {
            // Always there while the app has the coach, whatever 「打開應用程式先進教練」 says: the coach must stay reachable.
            if (hasCoach) BarButton(stringResource(R.string.bar_coach), Tags.BAR_COACH, compact = true) { nav.openCoach() }
            BarButton(ShellTab.AGENTS.zh, Tags.TAB + ShellTab.AGENTS.name, compact = compact) { nav.select(ShellTab.AGENTS) }
            BarButton(stringResource(R.string.bar_new), Tags.BAR_NEW, pink = true, compact = compact) { nav.listOpen = false; nav.openSessionId = null }
        } else {
            BarButton(stringResource(R.string.bar_back), Tags.TAB + ShellTab.CHAT.name, pink = true) { nav.select(ShellTab.CHAT) }
        }
    }
}

/** The coach's bar: its settings on the left, the title, and 自由聊天 (the ordinary chat) on the right. */
@Composable
private fun CoachBar(nav: ShellNav, title: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nav.coachSettings) {
            BarButton(stringResource(R.string.coach_back), Tags.COACH_SETTINGS_OPEN) { nav.coachSettings = false }
        } else {
            BarButton(stringResource(R.string.coach_settings_open), Tags.COACH_SETTINGS_OPEN) { nav.coachSettings = true }
        }
        Text(
            title ?: stringResource(R.string.coach_title), color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em,
            maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        BarButton(stringResource(R.string.bar_free_chat), Tags.BAR_FREE_CHAT, pink = true) { nav.select(ShellTab.CHAT) }
    }
}

@Composable
private fun BarButton(label: String, tag: String, pink: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(Round)
            .background(if (pink) Sakura.Primary.copy(alpha = .9f) else Color.White.copy(alpha = .55f), Round)
            .border(1.dp, if (pink) Color.White.copy(alpha = .9f) else Sakura.Primary.copy(alpha = .6f), Round)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = if (compact) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Ink on the pink button too: white on pink measured 2.1:1, #5E4445 is 4.2:1.
        Text(label, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** "DSH MOBILE" and the time on the left; the page's name and five dots (the current one long and glowing) on the right. */
@Composable
private fun Header(tab: ShellTab, tabs: List<ShellTab>, modifier: Modifier = Modifier) {
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
                tabs.forEach { t ->
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
