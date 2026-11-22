package app.dshmobile.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Follow-up 1: the app's own screens use the FOGCITY colours of the dsh theme (packages/ui/src/fogcity.ts). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SakuraThemeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun themed(): Pair<ColorScheme, Shapes> {
        var scheme: ColorScheme? = null
        var shapes: Shapes? = null
        rule.setContent { SakuraTheme { scheme = MaterialTheme.colorScheme; shapes = MaterialTheme.shapes } }
        rule.waitForIdle()
        return scheme!! to shapes!!
    }

    @Test fun thePaletteIsFogcity() {
        assertEquals(Color(0xFFFEDFE1), Sakura.Background)
        assertEquals(Color(0xFFDC9FB4), Sakura.Primary)
        assertEquals(Color(0xFFD7C4BB), Sakura.MilkTea)
        assertEquals(Color(0xFFFFFFFF), Sakura.White)
        assertEquals(Color(0xFFA5DEE4), Sakura.Aqua)
        assertEquals(Color(0xFF9E7A7A), Sakura.RoseBrown)
        assertEquals(Color(0xFF5E4445), Sakura.Text)
        assertEquals(Color(0xFF7D5F60), Sakura.TextSecondary)
    }

    @Test fun theSchemeUsesThePinkPrimaryTheBlushBackgroundAndRoseBrownText() {
        val (c, _) = themed()
        assertEquals(Sakura.Primary, c.primary)
        assertEquals(Sakura.Background, c.background)
        assertEquals(Sakura.Background, c.surface)
        assertEquals(Sakura.Text, c.onBackground)
        assertEquals(Sakura.Text, c.onSurface)
        assertEquals(Sakura.Text, c.onPrimary) // white on #DC9FB4 is too faint
        assertEquals(Sakura.TextSecondary, c.onSurfaceVariant)
    }

    @Test fun textIsOnlyEverTheTwoRoseBrownsAndAquaIsNeverText() {
        val (c, _) = themed()
        val onColours = listOf(
            c.onPrimary, c.onPrimaryContainer, c.onSecondary, c.onSecondaryContainer, c.onBackground, c.onSurface,
            c.onSurfaceVariant,
        )
        for (on in onColours) assertTrue("text colour $on", on == Sakura.Text || on == Sakura.TextSecondary)
        assertTrue(onColours.none { it == Sakura.Aqua })
    }

    @Test fun noDefaultLavenderContainersRemain() {
        val (c, _) = themed()
        val palette = setOf(Sakura.Background, Sakura.Primary, Sakura.MilkTea, Sakura.White, Sakura.Aqua, Sakura.RoseBrown)
        for (container in listOf(
            c.primaryContainer, c.secondary, c.secondaryContainer, c.tertiary, c.surfaceVariant, c.surfaceContainerLowest,
            c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest, c.surfaceBright,
            c.surfaceDim, c.outline, c.outlineVariant,
        )) assertTrue("container colour $container is not FOGCITY", container in palette)
    }

    @Test fun noSquareCorners() {
        val (_, s) = themed()
        assertEquals(RoundedCornerShape(12.dp), s.extraSmall)
        assertEquals(RoundedCornerShape(16.dp), s.small)
        assertEquals(RoundedCornerShape(22.dp), s.medium) // panels
        assertEquals(RoundedCornerShape(30.dp), s.large) // pop-up cards
        assertEquals(RoundedCornerShape(30.dp), s.extraLarge)
    }
}
