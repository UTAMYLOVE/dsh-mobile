package app.dshmobile.ui.fog

import android.content.Context
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A run's thickening strokes and narrower outlines are built once per glyph and reused every frame, each at its own glyph's
 * width: in one name a pen character (thickened), an emoji (not) and a fallback character (a little) each get the outline that
 * keeps their white rim as wide as the pen's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlyphRunStrokesTest {
    private fun run(vararg thick: Float): GlyphRun {
        val context: Context = ApplicationProvider.getApplicationContext()
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        val layout = measurer.measure("小")
        val n = thick.size
        return GlyphRun(
            List(n) { "小" }, Array(n) { layout }, FloatArray(n) { it * 20f }, FloatArray(n) { 20f }, 20f * n, 24f, 18f, 20f,
            BooleanArray(n), thick = thick.copyOf(),
        )
    }

    @Test fun eachGlyphGetsItsOwnOutlineWidth() {
        val r = run(4f, 0f, 1f)
        val outline = Stroke(10f)
        assertSame(outline, r.outlineFor(0, outline))
        assertEquals(6f, r.outlineFor(1, outline).width)
        assertEquals(7f, r.outlineFor(2, outline).width)
        // Asked again (the next frame): the same objects, nothing new.
        assertSame(r.outlineFor(1, outline), r.outlineFor(1, outline))
        assertSame(r.outlineFor(2, outline), r.outlineFor(2, outline))
        val halo = Stroke(14f)
        assertEquals(10f, r.outlineFor(1, halo).width)
    }

    @Test fun theThickeningStrokeIsBuiltOncePerGlyph() {
        val r = run(4f, 0f, 1f)
        assertEquals(4f, r.body(0)!!.width)
        assertSame(r.body(0), r.body(0))
        assertNull(r.body(1))
        assertEquals(1f, r.body(2)!!.width)
        // A look that lowers a glyph's thickening after measuring (Stardust's matchPen) gets a stroke at the new width.
        r.thick[0] = 2f
        assertEquals(2f, r.body(0)!!.width)
    }
}
