package app.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The FOGCITY palette shared by every screen of the app, the same colours the `@dshm/ui` plugin gives dsh's own interface
 * (plugins/packages/ui/src/fogcity.ts). Text is only ever [Text] or [TextSecondary]; [Aqua] is a fill, never text (too faint).
 */
object Sakura {
    val Background = Color(0xFFFEDFE1)
    val MilkTea = Color(0xFFD7C4BB)
    val Primary = Color(0xFFDC9FB4)
    val White = Color(0xFFFFFFFF)
    val Aqua = Color(0xFFA5DEE4)
    val RoseBrown = Color(0xFF9E7A7A)
    val Text = Color(0xFF5E4445)
    val TextSecondary = Color(0xFF7D5F60)
    /** Card and field outlines. */
    val Border = Primary
}

private val SakuraColors = lightColorScheme(
    primary = Sakura.Primary,
    onPrimary = Sakura.Text, // white on #DC9FB4 is too faint to read
    primaryContainer = Sakura.White,
    onPrimaryContainer = Sakura.Text,
    inversePrimary = Sakura.Primary,
    secondary = Sakura.Aqua,
    onSecondary = Sakura.Text,
    secondaryContainer = Sakura.MilkTea,
    onSecondaryContainer = Sakura.Text,
    tertiary = Sakura.RoseBrown,
    onTertiary = Sakura.White,
    background = Sakura.Background,
    onBackground = Sakura.Text,
    surface = Sakura.Background,
    onSurface = Sakura.Text,
    surfaceVariant = Sakura.White,
    onSurfaceVariant = Sakura.TextSecondary,
    surfaceTint = Sakura.Primary,
    surfaceBright = Sakura.White,
    surfaceDim = Sakura.MilkTea,
    surfaceContainerLowest = Sakura.White,
    surfaceContainerLow = Sakura.White,
    surfaceContainer = Sakura.White,
    surfaceContainerHigh = Sakura.White,
    surfaceContainerHighest = Sakura.White,
    outline = Sakura.Border,
    outlineVariant = Sakura.MilkTea,
    // Errors keep Material's red: a warning must stand out from a pink screen.
    error = Color(0xFFB3261E),
    onError = Sakura.White,
)

/** No square corners: fields 12, chips 16, panels 22, pop-up cards 30 dp; Material 3 buttons are already capsules. */
private val SakuraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun SakuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SakuraColors, shapes = SakuraShapes, content = content)
}
