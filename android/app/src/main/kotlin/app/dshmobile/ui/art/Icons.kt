package app.dshmobile.ui.kit

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The redesign's line icons (22 dp, stroke 1.8, round ends), ported from the mockups' `parts.js`: no filled Material icons.
 * Drawn in the current content colour unless a [tint] is given.
 */
object Icons {
    private fun line(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Home: ImageVector by lazy { line("Home", "M4 11l8-6.5 8 6.5v8.5a1 1 0 0 1-1 1h-4.5v-5.5h-5V20.5H5a1 1 0 0 1-1-1z") }
    val Book: ImageVector by lazy { line("Book", "M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5zM5 19.5A1.5 1.5 0 0 0 6.5 21H19", "M9 7.5h6") }
    val Star: ImageVector by lazy { line("Star", "M12 3.5l2.4 5.3 5.6.6-4.2 3.8 1.2 5.6L12 16l-5 2.8 1.2-5.6L4 9.4l5.6-.6z") }
    val Sliders: ImageVector by lazy { line("Sliders", "M4 7h9M17 7h3M4 17h3M11 17h9", "M12.8,7.0a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z", "M6.8,17.0a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z") }
    val Send: ImageVector by lazy { line("Send", "M4.5 12L20 4.5 15 20l-3-6.2z", "M12 13.8l8-9.3") }
    val Down: ImageVector by lazy { line("Down", "M6 9.5l6 6 6-6") }
    val Up: ImageVector by lazy { line("Up", "M6 14.5l6-6 6 6") }
    val Back: ImageVector by lazy { line("Back", "M15 5l-7 7 7 7") }
    val Right: ImageVector by lazy { line("Right", "M9.5 5.5l6.5 6.5-6.5 6.5") }
    val Check: ImageVector by lazy { line("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Camera: ImageVector by lazy { line("Camera", "M4 8.5h3.2L9 6h6l1.8 2.5H20V19H4z", "M8.7,13.5a3.3,3.3 0 1,0 6.6,0a3.3,3.3 0 1,0 -6.6,0z") }
    val Mic: ImageVector by lazy { line("Mic", "M12.0,3.5h0.0a3.0,3.0 0 0 1 3.0,3.0v5.0a3.0,3.0 0 0 1 -3.0,3.0h-0.0a3.0,3.0 0 0 1 -3.0,-3.0v-5.0a3.0,3.0 0 0 1 3.0,-3.0z", "M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3") }
    val Play: ImageVector by lazy { line("Play", "M8 5.5v13l10.5-6.5z") }
    val Cup: ImageVector by lazy { line("Cup", "M7 4h10l-1.3 15.5a1.5 1.5 0 0 1-1.5 1.5H9.8a1.5 1.5 0 0 1-1.5-1.5z", "M7.5 10h9") }
    val Bowl: ImageVector by lazy { line("Bowl", "M3.5 11.5h17a8.5 8.5 0 0 1-17 0z", "M8 8c0-2 1.5-2 1.5-4M12 8c0-2 1.5-2 1.5-4") }
    val Sun: ImageVector by lazy { line("Sun", "M8.0,12.0a4.0,4.0 0 1,0 8.0,0a4.0,4.0 0 1,0 -8.0,0z", "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6L7 7M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4") }
    val Moon: ImageVector by lazy { line("Moon", "M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z") }
    val Heart: ImageVector by lazy { line("Heart", "M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z") }
    val Bolt: ImageVector by lazy { line("Bolt", "M13 3L5.5 13.5H12L11 21l7.5-10.5H12z") }
    val Lock: ImageVector by lazy { line("Lock", "M7.5,10.5h9.0a2.5,2.5 0 0 1 2.5,2.5v5.0a2.5,2.5 0 0 1 -2.5,2.5h-9.0a2.5,2.5 0 0 1 -2.5,-2.5v-5.0a2.5,2.5 0 0 1 2.5,-2.5z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5") }
    val User: ImageVector by lazy { line("User", "M8.0,8.5a4.0,4.0 0 1,0 8.0,0a4.0,4.0 0 1,0 -8.0,0z", "M4.5 20.5c1-4 4-6 7.5-6s6.5 2 7.5 6") }
    val Shield: ImageVector by lazy { line("Shield", "M12 3.5l7 3v5c0 4.5-3 7.6-7 9-4-1.4-7-4.5-7-9v-5z") }
    val Clock: ImageVector by lazy { line("Clock", "M3.5,12.0a8.5,8.5 0 1,0 17.0,0a8.5,8.5 0 1,0 -17.0,0z", "M12 7.5V12l3 2") }
    val Box: ImageVector by lazy { line("Box", "M4 8l8-4.5L20 8v8.5L12 21l-8-4.5z", "M4 8l8 4.5L20 8M12 12.5V21") }
    val Phone: ImageVector by lazy { line("Phone", "M9.5,3.0h5.0a2.5,2.5 0 0 1 2.5,2.5v13.0a2.5,2.5 0 0 1 -2.5,2.5h-5.0a2.5,2.5 0 0 1 -2.5,-2.5v-13.0a2.5,2.5 0 0 1 2.5,-2.5z", "M11 18h2") }
    val Pin: ImageVector by lazy { line("Pin", "M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z", "M9.7,10.0a2.3,2.3 0 1,0 4.6,0a2.3,2.3 0 1,0 -4.6,0z") }
    val Chat: ImageVector by lazy { line("Chat", "M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17H10l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5z") }
    val Ruler: ImageVector by lazy { line("Ruler", "M4 15.5L15.5 4 20 8.5 8.5 20z", "M8 11.5l2 2M10.5 9l1.5 1.5M13 6.5l2 2") }
    val Dumbbell: ImageVector by lazy { line("Dumbbell", "M3.5 9.5v5M6.5 7.5v9M17.5 7.5v9M20.5 9.5v5M6.5 12h11") }
    val Pause: ImageVector by lazy { line("Pause", "M9 6v12M15 6v12") }
    val Skip: ImageVector by lazy { line("Skip", "M6 6l8 6-8 6zM17.5 6v12") }
    val Pen: ImageVector by lazy { line("Pen", "M4.5 19.5l1-4.5L15.5 5l3.5 3.5-10 10z") }
    val Import: ImageVector by lazy { line("Import", "M12 4v10M8 10l4 4 4-4M4 16v3.5h16V16") }
    val Sparkle: ImageVector by lazy { line("Sparkle", "M12 3c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7z") }
}

/** One line icon; [description] null for a decorative one (the text beside it says what it is). */
@Composable
fun LineIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Sakura.Text, size: Dp = 22.dp, description: String? = null) {
    Icon(icon, contentDescription = description, tint = tint, modifier = modifier.size(size))
}
