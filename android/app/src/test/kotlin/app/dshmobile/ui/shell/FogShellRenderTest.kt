package app.dshmobile.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixels, not semantics: a page painted solid black (and drawing far beyond its own bounds) must be clipped at the header,
 * softened by the fade under it, and faded out before the dock, at every effect level (with and without blur).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real drawing, so pixels can be read back
class FogShellRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(level: EffectLevel) = rule.setContent {
        FogTheme(effectsFor(level, reducedMotion = true)) {
            FogShell(
                status = MainUiState.Ready, connection = MuxState.OPEN, nav = ShellNav(),
                composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                onRetry = {}, onExportErrorLog = {},
            ) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val over = 400.dp.toPx()
                        drawRect(Color.Black, topLeft = Offset(0f, -over), size = Size(size.width, size.height + 2 * over))
                    },
                )
            }
        }
    }

    private fun render(): Bitmap {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun Bitmap.lum(xDp: Float, yPx: Float): Int {
        val p = getPixel((xDp * rule.density.density).toInt(), yPx.toInt())
        return (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
    }

    private fun check(level: EffectLevel) {
        show(level)
        val px = rule.density.density
        val header = rule.onNodeWithTag(Tags.SHELL_HEADER).fetchSemanticsNode().boundsInRoot
        val dockTop = rule.onNodeWithTag(Tags.DOCK).fetchSemanticsNode().boundsInRoot.top
        val b = render()
        val x = 206f // the middle: no header text, no dock button above the dock
        val where = level.name
        assertTrue("$where: the page shows mid-screen", b.lum(x, header.bottom + 200 * px) < 40)
        assertTrue("$where: clipped at the header (luminance ${b.lum(x, header.bottom - 4 * px)})", b.lum(x, header.bottom - 4 * px) > 150)
        assertTrue("$where: faded right under the header (luminance ${b.lum(x, header.bottom + 1 * px)})", b.lum(x, header.bottom + 1 * px) > 150)
        assertTrue("$where: still solid above the fade band", b.lum(x, dockTop - 30 * px) < 40)
        assertTrue("$where: dissolved before the dock (luminance ${b.lum(x, dockTop - 2 * px)})", b.lum(x, dockTop - 2 * px) > 150)
    }

    /** The disabled send button (no text yet) fades its colours; an alpha layer would cut its glow off at a square edge. */
    @Test fun aDisabledSendKeepsItsRoundGlow() {
        show(EffectLevel.SAVER)
        val send = rule.onNodeWithTag(Tags.SEND).fetchSemanticsNode().boundsInRoot
        val b = render()
        val px = rule.density.density
        // Across the button's square edge, just below its circle (where the glow is strongest), the colour must not jump.
        val y = (send.bottom - 2 * px).toInt()
        val inside = b.getPixel((send.left + 2 * px).toInt(), y)
        val outside = b.getPixel((send.left - 2 * px).toInt(), y)
        val jump = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).maxOf { c -> kotlin.math.abs(c(inside) - c(outside)) }
        assertTrue("colour jumps by $jump at the square edge of the send button", jump <= 6)
    }

    @Test fun pagesAreClippedUnderTheHeaderAndFadeBeforeTheDockWithBlur() = check(EffectLevel.FULL)

    @Test fun pagesFadeBeforeTheDockWithoutBlur() = check(EffectLevel.SAVER)
}
