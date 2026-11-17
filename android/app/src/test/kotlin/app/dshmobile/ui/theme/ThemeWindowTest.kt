package app.dshmobile.ui.theme

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.insets.ProtectionLayout
import app.dshmobile.R
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * core C10 (R9), written against [Theme.bars] so it stays right when a theme overrides its bars (D5): the window colour and both
 * bars' icons follow the theme, the starting-window styles resolve the theme's window colour, navigation contrast stays enforced
 * (eng E29), the styles name their parent (eng E6), and an edge-to-edge dialog's bars follow the theme (owner O8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeWindowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val blush = Color(0xFFFEDFE1)

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun Window.background(): Int = (decorView.background as ColorDrawable).color

    private fun Window.lightStatus() = WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars

    private fun Window.lightNav() = WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars

    @Test fun neonsWindowIsDarkWithLightIconsAndClassicComesBackToBlush() {
        val a = activity()
        a.applyThemeWindow(Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL))
        assertEquals(Color(0xFF0B0B0E).toArgb(), a.window.background())
        assertFalse("light icons on #0B0B0E", a.window.lightStatus())
        assertFalse(a.window.lightNav())
        a.applyThemeWindow(Themes.of(ThemeId.WEB_NEON, ColorMode.SIX))
        assertEquals(Color(0xFF5E4445).toArgb(), a.window.background())
        assertFalse(a.window.lightStatus())
        a.applyThemeWindow(Themes.Classic)
        assertEquals(blush.toArgb(), a.window.background())
        assertTrue("today's dark icons on blush (system light mode)", a.window.lightStatus())
        assertTrue(a.window.lightNav())
    }

    @Test fun everyThemesWindowAndIconsFollowItsBars() {
        val a = activity()
        for (t in Themes.all()) {
            a.applyThemeWindow(t)
            val label = "${t.id} ${t.mode}"
            val bars = t.bars
            if (bars == null) {
                assertTrue("only CLASSIC has no bars: $label", t.classic)
                assertEquals(label, blush.toArgb(), a.window.background())
                assertTrue(label, a.window.lightStatus())
                assertTrue(label, a.window.lightNav())
            } else {
                assertEquals("$label window", bars.window.toArgb(), a.window.background())
                assertEquals("$label window = colors.window", t.colors.window.toArgb(), a.window.background())
                assertEquals("$label status icons", !bars.darkStatus, a.window.lightStatus())
                assertEquals("$label navigation icons", !bars.darkNav, a.window.lightNav())
            }
            // eng E29: SystemBarStyle.auto keeps the system's scrim for 3-button navigation in every theme.
            assertTrue("$label navigation contrast enforced", a.window.isNavigationBarContrastEnforced)
        }
    }

    @Test fun applyingAgainDoesNotPileUpProtectionLayouts() {
        val a = activity()
        repeat(3) { for (t in Themes.all()) a.applyThemeWindow(t) }
        val decor = a.window.decorView as ViewGroup
        val layers = (0 until decor.childCount).count { decor.getChildAt(it) is ProtectionLayout }
        assertTrue("at most one ProtectionLayout after ${3 * Themes.all().size} calls: $layers", layers <= 1)
    }

    @Test fun everyStartStyleResolvesTheThemesWindowAndBarIcons() {
        val context = rule.activity
        // Sorted ascending by id, as obtainStyledAttributes needs.
        val attrs = intArrayOf(android.R.attr.colorPrimary, android.R.attr.windowBackground, android.R.attr.windowLightStatusBar)
            .sortedArray()
        val iPrimary = attrs.indexOf(android.R.attr.colorPrimary)
        val iWindow = attrs.indexOf(android.R.attr.windowBackground)
        val iLight = attrs.indexOf(android.R.attr.windowLightStatusBar)
        for (t in Themes.all()) {
            val style = startWindowStyle(t.id, t.mode)
            val a = context.obtainStyledAttributes(style, attrs)
            try {
                val label = "${t.id} ${t.mode}"
                assertEquals("$label windowBackground", t.colors.window.toArgb(), a.getColor(iWindow, 0))
                assertEquals("$label windowLightStatusBar", !t.colors.darkWindow, a.getBoolean(iLight, t.colors.darkWindow))
                // eng E6: every start style inherits Theme.DshMobile (its colorPrimary is the app's pink).
                assertEquals("$label parent", Sakura.Primary.toArgb(), a.getColor(iPrimary, 0))
            } finally {
                a.recycle()
            }
        }
        // The dark styles are exactly the two neon ones.
        val dark = listOf(R.style.Theme_DshMobile_Start_Night, R.style.Theme_DshMobile_Start_Rose)
        for (style in listOf(R.style.Theme_DshMobile, R.style.Theme_DshMobile_Start_Archive, R.style.Theme_DshMobile_Start_Mist, R.style.Theme_DshMobile_Start_Lilac) + dark) {
            val a = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.windowLightStatusBar))
            try { assertEquals("style $style", style !in dark, a.getBoolean(0, style in dark)) } finally { a.recycle() }
        }
    }

    /** owner O8: inside an edge-to-edge dialog the theme's bars apply to the dialog's own window; CLASSIC leaves it alone. */
    @Test fun anEdgeToEdgeDialogsBarsFollowTheTheme() {
        var theme by mutableStateOf(Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL))
        var withBars by mutableStateOf(true)
        var window: Window? = null
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, false), theme) {
                Dialog(onDismissRequest = {}, properties = DialogProperties(decorFitsSystemWindows = false)) {
                    window = (LocalView.current.parent as DialogWindowProvider).window
                    if (withBars) ThemeDialogBars()
                    Box(Modifier.size(40.dp))
                }
            }
        }
        rule.waitForIdle()
        val w = assertNotNullAndGet(window)
        assertFalse("neon: light status icons in the dialog", w.lightStatus())
        assertFalse(w.lightNav())

        theme = Themes.of(ThemeId.SIGIL, ColorMode.SIX)
        rule.waitForIdle()
        assertTrue("a light theme: dark icons", w.lightStatus())
        assertTrue(w.lightNav())

        // CLASSIC: the dialog keeps whatever its window has (set here to a value no theme rule would give it).
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = false
        theme = Themes.Classic
        rule.waitForIdle()
        assertFalse("CLASSIC leaves the dialog's bars alone", w.lightStatus())
        withBars = false
        rule.waitForIdle()
        assertFalse(w.lightStatus())
    }

    private fun <T : Any> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
