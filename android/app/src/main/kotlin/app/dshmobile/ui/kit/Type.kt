package app.dshmobile.ui.kit

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The redesign's type scale (system font; 12 sp is the smallest anywhere). Colours come from [Sakura] at use. */
object Type {
    val Title get() = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, color = Sakura.Text)
    val Heading get() = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, color = Sakura.Text)
    val Label get() = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = Sakura.Text)
    val Body get() = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = Sakura.Text)
    val Chat get() = TextStyle(fontSize = 15.5.sp, lineHeight = 24.sp, color = Sakura.Text)
    val Support get() = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Sakura.TextSecondary)
    val Caption get() = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Sakura.TextSecondary)
    val Timer get() = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Bold, color = Sakura.Text, fontFeatureSettings = TABULAR)

    internal val material = Typography()
}
private const val TABULAR = "tnum"
