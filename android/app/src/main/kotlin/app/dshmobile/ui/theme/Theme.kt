package app.dshmobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.dshmobile.ui.fog.IntroStyle

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

/** The Material 3 colours and shapes a theme hands to MaterialTheme (built by materialOf in ThemeColors.kt). */
@Immutable data class MaterialSkin(val scheme: ColorScheme, val shapes: Shapes)
