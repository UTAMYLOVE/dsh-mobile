package app.dshmobile.ui.shell

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.dshmobile.ui.fog.FogDebug
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B1-I6: the page-switch sweep glows with pre-built gradients, never a blur mask filter (re-rasterised every frame under the skew). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SweepLookTest {
    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogDebug.enabled = false
    }

    @Test fun theSweepGlowsWithGradientsAndNoMaskFilter() {
        val density = 2f
        val bitmap = ImageBitmap(400, 100)
        val look = SweepLook(density)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(400f, 100f)) { look.draw(this, .5f) }
        assertEquals("no blur mask filter built or used", 0, FogDebug.get("maskFilter"))
        val px = bitmap.asAndroidBitmap()
        // At p = .5 the line's left edge is at start + (width - start) * .5 = -20 + 420 * .5 = 190 px; its middle at 192 px.
        val y = 50 // the skew's pivot: the line is unshifted here
        fun alpha(x: Int) = android.graphics.Color.alpha(px.getPixel(x, y))
        assertTrue("the core is drawn (at .59 alpha, mid-sweep): ${alpha(192)}", alpha(192) > 150)
        assertTrue("a white glow near it: ${alpha(192 + 16)}", alpha(192 + 16) in 40..220)
        assertTrue("a pink haze further out: ${alpha(192 + 50)}", alpha(192 + 50) in 3..120)
        assertTrue("it glows less further out", alpha(192 + 50) < alpha(192 + 16))
        assertEquals("nothing far away", 0, alpha(192 + 160))
        assertTrue("symmetric", kotlin.math.abs(alpha(192 - 16) - alpha(192 + 16)) <= 12)
    }
}
