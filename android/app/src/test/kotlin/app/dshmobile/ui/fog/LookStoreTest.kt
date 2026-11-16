package app.dshmobile.ui.fog

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.ui.theme.ColorMode
import app.dshmobile.ui.theme.ThemeId
import app.dshmobile.ui.theme.WarStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LookStoreTest {
    @Test fun defaultsAreTheCosyLookWithTheIntroOff() {
        val look = LookStore(InMemorySharedPreferences()).load()
        assertEquals(Look(), look)
        assertEquals(BackdropStyle.COSY, look.style)
        assertEquals(false, look.intro.enabled)
        assertEquals(false, look.hasPicture)
        assertEquals(IntroStyle.SIGIL, look.intro.style)
    }

    @Test fun everyChoiceRoundTrips() {
        val prefs = InMemorySharedPreferences()
        val look = Look(
            style = BackdropStyle.KAWAII, petals = false, sparkles = true, hearts = false, clouds = false, amount = 80, speed = 10,
            picture = 1234L, six = 60, aqua = 20, opacity = 45, align = PictureAlign.TOP,
            intro = Intro(
                enabled = true, name = "小雨", number = "No.0420", level = "Lv.99", welcome = "歡迎光臨", seconds = 5.5f, skippable = false, portrait = false,
                style = IntroStyle.PETAL_STORM,
            ),
            avatar = AvatarCrop(.42f, .18f, .33f),
        )
        LookStore(prefs).save(look)
        assertEquals(look, LookStore(prefs).load())
        // Every look survives the round trip under its own id.
        for (style in IntroStyle.entries) {
            LookStore(prefs).save(look.copy(intro = look.intro.copy(style = style)))
            assertEquals(style, LookStore(prefs).load().intro.style)
            assertEquals(style.id, prefs.getString("look_intro_style", null))
        }
    }

    @Test fun strangeStoredValuesReadAsSafeOnes() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("look_style", "neon").putInt("look_amount", 500).putInt("look_speed", -3).putInt("look_opacity", 0)
            .putString("look_align", "left").putFloat("look_intro_seconds", 99f).putString("look_intro_name", "x".repeat(80))
            .putString("look_intro_style", "neon-tunnel")
            .apply()
        val look = LookStore(prefs).load()
        assertEquals(BackdropStyle.COSY, look.style)
        assertEquals(100, look.amount)
        assertEquals(0, look.speed)
        assertEquals(LookStore.MIN_OPACITY, look.opacity)
        assertEquals(PictureAlign.CENTER, look.align)
        assertEquals(Intro.MAX_SECONDS, look.intro.seconds)
        assertEquals(Intro.MAX_TEXT, look.intro.name.length)
        // A look this app does not know (an older or newer app wrote it) plays 櫻紋陣.
        assertEquals(IntroStyle.SIGIL, look.intro.style)
    }

    @Test fun theAvatarSquareIsAutomaticUntilOneIsChosenAndRoundTrips() {
        val prefs = InMemorySharedPreferences()
        assertNull(LookStore(prefs).load().avatar)
        val look = Look(picture = 7L, avatar = AvatarCrop(.3f, .22f, .4f))
        LookStore(prefs).save(look)
        assertEquals(look, LookStore(prefs).load())
        assertEquals(.3f, prefs.getFloat("look_avatar_x", -1f))
        assertEquals(.22f, prefs.getFloat("look_avatar_y", -1f))
        assertEquals(.4f, prefs.getFloat("look_avatar_size", -1f))
        // Back to 自動: the keys are gone, not left behind as a stale square.
        LookStore(prefs).save(look.copy(avatar = null))
        assertNull(LookStore(prefs).load().avatar)
        for (k in listOf("look_avatar_x", "look_avatar_y", "look_avatar_size")) assertFalse(k, prefs.contains(k))
    }

    @Test fun aMissingOrStrangeAvatarSquareReadsAsAutomatic() {
        fun stored(edit: (android.content.SharedPreferences.Editor) -> Unit): AvatarCrop? {
            val prefs = InMemorySharedPreferences()
            prefs.edit().putFloat("look_avatar_x", .5f).putFloat("look_avatar_y", .5f).putFloat("look_avatar_size", .5f).apply()
            prefs.edit().also(edit).apply()
            return LookStore(prefs).load().avatar
        }
        assertEquals(AvatarCrop(.5f, .5f, .5f), stored {})
        assertNull("one part missing", stored { it.remove("look_avatar_size") })
        assertNull("not a number", stored { it.putFloat("look_avatar_x", Float.NaN) })
        assertNull("past the picture", stored { it.putFloat("look_avatar_y", 1.5f) })
        assertNull("no size", stored { it.putFloat("look_avatar_size", 0f) })
        assertNull("too big", stored { it.putFloat("look_avatar_size", 3f) })
        assertNull("another kind of value", stored { it.putString("look_avatar_x", "middle") })
        // A square that cannot be right is not saved either.
        val prefs = InMemorySharedPreferences()
        LookStore(prefs).save(Look(avatar = AvatarCrop(Float.NaN, .5f, .5f)))
        assertNull(LookStore(prefs).load().avatar)
        assertFalse(prefs.contains("look_avatar_x"))
    }

    // 主題, 顏色 and 指揮室樣式 (R1-R3): stored as "look_theme", "look_theme_colors" and "look_war_style".
    @Test fun theThemeColoursAndWarStyleRoundTrip() {
        val prefs = InMemorySharedPreferences()
        val base = Look(picture = 5L, six = 40, intro = Intro(enabled = true, style = IntroStyle.TUNNEL))
        for (theme in ThemeId.entries + listOf<ThemeId?>(null)) for (colors in ColorMode.entries) for (war in WarStyle.entries + listOf<WarStyle?>(null)) {
            val look = base.copy(theme = theme, themeColors = colors, warStyle = war)
            LookStore(prefs).save(look)
            assertEquals("$theme $colors $war", look, LookStore(prefs).load())
        }
    }

    @Test fun theStoredValuesSayWhatTheUserSees() {
        val prefs = InMemorySharedPreferences()
        // Look() round-trips as follow / original / follow, written out rather than left missing.
        LookStore(prefs).save(Look())
        assertEquals(LookStore.FOLLOW, prefs.getString("look_theme", null))
        assertEquals("follow", prefs.getString("look_theme", null))
        assertEquals("original", prefs.getString("look_theme_colors", null))
        assertEquals("follow", prefs.getString("look_war_style", null))
        assertEquals(Look(), LookStore(prefs).load())
        LookStore(prefs).save(Look(theme = ThemeId.WEB_NEON, themeColors = ColorMode.SIX, warStyle = WarStyle.OFFICE))
        assertEquals("web-neon", prefs.getString("look_theme", null))
        assertEquals("six", prefs.getString("look_theme_colors", null))
        assertEquals("office", prefs.getString("look_war_style", null))
        LookStore(prefs).save(Look(theme = ThemeId.CLASSIC, warStyle = WarStyle.CLASSIC))
        assertEquals("classic", prefs.getString("look_theme", null))
        assertEquals("classic", prefs.getString("look_war_style", null))
        // Every theme is stored under its intro's id.
        for (theme in ThemeId.entries) {
            LookStore(prefs).save(Look(theme = theme))
            assertEquals(theme.intro?.id ?: "classic", prefs.getString("look_theme", null))
        }
    }

    @Test fun anOlderAppsFileWithoutTheKeysReadsAsTheDefaults() {
        val prefs = InMemorySharedPreferences()
        val older = Look(style = BackdropStyle.KAWAII, amount = 70, intro = Intro(style = IntroStyle.WEB_ACCESS))
        LookStore(prefs).save(older)
        prefs.edit().remove("look_theme").remove("look_theme_colors").remove("look_war_style").apply()
        val look = LookStore(prefs).load()
        assertEquals(older, look)
        assertNull(look.theme)
        assertEquals(ColorMode.ORIGINAL, look.themeColors)
        assertNull(look.warStyle)
    }

    @Test fun unknownThemeValuesReadAsFollowOriginalFollow() {
        val prefs = InMemorySharedPreferences()
        LookStore(prefs).save(Look(theme = ThemeId.SIGIL, themeColors = ColorMode.SIX, warStyle = WarStyle.LOBBY))
        prefs.edit().putString("look_theme", "neon-x").putString("look_theme_colors", "sepia").putString("look_war_style", "hall").apply()
        val look = LookStore(prefs).load()
        assertNull(look.theme)
        assertEquals(ColorMode.ORIGINAL, look.themeColors)
        assertNull(look.warStyle)
        // "follow" itself reads the same way.
        prefs.edit().putString("look_theme", "follow").putString("look_war_style", "follow").apply()
        assertNull(LookStore(prefs).load().theme)
        assertNull(LookStore(prefs).load().warStyle)
    }

    @Test fun aThemeValueOfAnotherKindReadsAsFollowAndTheRestStillLoads() {
        val prefs = InMemorySharedPreferences()
        val mine = Look(
            style = BackdropStyle.KAWAII, amount = 30, picture = 9L, intro = Intro(name = "小雨", style = IntroStyle.STARDUST),
            avatar = AvatarCrop(.4f, .3f, .5f), theme = ThemeId.WEB_WAVE, themeColors = ColorMode.SIX, warStyle = WarStyle.OFFICE,
        )
        LookStore(prefs).save(mine)
        prefs.edit().putInt("look_theme", 3).apply()
        val look = LookStore(prefs).load()
        assertNull(look.theme)
        assertEquals(mine.copy(theme = null), look)
        // The other two keys of another kind read as their defaults as well.
        prefs.edit().putBoolean("look_theme_colors", true).putLong("look_war_style", 2L).apply()
        assertEquals(mine.copy(theme = null, themeColors = ColorMode.ORIGINAL, warStyle = null), LookStore(prefs).load())
    }
}
