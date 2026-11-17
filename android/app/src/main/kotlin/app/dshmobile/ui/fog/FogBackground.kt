package app.dshmobile.ui.fog

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import app.dshmobile.ui.theme.Sakura
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.BackdropScene
import app.dshmobile.ui.theme.LocalTheme
import app.dshmobile.ui.theme.LocalThemeClock
import app.dshmobile.ui.theme.PictureStage
import app.dshmobile.ui.theme.ShellGeometry
import app.dshmobile.ui.theme.SystemBars
import app.dshmobile.ui.theme.Theme
import app.dshmobile.ui.theme.ThemeBackdrop
import app.dshmobile.ui.theme.ThemeColors
import app.dshmobile.ui.theme.themeDrawing
import app.dshmobile.ui.theme.themeLevel
import app.dshmobile.ui.theme.themes.over
import app.dshmobile.ui.theme.themes.veilFloor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object FogSemantics {
    /** True while a glass surface actually blurs what is behind it. Reported only while [exposeBlur]. */
    val Blur = SemanticsPropertyKey<Boolean>("FogBlur")
    /** True while the background (glows, grid) is being animated. */
    val Animating = SemanticsPropertyKey<Boolean>("FogAnimating")
    /** How many 粉嫩二次元 things float over the background (absent: none). */
    val Kawaii = SemanticsPropertyKey<Int>("FogKawaii")
    /** Present while the user's own picture is shown. */
    val Picture = SemanticsPropertyKey<Boolean>("FogPicture")

    /**
     * Whether glass surfaces report [Blur]. Reporting it makes every glass surface a semantics node of its own, which nothing but
     * tests reads, so it is off in the app; Robolectric tests start with it on (and may set it), so a glass surface composed
     * after a change follows the new value.
     */
    @JvmField @Volatile var exposeBlur: Boolean = "robolectric" == android.os.Build.FINGERPRINT
}

/** The background drawn into a [GraphicsLayer], which [glass] surfaces blur behind themselves, plus where that layer sits in the window. */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null

    internal fun originInWindow(): Offset? = coordinates?.takeIf { it.isAttached }?.positionInWindow()
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Debug switches for bisecting renderer trouble on test devices (the CI emulator): set before the shell is shown. Never set in
 * the app itself.
 */
object FogSwitches {
    /** Only the plain pink ground: no glows, no grids, nothing animated behind the pages. */
    @JvmField @Volatile var plainBackground: Boolean = false
}

/**
 * Seconds of animation time, advancing only while [running]; slowed to [FogMotion.IDLE_SPEED] while [idle], when it also ticks
 * only every [FogMotion.IDLE_TICK_MS] instead of every frame (an idle screen still draws its slow drift, at a fraction of the
 * redraws). [idle] is read here, in the clock, so going idle never recomposes anything. Read the returned state in a draw lambda
 * so a frame redraws without recomposing.
 */
@Composable
internal fun rememberFogClock(running: Boolean, idle: State<Boolean>): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, idle) {
        if (!running) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            if (idle.value) delay(FogMotion.IDLE_TICK_MS)
            val now = withInfiniteAnimationFrameNanos { it }
            time.floatValue += (now - last) / 1_000_000_000f * (if (idle.value) FogMotion.IDLE_SPEED else 1f)
            last = now
            FogDebug.count("clock.tick")
        }
    }
    return time
}

/** 0..1..0 over `2 * oneWay` seconds, eased in and out like CSS `alternate` + `ease-in-out`. */
private fun alternate(t: Float, oneWay: Float): Float {
    val x = (t % (2 * oneWay)) / oneWay
    val tri = if (x <= 1f) x else 2f - x
    return tri * tri * (3f - 2f * tri)
}

/**
 * What is behind the pages. A theme without a backdrop (CLASSIC) draws today's background ([ClassicBackground]); every other
 * theme draws its own ([ThemedBackground]). [picture] is the user's picture as the shell shows it (FogShell passes its one
 * hoisted picture, plan D9; the default loads it here); [geometry] says where the shell's parts are, for the theme's drawings.
 * The picture, the floating things and the avatar square are kept here, above the branch, so a switch between 原本的樣子 and a
 * theme never drops the picture for a frame or restarts the floats (eng E21).
 */
@Composable
fun FogBackground(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    picture: ImageBitmap? = rememberLookPicture(LocalLook.current),
    geometry: ShellGeometry = NoShell,
) {
    val look = LocalLook.current
    // Keyed on what Kawaii.particles reads, not the whole Look: choosing a theme (a Look field too) keeps the same floats.
    val particles = remember(look.style, look.amount, look.petals, look.sparkles, look.hearts, look.clouds) {
        FogDebug.count("background.particles")
        Kawaii.particles(look)
    }
    val crop = rememberAvatarCrop(look)
    val theme = LocalTheme.current
    val themed = theme.backdrop
    // A leaf (E3): only the background's own body depends on the theme.
    if (themed == null) ClassicBackground(backdrop, modifier, look, picture, particles)
    else ThemedBackground(themed, theme, backdrop, modifier, look, picture, particles, crop, geometry)
}

/**
 * The pink ground, three drifting glows and the two perspective grids, recorded into [backdrop]'s layer for the glass to blur.
 * The box has a layer of its own, and the glows and the grids one each, so a clock tick re-records only what moves, never the
 * shell above. With [FogSwitches.plainBackground] only the ground is drawn. (Today's body, fed the hoisted picture and floats.)
 */
@Composable
private fun ClassicBackground(backdrop: Backdrop, modifier: Modifier, look: Look, picture: ImageBitmap?, particles: List<KawaiiParticle>) {
    val effects = LocalEffects.current
    val plain = FogSwitches.plainBackground
    val animating = effects.ambient && LocalPageActive.current && !plain
    val clock = rememberFogClock(animating, LocalIdle.current)
    Box(
        modifier
            .semantics { set(FogSemantics.Animating, animating) }
            .onGloballyPositioned { backdrop.coordinates = it }
            .graphicsLayer { }
            .drawWithContent {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            },
    ) {
        Box(Modifier.fillMaxSize().background(Sakura.Background))
        if (!plain) {
            Box(Modifier.fillMaxSize().fogGlows(clock, kawaii = look.style == BackdropStyle.KAWAII))
            if (look.style == BackdropStyle.KAWAII) Box(Modifier.fillMaxSize().kawaiiDots())
            // 0.8.0 (the owner: 「不要科技感了，來一個舒服的感覺」): the perspective grids are gone; the soft glows stay.
            // 外觀主題 (2026-11-15): the user's own picture over the glows, then the 粉嫩二次元 floating things.
            picture?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, look.align.bias),
                    alpha = look.opacity / 100f, modifier = Modifier.fillMaxSize().semantics { set(FogSemantics.Picture, true) },
                )
                // Blush scrims where the header and the dock sit, so their text and pills stay readable over busy art.
                Box(Modifier.fillMaxSize().background(PictureScrim))
            }
            if (particles.isNotEmpty()) {
                Box(Modifier.fillMaxSize().kawaii(clock, particles, Kawaii.pace(look.speed), sides = picture != null).semantics { set(FogSemantics.Kawaii, particles.size) })
            }
        }
    }
}

/**
 * A theme's backdrop (plan §2.4.5, slots §3.8). Static, in one layer (recorded into [backdrop]'s layer for the glass only when
 * the theme blurs): the ground colour and [ThemeBackdrop.ground], the picture as [ThemeBackdrop.picture] places it, the veil (or
 * today's blush scrim), the generic sky floor (D9: a stage wash just strong enough that sky text stays readable over this
 * picture; nothing over a light one) and the status band floor (eng E30). Then, outside that layer, the backdrop's one motion
 * layer and the 粉嫩二次元 floats, each reading the theme clock only while it holds it (E5): a still backdrop is never redrawn by
 * a tick. [FogSwitches.plainBackground] draws only the ground colour. The drawings are built only when the scene changes (E11,
 * E22: the scene holds the effect level, never the whole Effects).
 */
@Composable
private fun ThemedBackground(
    bd: ThemeBackdrop,
    theme: Theme,
    backdrop: Backdrop,
    modifier: Modifier,
    look: Look,
    picture: ImageBitmap?,
    particles: List<KawaiiParticle>,
    crop: AvatarCrop?,
    geometry: ShellGeometry,
) {
    val c = theme.colors
    val effects = LocalEffects.current
    val plain = FogSwitches.plainBackground
    // Today's exact expression (e2e t21 reads it), whether or not this backdrop moves (eng E9).
    val animating = effects.ambient && LocalPageActive.current && !plain
    val clock = LocalThemeClock.current
    // The backdrop's own hold, and the floats' (they move over a still theme too). SAVER, reduced motion and the jank cap turn
    // effects.ambient off, so nothing holds the clock and everything shows its still.
    val backdropMoves = bd.moves && effects.ambient && !plain
    val floatsMove = particles.isNotEmpty() && effects.ambient && !plain
    clock.Hold(backdropMoves, backdrop = true)
    clock.Hold(floatsMove)
    val stage = bd.picture
    val opacity = look.opacity / 100f

    // ---- the scene ----
    var place by remember { mutableStateOf(Rect.Zero) }
    val square = remember(picture, crop) {
        picture?.let { avatarSquare(it.width, it.height, crop ?: AvatarGuess.heuristic(it.width, it.height)) }
    }
    val avatar by produceState<ImageBitmap?>(null, picture, square) {
        value = if (picture == null || square == null) null else withContext(Dispatchers.Default) {
            faceCrop(picture, AvatarPx, crop ?: AvatarGuess.heuristic(picture.width, picture.height))
        }
    }
    val faceTone by produceState<PictureTone?>(null, picture, square) {
        value = if (picture == null || square == null) null else LookPictures.faceTone(picture, square)
    }
    val pictureTone = remember(picture) { picture?.let(LookPictures::toneOf) }
    val face = remember(picture, square, place, stage, look.align) {
        if (picture == null || square == null || place.isEmpty) null else faceAt(picture, square, place, stage, look.align)
    }
    val level = effects.themeLevel
    val measurer = rememberTextMeasurer()
    val scene = remember(picture != null, look.style, face, avatar, opacity, look.align, level, geometry, look.intro, measurer, pictureTone, faceTone) {
        BackdropScene(
            hasPicture = picture != null, style = look.style, face = face, avatar = avatar, opacity = opacity, align = look.align,
            level = level, shell = geometry, intro = look.intro, measurer = measurer, pictureTone = pictureTone, faceTone = faceTone,
        )
    }
    val ground = remember(bd, scene) { bd.ground(scene) }
    val veil = remember(bd, scene) { if (scene.hasPicture) bd.veil(scene) else null }
    val motion = remember(bd, scene) { bd.motion(scene) }
    val still = remember(bd) { StillTime(bd.stillAt) }

    // ---- the floors ----
    val full = picture != null && stage !is PictureStage.InMark
    val pictureAlpha = pictureAlphaOf(stage, look.opacity)
    val sky = remember(theme, full, pictureAlpha, pictureTone) {
        if (full && !bd.floorsOwnSky) skyFloor(c, pictureAlpha, pictureTone) else 0f
    }
    val band = remember(theme, full, pictureAlpha, pictureTone, sky, veil == null) {
        if (full) statusBand(c, theme.bars ?: SystemBars.of(c), pictureAlpha, pictureTone, scrim = veil == null, sky) else null
    }

    Box(
        modifier
            .semantics { set(FogSemantics.Animating, animating) }
            .onGloballyPositioned {
                backdrop.coordinates = it
                val at = it.positionInWindow()
                val r = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                if (r != place) place = r
            },
    ) {
        // The static layer: what the glass blurs when the theme blurs; its own layer either way, so a tick never redraws it.
        Box(Modifier.fillMaxSize().then(if (theme.blurs) Modifier.recordInto(backdrop) else Modifier.graphicsLayer { })) {
            Box(Modifier.fillMaxSize().background(c.ground).then(if (plain) Modifier else Modifier.themeDrawing(ground, null)))
            if (!plain) {
                if (picture != null) {
                    when (stage) {
                        is PictureStage.Cover -> Image(
                            picture, contentDescription = null, contentScale = ContentScale.Crop,
                            alignment = BiasAlignment(0f, look.align.bias), alpha = pictureAlpha, colorFilter = stage.filter,
                            modifier = Modifier.fillMaxSize()
                                .then(if (stage.scale != 1f) Modifier.scale(stage.scale) else Modifier)
                                .semantics { set(FogSemantics.Picture, true) },
                        )
                        is PictureStage.Print -> Print(picture, stage, look.align, pictureAlpha)
                        PictureStage.InMark -> Box(Modifier.fillMaxSize().semantics { set(FogSemantics.Picture, true) })
                    }
                    Box(Modifier.fillMaxSize().then(if (veil != null) Modifier.themeDrawing(veil, null) else Modifier.background(PictureScrim)))
                }
                if (sky > 0f || (band != null && band.second > 0f)) {
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            if (sky > 0f) drawRect(c.stage.copy(alpha = sky))
                            if (band != null && band.second > 0f) {
                                // The status bar's band in this box (the shell's geometry is in window pixels).
                                val bottom = geometry.insetTop.value - place.top
                                if (bottom > 0f) drawRect(band.first.copy(alpha = band.second), size = Size(size.width, bottom + StatusBandExtra.toPx()))
                            }
                        },
                    )
                }
            }
        }
        if (!plain) {
            // The one motion layer, in its own graphics layer, with the clock only while the backdrop holds it.
            motion?.let { Box(Modifier.fillMaxSize().graphicsLayer { }.themeDrawing(it, if (backdropMoves) clock.time else still)) }
            if (particles.isNotEmpty()) {
                Box(
                    Modifier.fillMaxSize()
                        .kawaii(if (floatsMove) clock.time else StillZero, particles, Kawaii.pace(look.speed), sides = picture != null)
                        .semantics { set(FogSemantics.Kawaii, particles.size) },
                )
            }
        }
    }
}

/** The print of [PictureStage.Print]: the picture cover-cropped into the stage's box, faded by its mask in a layer of its own. */
@Composable
private fun Print(picture: ImageBitmap, stage: PictureStage.Print, align: PictureAlign, alpha: Float) {
    Box(Modifier.fillMaxSize().semantics { set(FogSemantics.Picture, true) }) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val r = printBox(stage.box, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    val p = measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .themeDrawing(stage.mask, null, front = true),
        ) {
            Image(
                picture, contentDescription = null, contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, align.bias),
                alpha = alpha, colorFilter = stage.filter, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** [box] (dp of a 360 × 780 frame) in a [w] × [h] px window: x × w/360, y × h/780 (web §6.9). */
internal fun printBox(box: Rect, w: Float, h: Float): Rect =
    Rect(box.left / PrintFrameW * w, box.top / PrintFrameH * h, box.right / PrintFrameW * w, box.bottom / PrintFrameH * h)

private const val PrintFrameW = 360f
private const val PrintFrameH = 780f

/** The picture's own alpha on [stage] at 圖片濃淡 [opacity]: Cover keeps today's opacity / 100; Print opacity / 80 (D17). */
internal fun pictureAlphaOf(stage: PictureStage, opacity: Int): Float = when (stage) {
    is PictureStage.Cover -> opacity / 100f * stage.alphaScale
    is PictureStage.Print -> stage.alphaScale * opacity / 80f
    PictureStage.InMark -> 0f
}.coerceIn(0f, 1f)

/**
 * D9's generic sky floor: the least stage-wash alpha at which [c]'s sky ink stays at 4.6:1 over the picture's darkest common
 * pixel ([tone]; unknown is black) at [pictureAlpha] over the ground. 0 for a light picture.
 */
internal fun skyFloor(c: ThemeColors, pictureAlpha: Float, tone: PictureTone?): Float =
    veilFloor(c.skyInk, c.stage, c.ground, pictureAlpha, tone, designed = 0f)

/**
 * The status band's wash (eng E30): its colour (the ground under light icons, the stage under dark ones) and the least alpha at
 * which the bar icons (white, or the system's #1F1F1F) reach 3:1 over what lies under the band with a picture shown. Under dark
 * icons the worst pixel is the picture's darkest common one ([tone]), and the band's stage wash adds to the sky floor's [sky];
 * under light icons the worst is a white pixel (the tone says nothing of the light end) with the blush [scrim] and the sky floor
 * over it.
 */
internal fun statusBand(c: ThemeColors, bars: SystemBars, pictureAlpha: Float, tone: PictureTone?, scrim: Boolean, sky: Float): Pair<Color, Float> {
    if (bars.darkStatus) {
        var under = Color.White.copy(alpha = pictureAlpha).over(c.ground)
        if (scrim) under = PictureScrimTop.over(under)
        if (sky > 0f) under = c.stage.copy(alpha = sky).over(under)
        return c.ground to veilFloor(Color.White, c.ground, under, 0f, null, designed = 0f, target = BandContrast)
    }
    val total = veilFloor(DarkIcons, c.stage, c.ground, pictureAlpha, tone, designed = 0f, target = BandContrast)
    return c.stage to if (total <= sky) 0f else 1f - (1f - total) / (1f - sky)
}

/** The system's dark bar icons. */
private val DarkIcons = Color(0xFF1F1F1F)
/** What the bar icons need over the band (graphics, WCAG 1.4.11). */
private const val BandContrast = 3f
/** The band reaches this far below the status bar (§2.4.5). */
private val StatusBandExtra = 8.dp
/** The face square cut for a backdrop (a watermark disc is at most about a third of the screen's short side). */
private const val AvatarPx = 384

/**
 * Where the avatar [square]'s centre lands in the window when [picture] is placed as [stage] places it in [place]: cover-cropped
 * with the 圖片對齊 bias (today's full-screen picture, or the print's box), then scaled about the box's centre for a Cover.
 */
private fun faceAt(picture: ImageBitmap, square: AvatarSquare, place: Rect, stage: PictureStage, align: PictureAlign): Offset {
    val dest = if (stage is PictureStage.Print) printBox(stage.box, place.width, place.height).translate(place.left, place.top) else place
    val pw = picture.width.coerceAtLeast(1).toFloat()
    val ph = picture.height.coerceAtLeast(1).toFloat()
    val s = maxOf(dest.width / pw, dest.height / ph)
    val left = dest.left + (dest.width - pw * s) / 2f
    val top = dest.top + (dest.height - ph * s) * (1f + align.bias) / 2f
    var at = Offset(left + (square.left + square.side / 2f) * s, top + (square.top + square.side / 2f) * s)
    if (stage is PictureStage.Cover && stage.scale != 1f) at = dest.center + (at - dest.center) * stage.scale
    return at
}

/** Records this box into [backdrop]'s layer for the glass to blur, then draws the layer (as ClassicBackground does). */
private fun Modifier.recordInto(backdrop: Backdrop): Modifier = this
    .graphicsLayer { }
    .drawWithContent {
        FogDebug.count("background.record")
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/** A time that never changes (a drawing's still): reading it records no snapshot read, so nothing ever redraws it. */
private class StillTime(override val value: Float) : State<Float>

private val StillZero: State<Float> = StillTime(0f)

/** A background outside the shell (a settings preview, a test): no bars, no stack. */
private val NoShell: ShellGeometry = StillTime(0f).let { z -> ShellGeometry(z, z, z, z, z, z, z) }

/** One drifting glow: a radial gradient built once at the glow's own centre, moved and scaled at draw time by the clock. */
private class GlowSpec(
    val brush: Brush,
    val radius: Float,
    val left: Float,
    val top: Float,
    val driftX: Float,
    val driftY: Float,
    val scaleTo: Float,
    val oneWay: Float,
)

private fun buildGlow(color: Color, sizeDp: Float, left: Float, top: Float, drift: Offset, scaleTo: Float, oneWay: Float, density: Float, peak: Float = .75f): GlowSpec {
    val r = sizeDp * density / 2f
    val c = Offset(r, r)
    return GlowSpec(
        Brush.radialGradient(
            0f to color.copy(alpha = peak), .55f to color.copy(alpha = peak * .507f), 1f to color.copy(alpha = 0f), center = c, radius = r,
        ),
        r, left, top, drift.x * density, drift.y * density, scaleTo, oneWay,
    )
}

private val PictureScrim = Brush.verticalGradient(
    0f to Sakura.Background.copy(alpha = .55f), .14f to Sakura.Background.copy(alpha = 0f),
    .80f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = .65f),
)

/** [PictureScrim] at its strongest under the status bar (its top stop). */
private val PictureScrimTop = Sakura.Background.copy(alpha = .55f)

/**
 * The three glows live in their own layer, so a clock tick redraws only this layer, not the chat list or dock above it. In the
 * 粉嫩 style ([kawaii]) the light is clean: aqua over blush measured as a grey-cyan stain and milk tea as a dusty smudge, so those
 * two become a white and a soft pink glow, and a pink halftone corner (the startup animation's motif) gives the empty chat some
 * structure.
 */
private fun Modifier.fogGlows(clock: State<Float>, kawaii: Boolean = false): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.glows")
        val d = density
        val w = size.width
        val h = size.height
        // In the 粉嫩 style the two pink glows are wider and fainter (same centres): at full strength they read as stains on an
        // otherwise flat field.
        val glows = arrayOf(
            if (kawaii) buildGlow(Sakura.Primary, 420f, w - 270f * d, -120f * d, Offset(-70f, 80f), 1.12f, 18f, d, peak = .15f)
            else buildGlow(Sakura.Primary, 300f, w + 90f * d - 300f * d, -60f * d, Offset(-70f, 80f), 1.12f, 18f, d),
            if (kawaii) buildGlow(Sakura.White, 260f, -110f * d, h - 120f * d - 260f * d, Offset(90f, -60f), .9f, 22f, d)
            else buildGlow(Sakura.Aqua, 260f, -110f * d, h - 120f * d - 260f * d, Offset(90f, -60f), .9f, 22f, d),
            if (kawaii) buildGlow(Sakura.Primary, 336f, w - 218f * d, h * .42f - 48f * d, Offset(-80f, -40f), 1.15f, 26f, d, peak = .15f)
            else buildGlow(Sakura.MilkTea, 240f, w + 70f * d - 240f * d, h * .42f, Offset(-80f, -40f), 1.15f, 26f, d),
        )
        val halftone = if (kawaii) Kawaii.halftone(w, h, 9f * d, 3f * d, .4f * d) else null
        onDrawBehind {
            FogDebug.count("background.glows.draw")
            halftone?.let { drawPath(it, Sakura.Primary.copy(alpha = .22f)) }
            val t = clock.value
            for (g in glows) {
                val p = alternate(t, g.oneWay)
                val s = 1f + (g.scaleTo - 1f) * p
                translate(g.left + g.driftX * p, g.top + g.driftY * p) {
                    scale(s, s, pivot = Offset(g.radius, g.radius)) { drawCircle(g.brush, g.radius, Offset(g.radius, g.radius)) }
                }
            }
        }
    }

/**
 * The 粉嫩 field's polka dots: tiny pink dots on a staggered 16 dp grid, faint, so the flat pink has a fabric-like texture. One
 * small tile repeated by a shader (a few thousand circles as a path would be redrawn every frame the glows move); its own
 * layer, which never reads the clock, so it is recorded once.
 */
private fun Modifier.kawaiiDots(): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        val pitch = (16f * density).roundToInt().coerceAtLeast(4)
        // A texture is a nicety: if the tile cannot be made (no memory, or a test renderer without bitmaps), the field is plain.
        val brush = runCatching {
            val tile = ImageBitmap(pitch, pitch)
            val paint = Paint().apply { color = Sakura.Primary; isAntiAlias = true }
            Canvas(tile).apply {
                drawCircle(Offset(pitch / 4f, pitch / 4f), 1.5f * density, paint)
                drawCircle(Offset(3f * pitch / 4f, 3f * pitch / 4f), 1.5f * density, paint)
            }
            ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        }.getOrNull()
        onDrawBehind { if (brush != null) drawRect(brush, alpha = .16f) }
    }

/**
 * The mockup's grid slab (`.grid` + `.grid i`), floor or ceiling, on a [screenW] x [screenH] screen: a slab twice the screen's
 * width (left -50%) and 42% of its height, `rotateX(68deg)` about its bottom edge under `perspective(340px)` seen from the
 * slab's centre, the ceiling being the floor mirrored (`scaleY(-1)`). Cells of 44 dp, 1 dp lines of white α.95 in a layer of
 * α.55, masked from 10% (opaque, nearest the screen edge) to 85% (gone, far away).
 *
 * A plane under a perspective camera is a 2D projective map, so [matrix] (slab pixels to screen pixels) is that map exactly
 * and [project] the same map for one point. Unlike a graphicsLayer's rotationX (whose camera sits at the transform origin and
 * whose cameraDistance is in 72-dpi "inches"), this puts the camera where the mockup's perspective-origin is.
 */
internal class GridSlab(screenW: Float, screenH: Float, density: Float, val ceiling: Boolean) {
    val width = 2f * screenW
    val height = .42f * screenH
    val cell = 44f * density
    val line = 1f * density
    private val left = -.5f * screenW
    private val top = if (ceiling) 0f else screenH - height

    // Homogeneous rows (u, v, 1) -> (nx, ny, nw); see the class doc for the geometry.
    private val c = cos(Math.toRadians(68.0)).toFloat()
    private val sn = sin(Math.toRadians(68.0)).toFloat()
    private val q = sn / (340f * density)
    private val ax = 1f; private val bx = -(width / 2f) * q; private val cx = (width / 2f) * height * q
    private val by = c - (height / 2f) * q; private val cy = (height / 2f) * (1f + height * q) + height / 2f - height * c
    private val bw = -q; private val cw = 1f + height * q

    /** Slab point ([u] across, [v] down from the far edge's side as drawn flat) to screen pixels. */
    fun project(u: Float, v: Float): Offset {
        val nw = bw * v + cw
        val x = (ax * u + bx * v + cx) / nw
        val y = (by * v + cy) / nw
        return Offset(left + x, top + if (ceiling) height - y else y)
    }

    /** How much a length at depth [v] is shortened on screen (1 at the near edge). */
    fun shrink(v: Float): Float = 1f / (bw * v + cw)

    /** [project] as a matrix, to draw the slab's lines under (3x3 projective map in a 4x4). */
    val matrix: Matrix = Matrix().apply {
        // screen x = left + nx/nw; screen y = top + ny/nw (floor) or top + height - ny/nw (ceiling)
        val rowX = floatArrayOf(ax + left * 0f, bx + left * bw, cx + left * cw)
        val rowY = if (ceiling) floatArrayOf(0f, (top + height) * bw - by, (top + height) * cw - cy)
        else floatArrayOf(0f, by + top * bw, cy + top * cw)
        values[Matrix.ScaleX] = rowX[0]; values[Matrix.SkewX] = rowX[1]; values[Matrix.TranslateX] = rowX[2]
        values[Matrix.SkewY] = rowY[0]; values[Matrix.ScaleY] = rowY[1]; values[Matrix.TranslateY] = rowY[2]
        values[Matrix.Perspective0] = 0f; values[Matrix.Perspective1] = bw; values[Matrix.Perspective2] = cw
    }

    /** The mask: 1 up to 10% from the near (bottom) edge of the slab, 0 from 85%, linear between. */
    fun fade(v: Float): Float = ((v / height - .15f) / .75f).coerceIn(0f, 1f)
}

/** White α.95 in a layer of α.55, folded into the colour (no offscreen layer for the opacity). */
private val GridLine = Color.White.copy(alpha = .95f * .55f)

/**
 * Both grids in one layer of their own. [perspective] (FULL, BALANCED): the slab's lines drawn under the projective [GridSlab.matrix],
 * the cells flowing one every 6 s while the clock runs. Otherwise (SAVER): the same lines' ends projected once and drawn flat, no
 * 3D transform. Neither needs an offscreen layer: the mask and the opacity are folded into the line colours.
 */
private fun Modifier.fogGrids(clock: State<Float>, perspective: Boolean): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.grid")
        val slabs = listOf(GridSlab(size.width, size.height, density, ceiling = true), GridSlab(size.width, size.height, density, ceiling = false))
        if (perspective) {
            val vertical = slabs.map { s -> Brush.verticalGradient(0f to Color.Transparent, .15f to Color.Transparent, .9f to GridLine, 1f to GridLine, startY = 0f, endY = s.height) }
            onDrawBehind {
                FogDebug.count("background.grid.depth")
                val t = clock.value
                slabs.forEachIndexed { i, s -> withTransform({ transform(s.matrix) }) { drawSlab(s, vertical[i], t) } }
            }
        } else {
            val flat = slabs.map { FlatGrid(it) }
            onDrawBehind {
                FogDebug.count("background.grid.flat")
                flat.forEach { it.draw(this) }
            }
        }
    }

/** One slab's lines in slab pixels (drawn under its matrix): verticals with the fade as a brush, horizontals flowing with the clock. */
private fun DrawScope.drawSlab(s: GridSlab, vertical: Brush, t: Float) {
    var u = 0f
    while (u <= s.width) {
        drawRect(vertical, Offset(u, 0f), Size(s.line, s.height))
        u += s.cell
    }
    val flow = ((t / 6f) % 1f) * s.cell
    var v = flow - s.cell
    while (v <= s.height) {
        val a = s.fade(v)
        if (a > 0f) drawRect(GridLine.copy(alpha = GridLine.alpha * a), Offset(0f, v), Size(s.width, s.line))
        v += s.cell
    }
}

/** A slab's lines with their ends projected once: flat lines on screen, the fade as one vertical brush and per-line alphas. */
private class FlatGrid(s: GridSlab) {
    private class Seg(val from: Offset, val to: Offset, val width: Float, val color: Color)
    private val segs = ArrayList<Seg>()
    private val verticalBrush: Brush
    private val verticals = ArrayList<Pair<Offset, Offset>>()
    private val line = s.line

    init {
        var u = 0f
        while (u <= s.width) {
            verticals += s.project(u, 0f) to s.project(u, s.height)
            u += s.cell
        }
        var v = 0f
        while (v <= s.height) {
            val a = s.fade(v)
            if (a > 0f) segs += Seg(s.project(0f, v), s.project(s.width, v), (s.line * s.shrink(v)).coerceAtLeast(.5f), GridLine.copy(alpha = GridLine.alpha * a))
            v += s.cell
        }
        val near = s.project(0f, s.height * .9f).y
        val far = s.project(0f, s.height * .15f).y
        verticalBrush = Brush.verticalGradient(0f to GridLine, 1f to Color.Transparent, startY = near, endY = far)
    }

    fun draw(scope: DrawScope) = with(scope) {
        for ((a, b) in verticals) drawLine(verticalBrush, a, b, line)
        for (g in segs) drawLine(g.color, g.from, g.to, g.width)
    }
}
