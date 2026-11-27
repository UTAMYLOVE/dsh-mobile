package app.dshmobile.ui.kit

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

/** The company's design tokens (0.9.1): one palette for every screen, readable by day and night. */
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

    @Test fun thePaletteIsTheIdolPinkWithLilacAndGold() {
        assertEquals(Color(0xFFFF5FA2), Sakura.Primary)
        assertEquals(Color(0xFFB9A6FF), Sakura.Lilac)
        assertEquals(Color(0xFFE8C27A), Sakura.Gold)
        assertEquals(Color(0xFFFFF7FA), Sakura.Page)
        assertEquals(Color(0xFFFFFFFF), Sakura.White)
        assertEquals(Color(0xFFA5DEE4), Sakura.Aqua)
        // Text reads on the page and on the cards (WCAG AA), day and night.
        for (p in listOf(Palette.Light, Palette.Dark)) {
            assertTrue(MemberColour.contrast(p.text, p.page) >= 4.5)
            assertTrue(MemberColour.contrast(p.text, p.white) >= 4.5)
            assertTrue(MemberColour.contrast(p.textSecondary, p.white) >= 4.5)
            assertTrue(MemberColour.contrast(p.text, p.background) >= 4.5)
        }
    }

    @Test fun theSchemeUsesTheReadablePinkThePageAndTheTextColour() {
        val (c, _) = themed()
        assertEquals(Accent.DEFAULT.fill, c.primary)
        assertEquals(Sakura.Page, c.background)
        assertEquals(Sakura.Page, c.surface)
        assertEquals(Sakura.Text, c.onBackground)
        assertEquals(Sakura.Text, c.onSurface)
        assertEquals(Accent.DEFAULT.onFill, c.onPrimary)
        assertTrue(MemberColour.contrast(c.primary, c.onPrimary) >= 4.5)
        assertEquals(Sakura.TextSecondary, c.onSurfaceVariant)
    }

    @Test fun textIsOnlyEverTheTwoTextColoursAndAquaIsNeverText() {
        val (c, _) = themed()
        val onColours = listOf(c.onPrimaryContainer, c.onSecondary, c.onSecondaryContainer, c.onBackground, c.onSurface, c.onSurfaceVariant)
        for (on in onColours) assertTrue("text colour $on", on == Sakura.Text || on == Sakura.TextSecondary)
        assertTrue(onColours.none { it == Sakura.Aqua })
    }

    @Test fun noDefaultLavenderContainersRemain() {
        val (c, _) = themed()
        val palette = setOf(Sakura.Page, Sakura.Background, Sakura.Primary, Sakura.MilkTea, Sakura.White, Sakura.Aqua, Sakura.RoseBrown)
        for (container in listOf(
            c.primaryContainer, c.secondary, c.secondaryContainer, c.tertiary, c.surfaceVariant, c.surfaceContainerLowest,
            c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest, c.surfaceBright,
            c.surfaceDim, c.outline, c.outlineVariant,
        )) assertTrue("container colour $container is not the company's", container in palette)
    }

    @Test fun noSquareCorners() {
        val (_, s) = themed()
        assertEquals(RoundedCornerShape(12.dp), s.extraSmall)
        assertEquals(RoundedCornerShape(16.dp), s.small)
        assertEquals(RoundedCornerShape(20.dp), s.medium) // cards
        assertEquals(RoundedCornerShape(28.dp), s.large) // sheets
        assertEquals(RoundedCornerShape(28.dp), s.extraLarge)
    }
}
