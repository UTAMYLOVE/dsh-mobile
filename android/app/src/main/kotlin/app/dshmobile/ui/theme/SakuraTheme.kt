package app.dshmobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The sakura (light pink) palette shared by every screen. */
object Sakura {
    val Background = Color(0xFFFFF5F8)
    val Primary = Color(0xFFD6336C)
    val UserBubble = Color(0xFFF06595)
    val TagBackground = Color(0xFFFFD6E3)
    val TagText = Color(0xFFC2255C)
    val Border = Color(0xFFF3C6D3)
    val Text = Color(0xFF5A2A3A)
}

private val SakuraColors = lightColorScheme(
    primary = Sakura.Primary,
    onPrimary = Color.White,
    primaryContainer = Sakura.TagBackground,
    onPrimaryContainer = Sakura.TagText,
    secondary = Sakura.UserBubble,
    onSecondary = Color.White,
    secondaryContainer = Sakura.TagBackground,
    onSecondaryContainer = Sakura.TagText,
    background = Sakura.Background,
    onBackground = Sakura.Text,
    surface = Sakura.Background,
    onSurface = Sakura.Text,
    surfaceVariant = Sakura.TagBackground,
    onSurfaceVariant = Sakura.Text,
    outline = Sakura.Border,
    outlineVariant = Sakura.Border,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun SakuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SakuraColors, content = content)
}
