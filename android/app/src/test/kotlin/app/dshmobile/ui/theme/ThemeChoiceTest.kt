package app.dshmobile.ui.theme

import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主題, 顏色 and 指揮室樣式: what is stored and what the app then wears (R1-R3). */
class ThemeChoiceTest {
    @Test fun theDefaultsFollowTheIntroAndTheThemeAndWearSigilWithItsBridge() {
        val look = Look()
        assertNull(look.theme)
        assertEquals(ColorMode.ORIGINAL, look.themeColors)
        assertNull(look.warStyle)
        // 跟著開場 with the default 開場樣式 (櫻紋陣), even though 打開時播放 is off by default.
        assertFalse(look.intro.enabled)
        assertEquals(ThemeId.SIGIL, look.themeId)
        // 櫻紋陣 is a drawn look: it has only the six colours, whatever 顏色 says.
        assertEquals(ColorMode.SIX, look.colorMode)
        assertEquals(WarStyle.BRIDGE, look.warRoom)
    }

    @Test fun followingTheIntroWearsTheChosenStylesThemeWithTheIntroOnOrOff() {
        for (style in IntroStyle.entries) for (enabled in listOf(false, true)) {
            val look = Look(intro = Intro(enabled = enabled, style = style))
            assertEquals("$style enabled=$enabled", ThemeId.of(style), look.themeId)
            assertEquals(style, look.themeId.intro)
        }
    }

    @Test fun anExplicitThemeOverridesTheIntro() {
        for (theme in ThemeId.entries) for (style in IntroStyle.entries) {
            val look = Look(theme = theme, intro = Intro(enabled = true, style = style))
            assertEquals("$theme over $style", theme, look.themeId)
        }
        // 原本的樣子 is a choice of its own, not the absence of one.
        assertEquals(ThemeId.CLASSIC, Look(theme = ThemeId.CLASSIC).themeId)
    }

    @Test fun colourModeFollowsTheChoiceOnlyForTheFourCommunityThemes() {
        val community = setOf(ThemeId.WEB_SAKURA, ThemeId.WEB_NEON, ThemeId.WEB_WAVE, ThemeId.WEB_ACCESS)
        assertEquals(community, ThemeId.entries.filter { it.ownColours }.toSet())
        for (theme in ThemeId.entries) for (mode in ColorMode.entries) {
            val look = Look(theme = theme, themeColors = mode)
            val expected = if (theme in community) mode else ColorMode.SIX
            assertEquals("$theme with $mode", expected, look.colorMode)
            // The stored choice is kept as it is while a drawn theme is worn.
            assertEquals(mode, look.themeColors)
        }
        // Following the intro resolves the same way.
        assertEquals(ColorMode.ORIGINAL, Look(intro = Intro(style = IntroStyle.WEB_NEON)).colorMode)
        assertEquals(ColorMode.SIX, Look(intro = Intro(style = IntroStyle.WEB_NEON), themeColors = ColorMode.SIX).colorMode)
        assertEquals(ColorMode.SIX, Look(intro = Intro(style = IntroStyle.TUNNEL), themeColors = ColorMode.ORIGINAL).colorMode)
    }

    @Test fun eachThemeBringsItsWarRoomAndAnExplicitStyleOverridesIt() {
        val r3 = mapOf(
            ThemeId.SIGIL to WarStyle.BRIDGE, ThemeId.STARDUST to WarStyle.BRIDGE,
            ThemeId.WEB_NEON to WarStyle.BRIDGE, ThemeId.WEB_WAVE to WarStyle.BRIDGE,
            ThemeId.ONE_STROKE to WarStyle.OFFICE, ThemeId.PETAL_STORM to WarStyle.OFFICE,
            ThemeId.TUNNEL to WarStyle.LOBBY, ThemeId.WEB_SAKURA to WarStyle.LOBBY, ThemeId.WEB_ACCESS to WarStyle.LOBBY,
            ThemeId.CLASSIC to WarStyle.CLASSIC,
        )
        assertEquals(ThemeId.entries.toSet(), r3.keys)
        for ((theme, war) in r3) {
            assertEquals("$theme", war, theme.warStyle)
            assertEquals("$theme followed", war, Look(theme = theme).warRoom)
            for (explicit in WarStyle.entries) assertEquals("$theme with $explicit", explicit, Look(theme = theme, warStyle = explicit).warRoom)
        }
        // 跟著主題 under 跟著開場: the intro's theme brings the room.
        for (style in IntroStyle.entries) assertEquals("$style", ThemeId.of(style).warStyle, Look(intro = Intro(style = style)).warRoom)
    }

    @Test fun thePickerListsTheIntroOrderThenClassic() {
        assertEquals(
            listOf(
                ThemeId.SIGIL, ThemeId.STARDUST, ThemeId.PETAL_STORM, ThemeId.TUNNEL, ThemeId.ONE_STROKE,
                ThemeId.WEB_SAKURA, ThemeId.WEB_NEON, ThemeId.WEB_WAVE, ThemeId.WEB_ACCESS, ThemeId.CLASSIC,
            ),
            ThemeId.PICKER,
        )
        assertEquals(ThemeId.entries.toSet(), ThemeId.PICKER.toSet())
    }

    @Test fun storedIdsReuseTheIntroIdsAreUniqueAndNeverFollow() {
        for (style in IntroStyle.entries) {
            assertEquals(style.id, ThemeId.of(style).id)
            assertEquals(ThemeId.of(style), ThemeId.ofId(style.id))
        }
        assertEquals("classic", ThemeId.CLASSIC.id)
        assertEquals(ThemeId.CLASSIC, ThemeId.ofId("classic"))
        val ids = ThemeId.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(LookStore.FOLLOW in ids)
        assertNull(ThemeId.ofId(LookStore.FOLLOW))
        assertNull(ThemeId.ofId(null))
        assertNull(ThemeId.ofId("neon-x"))
        // 顏色 and 指揮室樣式 ids are unique too, and "follow" is never a war style.
        assertEquals(listOf("original", "six"), ColorMode.entries.map { it.id })
        assertEquals(listOf("bridge", "office", "lobby", "classic"), WarStyle.entries.map { it.id })
        assertTrue(WarStyle.entries.none { it.id == LookStore.FOLLOW })
    }
}
