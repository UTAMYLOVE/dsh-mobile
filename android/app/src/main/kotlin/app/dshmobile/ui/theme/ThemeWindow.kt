package app.dshmobile.ui.theme

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StyleRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.insets.ProtectionLayout
import app.dshmobile.R

/**
 * R9: the window colour behind everything and the status/navigation bar icons follow [theme]. CLASSIC keeps today's call,
 * enableEdgeToEdge() with its auto styles, and the blush window (set again here for a return from 霓虹). minSdk 34 has
 * SplashScreen.setSplashScreenTheme (API 33): the system's starting window on the next cold start then matches too.
 *
 * The bar styles are SystemBarStyle.auto with the theme's darkness as their answer (plan §2.4.6 a): light()/dark() would switch
 * off isNavigationBarContrastEnforced, and 3-button navigation would lose the system's scrim (eng E29). Safe to call again on every
 * theme change: on API 35+ each enableEdgeToEdge call adds a (transparent) ProtectionLayout to the decor, so the one an earlier call
 * added is taken out first instead of piling up.
 */
fun ComponentActivity.applyThemeWindow(theme: Theme) {
    val bars = theme.bars
    (window.decorView as? ViewGroup)?.let { decor ->
        for (i in decor.childCount - 1 downTo 0) if (decor.getChildAt(i) is ProtectionLayout) decor.removeViewAt(i)
    }
    if (bars == null) enableEdgeToEdge()
    else enableEdgeToEdge(statusBarStyle = statusStyle(bars.darkStatus), navigationBarStyle = navigationStyle(bars.darkNav))
    window.setBackgroundDrawable(ColorDrawable((bars?.window ?: Sakura.Background).toArgb()))
    // A binder call the system keeps per app; refused or missing (Robolectric) is not an error, the next start is just blush.
    runCatching { splashScreen.setSplashScreenTheme(startWindowStyle(theme.id, theme.mode)) }
}

/** dark = the status bar area is dark → light icons. Transparent scrims (enableEdgeToEdge()'s own status style). */
private fun statusStyle(dark: Boolean): SystemBarStyle =
    SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { _ -> dark }

/** dark = the navigation area is dark → light icons. enableEdgeToEdge()'s default navigation scrims, kept for 3-button navigation. */
private fun navigationStyle(dark: Boolean): SystemBarStyle = SystemBarStyle.auto(NavLightScrim, NavDarkScrim) { _ -> dark }

/** The scrims enableEdgeToEdge() gives the navigation bar by default (androidx.activity EdgeToEdge.kt). */
private val NavLightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavDarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** The starting-window style whose windowBackground is the theme's window colour (ThemeWindowTest checks each). */
@StyleRes
fun startWindowStyle(id: ThemeId, mode: ColorMode): Int = when (id) {
    ThemeId.WEB_NEON -> if (mode == ColorMode.ORIGINAL) R.style.Theme_DshMobile_Start_Night else R.style.Theme_DshMobile_Start_Rose
    ThemeId.WEB_ACCESS -> if (mode == ColorMode.ORIGINAL) R.style.Theme_DshMobile_Start_Archive else R.style.Theme_DshMobile
    ThemeId.WEB_WAVE -> if (mode == ColorMode.ORIGINAL) R.style.Theme_DshMobile_Start_Mist else R.style.Theme_DshMobile
    ThemeId.WEB_SAKURA -> if (mode == ColorMode.ORIGINAL) R.style.Theme_DshMobile_Start_Lilac else R.style.Theme_DshMobile
    else -> R.style.Theme_DshMobile
}

/**
 * Inside a Dialog with decorFitsSystemWindows = false (ShareSheet's host, LicensesDialog), whose own window draws behind the bars:
 * when the worn theme is not CLASSIC, its bar icons follow the theme as the activity's do ([Theme.bars]). CLASSIC does nothing
 * (the dialog keeps today's look, owner O8 / eng E30).
 */
@Composable
fun ThemeDialogBars() {
    val theme = LocalTheme.current
    if (theme.classic) return
    val bars = theme.bars ?: return
    val view = LocalView.current
    DisposableEffect(bars, view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: (view as? DialogWindowProvider)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).run {
                isAppearanceLightStatusBars = !bars.darkStatus
                isAppearanceLightNavigationBars = !bars.darkNav
            }
        }
        onDispose { }
    }
}
