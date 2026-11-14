package app.dshmobile.ui.fog

import app.dshmobile.gateway.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LookStoreTest {
    @Test fun defaultsAreTheCosyLookWithTheIntroOff() {
        val look = LookStore(InMemorySharedPreferences()).load()
        assertEquals(Look(), look)
        assertEquals(BackdropStyle.COSY, look.style)
        assertEquals(false, look.intro.enabled)
        assertEquals(false, look.hasPicture)
    }

    @Test fun everyChoiceRoundTrips() {
        val prefs = InMemorySharedPreferences()
        val look = Look(
            style = BackdropStyle.KAWAII, petals = false, sparkles = true, hearts = false, clouds = false, amount = 80, speed = 10,
            picture = 1234L, six = 60, aqua = 20, opacity = 45, align = PictureAlign.TOP,
            intro = Intro(enabled = true, name = "小雨", number = "No.0420", level = "Lv.99", welcome = "歡迎光臨", seconds = 5.5f, skippable = false, portrait = false),
        )
        LookStore(prefs).save(look)
        assertEquals(look, LookStore(prefs).load())
    }

    @Test fun strangeStoredValuesReadAsSafeOnes() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("look_style", "neon").putInt("look_amount", 500).putInt("look_speed", -3).putInt("look_opacity", 0)
            .putString("look_align", "left").putFloat("look_intro_seconds", 99f).putString("look_intro_name", "x".repeat(80))
            .apply()
        val look = LookStore(prefs).load()
        assertEquals(BackdropStyle.COSY, look.style)
        assertEquals(100, look.amount)
        assertEquals(0, look.speed)
        assertEquals(LookStore.MIN_OPACITY, look.opacity)
        assertEquals(PictureAlign.CENTER, look.align)
        assertEquals(Intro.MAX_SECONDS, look.intro.seconds)
        assertEquals(Intro.MAX_TEXT, look.intro.name.length)
    }
}
