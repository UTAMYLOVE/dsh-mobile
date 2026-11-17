package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.LocalPictureBehind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The glass recipe: GlassRecipe.Classic is today's glass exactly; another recipe paints what it says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassRecipeTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null
    private val shape = RoundedCornerShape(22.dp)

    @Before fun plain() {
        // One flat ground behind both surfaces (no glows), so the two halves of the window can be compared pixel for pixel.
        FogSwitches.plainBackground = true
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogSwitches.plainBackground = false
        FogDebug.enabled = false
    }

    @Test fun classicRecipeIsTodaysGlassWithoutBlur() = sameAsToday(EffectLevel.BALANCED)

    @Test fun classicRecipeIsTodaysGlassWithBlur() = sameAsToday(EffectLevel.FULL)

    private fun sameAsToday(level: EffectLevel) {
        scene(level) {
            Box(Modifier.absoluteOffset(30.dp, 40.dp).size(60.dp).glass(shape))
            Box(Modifier.absoluteOffset(190.dp, 40.dp).size(60.dp).glass(shape, true, GlassRecipe.Classic))
        }
        val s = shot()
        // The left half (with its shadows) and the right half, shifted by 160 px, are the same pixels.
        var differ = 0
        for (y in 0 until 200) for (x in 0 until 160) if (s.getPixel(x, y) != s.getPixel(x + 160, y)) differ++
        assertEquals("$level: pixels that differ between glass(shape) and glass(shape, settled, Classic)", 0, differ)
        // And the surface is really drawn (not two empty halves): the fill is lighter than the ground.
        assertTrue(Color(s.getPixel(60, 70)).green > Color(s.getPixel(5, 190)).green)
        // Without blur nothing flips the cache's lifted state: one cache each (with blur the first frame may be drawn unblurred).
        if (level == EffectLevel.BALANCED) assertEquals("one cache each", 2, FogDebug.get("glass.cache"))
    }

    @Test fun aRecipePaintsItsOwnFillRimAndShadows() {
        val flat = GlassRecipe(fill = listOf(0f to Color.White.copy(alpha = .5f)), lift = .16f, rim = null, lit = false, shadows = emptyList())
        scene(EffectLevel.BALANCED) {
            Box(Modifier.absoluteOffset(30.dp, 40.dp).size(60.dp).glass(shape, true, flat))
        }
        val s = shot()
        val ground = Color(s.getPixel(5, 190))
        // No shadow just outside, no rim on the edge: the ground.
        assertColour("outside", ground, Color(s.getPixel(60, 104)))
        // Without blur the fill is lifted by the recipe's own lift: W .66 over the ground.
        assertColour("fill", Color.White.copy(alpha = .66f).over(ground), Color(s.getPixel(60, 70)))
    }

    @Test fun aTextSurfacesGlassIsFlooredOverADarkPictureAndTodaysIsNot() {
        val thin = GlassRecipe(fill = listOf(0f to Color.White.copy(alpha = .30f)), lift = .16f, rim = null, lit = false, shadows = emptyList())
        var behind by mutableStateOf(true)
        scene(EffectLevel.BALANCED, behind = { behind }) {
            Box(Modifier.absoluteOffset(30.dp, 40.dp).size(60.dp).glassFrame(shape, true, thin, pictureFloor = true))
            Box(Modifier.absoluteOffset(190.dp, 40.dp).size(60.dp).glass(shape, true, thin))
        }
        var s = shot()
        val ground = Color(s.getPixel(5, 190))
        assertColour("floored to .74", Color.White.copy(alpha = .74f).over(ground), Color(s.getPixel(60, 70)))
        assertColour("glass() never floors", Color.White.copy(alpha = .46f).over(ground), Color(s.getPixel(220, 70)))
        behind = false
        s = shot()
        assertColour("no dark picture: .30 + .16", Color.White.copy(alpha = .46f).over(ground), Color(s.getPixel(60, 70)))
    }

    private fun scene(level: EffectLevel, behind: () -> Boolean = { false }, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                FogTheme(effectsFor(level, false)) {
                    val backdrop = rememberBackdrop()
                    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false, LocalPictureBehind provides behind()) {
                        Box(Modifier.size(320.dp, 200.dp)) {
                            FogBackground(backdrop)
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun shot(): Bitmap {
        rule.waitForIdle()
        val v = view!!
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> rule.runOnUiThread { v.draw(Canvas(b)) } }
    }

    /** [this] laid over the opaque [under] (8-bit, as the canvas composites). */
    private fun Color.over(under: Color): Color = Color(
        red * alpha + under.red * (1f - alpha), green * alpha + under.green * (1f - alpha), blue * alpha + under.blue * (1f - alpha),
    )

    private fun assertColour(message: String, expected: Color, actual: Color) {
        val gap = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green), abs(expected.blue - actual.blue))
        assertTrue("$message: expected $expected, was $actual", gap <= 3f / 255f)
    }
}
