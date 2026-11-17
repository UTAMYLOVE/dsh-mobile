package app.dshmobile.ui.theme

import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.theme.themes.classicTheme
import app.dshmobile.ui.theme.themes.oneStrokeTheme
import app.dshmobile.ui.theme.themes.petalStormTheme
import app.dshmobile.ui.theme.themes.sigilTheme
import app.dshmobile.ui.theme.themes.stardustTheme
import app.dshmobile.ui.theme.themes.tunnelTheme
import app.dshmobile.ui.theme.themes.web.webAccessTheme
import app.dshmobile.ui.theme.themes.web.webNeonTheme
import app.dshmobile.ui.theme.themes.web.webSakuraTheme
import app.dshmobile.ui.theme.themes.web.webWaveTheme

/**
 * The ten app themes: the nine startup looks as whole-app themes, plus CLASSIC (原本的樣子, today's app, pixel for pixel).
 * [intro] is the 開場樣式 a theme comes from; its stored [id] reuses that look's id ("sigil", "web-neon", …), CLASSIC is "classic".
 */
enum class ThemeId(val intro: IntroStyle?) {
    CLASSIC(null),
    SIGIL(IntroStyle.SIGIL), STARDUST(IntroStyle.STARDUST), ONE_STROKE(IntroStyle.ONE_STROKE),
    PETAL_STORM(IntroStyle.PETAL_STORM), TUNNEL(IntroStyle.TUNNEL),
    WEB_SAKURA(IntroStyle.WEB_SAKURA), WEB_NEON(IntroStyle.WEB_NEON), WEB_WAVE(IntroStyle.WEB_WAVE), WEB_ACCESS(IntroStyle.WEB_ACCESS);

    /** Stored id: the intro's id ("sigil", "petal-storm", "web-neon", …), "classic" for CLASSIC. Never "follow". */
    val id: String get() = intro?.id ?: "classic"

    /** Only the four community looks have colours of their own (R2); the five drawn looks and CLASSIC are the six colours. */
    val ownColours: Boolean get() = intro?.web != null

    /** R3: the war room this theme brings when 指揮室樣式 is 跟著主題. */
    val warStyle: WarStyle get() = when (this) {
        SIGIL, STARDUST, WEB_NEON, WEB_WAVE -> WarStyle.BRIDGE        // 夜櫻艦橋
        ONE_STROKE, PETAL_STORM -> WarStyle.OFFICE                   // 櫻花小精靈事務所
        TUNNEL, WEB_SAKURA, WEB_ACCESS -> WarStyle.LOBBY             // 司令官大廳
        CLASSIC -> WarStyle.CLASSIC                                  // today's war room
    }

    companion object {
        /** The theme of a startup look (every look has one). */
        fun of(intro: IntroStyle): ThemeId = entries.first { it.intro == intro }

        /** The stored id's theme; null for "follow", a missing key, or a value this app does not know. */
        fun ofId(id: String?): ThemeId? = entries.firstOrNull { it.id == id }

        /** The 主題 dialog's order: the 開場樣式 list's order (IntroStyle.entries), then 原本的樣子. */
        val PICKER: List<ThemeId> = IntroStyle.entries.map(::of) + CLASSIC
    }
}

/** 顏色: the theme's own colours or the app's six. Meaningful only when [ThemeId.ownColours]. */
enum class ColorMode(val id: String) { ORIGINAL("original"), SIX("six") }

/** 指揮室樣式 (R3). CLASSIC is today's war room (agents/WarRoomPink.kt). */
enum class WarStyle(val id: String) { BRIDGE("bridge"), OFFICE("office"), LOBBY("lobby"), CLASSIC("classic") }

/** The war layout to draw. Default CLASSIC: a test or screen that provides nothing gets today's war room. */
val LocalWarStyle: ProvidableCompositionLocal<WarStyle> = staticCompositionLocalOf { WarStyle.CLASSIC }

/** dark = the area under the icons is dark → light icons. */
@Immutable data class SystemBars(val window: Color, val darkStatus: Boolean, val darkNav: Boolean) {
    companion object { fun of(c: ThemeColors) = SystemBars(c.window, c.darkWindow, c.darkWindow) }
}

/** The Material 3 colours and shapes a theme hands to MaterialTheme (built by materialOf in ThemeColors.kt). */
@Immutable data class MaterialSkin(val scheme: ColorScheme, val shapes: Shapes)

/** What FogPanel's heading row shows: the title, its English caption, the meta text, and [ice] (an aqua heading dot). */
@Immutable data class PanelTitle(val title: String?, val en: String?, val meta: String?, val ice: Boolean)

/**
 * One whole-app theme: its colours, type, frames, lines, marks, controls, backdrop and bars. CLASSIC ([Themes.Classic]) has no
 * frames, controls, backdrop or bars: every call site then runs today's code (E1). Every other theme has all of them.
 */
@Immutable
class Theme(
    val id: ThemeId,
    /** CLASSIC and the drawn five: SIX. */
    val mode: ColorMode,
    val colors: ThemeColors,
    val type: ThemeType,
    /** null ⇔ CLASSIC. Never null for the other nine. */
    val frames: ThemeFrames?,
    val edges: ThemeEdges = ThemeEdges(),
    val marks: ThemeMarks = ThemeMarks(),
    /** null only for CLASSIC. */
    val controls: ThemeControls? = null,
    /** null only for CLASSIC (FogBackground keeps today's body). */
    val backdrop: ThemeBackdrop? = null,
    /** FogPanel's heading row; null = PanelHeading (FogPanel.kt) reading marks.dot, type.label, edges.rule. */
    val heading: (@Composable (PanelTitle) -> Unit)? = null,
    /** Drawn over the pages, under the floating stack, no touch (neon lens ring, access corner marks). */
    val screen: ((ShellGeometry) -> ThemeDrawing)? = null,
    val sweep: Sweep = Sweep.Classic,
    /** null ⇔ CLASSIC (keeps enableEdgeToEdge() with its auto styles). Others: SystemBars.of(colors) unless overridden (D5). */
    val bars: SystemBars? = null,
    val material: MaterialSkin,
) {
    val classic: Boolean get() = id == ThemeId.CLASSIC
    val ownColours: Boolean get() = id.ownColours
    /** CLASSIC, or any frame with a GlassRecipe. FogShell provides LocalBackdrop (and FogBackground records) only then. */
    val blurs: Boolean = frames == null || frames.all().any { it.glass != null }
}

/** Static: a theme change recomposes everything once, like any Look change today (LocalLook is static too). */
val LocalTheme: ProvidableCompositionLocal<Theme> = staticCompositionLocalOf { Themes.Classic }

/** The worn theme's colours. */
val ThemeColors.Companion.current: ThemeColors
    @Composable @ReadOnlyComposable get() = LocalTheme.current.colors

/** Every theme, built once per (id, mode). */
object Themes {
    val Classic: Theme = classicTheme()
    private val cache = HashMap<Pair<ThemeId, ColorMode>, Theme>()

    /**
     * Built once per (id, mode); a theme without own colours has one instance (of(SIGIL, ORIGINAL) === of(SIGIL, SIX)).
     * Builders construct values only (E10): the first call runs on the main thread in onCreate.
     */
    @Synchronized fun of(id: ThemeId, mode: ColorMode): Theme {
        if (id == ThemeId.CLASSIC) return Classic
        val m = if (id.ownColours) mode else ColorMode.SIX
        return cache.getOrPut(id to m) { build(id, m) }
    }

    /** Tests only (ThemeRegistryTest's build-time check): empties the cache. */
    @VisibleForTesting @Synchronized internal fun resetForTest() { cache.clear() }

    /** The 14 distinct themes: CLASSIC, the five drawn, the four web × 2. */
    fun all(): List<Theme> = ThemeId.entries.flatMap { id ->
        if (id.ownColours) ColorMode.entries.map { of(id, it) } else listOf(of(id, ColorMode.SIX))
    }

    private fun build(id: ThemeId, mode: ColorMode): Theme = when (id) {
        ThemeId.CLASSIC -> Classic
        ThemeId.SIGIL -> sigilTheme()
        ThemeId.STARDUST -> stardustTheme()
        ThemeId.ONE_STROKE -> oneStrokeTheme()
        ThemeId.PETAL_STORM -> petalStormTheme()
        ThemeId.TUNNEL -> tunnelTheme()
        ThemeId.WEB_SAKURA -> webSakuraTheme(mode)
        ThemeId.WEB_NEON -> webNeonTheme(mode)
        ThemeId.WEB_WAVE -> webWaveTheme(mode)
        ThemeId.WEB_ACCESS -> webAccessTheme(mode)
    }
}
