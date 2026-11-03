package app.dshmobile.ui.chat

import app.dshmobile.ui.markdown.MarkdownParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockCacheTest {
    private val long1 = "霧".repeat(3_000)
    private val long2 = "雨".repeat(3_000)

    /** M2: a batch is parsed once and redrawn once; what an earlier, interrupted run already parsed is not parsed again. */
    @Test fun aBatchParsesOnlyWhatIsNotCachedAndRedrawsOnce() = runBlocking {
        val cache = BlockCache()
        val parsed = mutableListOf<String>()
        val parse = { t: String -> synchronized(parsed) { parsed += t.take(1) }; MarkdownParser.parse(t) }
        parseMissing(listOf("a:1" to long1), cache, parse) // an earlier run that got through the first message
        val before = cache.revision
        parseMissing(listOf("a:1" to long1, "a:2" to long2), cache, parse)
        assertEquals(listOf("霧", "雨"), parsed)
        assertEquals("one redraw for the batch", before + 1, cache.revision)
        assertTrue(cache.has("a:1", long1) && cache.has("a:2", long2))
        parseMissing(emptyList(), cache, parse)
        assertEquals("nothing to do, no redraw", before + 1, cache.revision)
    }

    /** Pieces of a long user bubble meet on the same colour of its gradient. */
    @Test fun neighbouringPiecesShareTheirEdgeOfTheGradient() {
        val e = BlockCache.Entry("x", MarkdownParser.parse((1..20).joinToString("\n\n") { "段落 $it " + "字".repeat(300) }))
        val shades = shades(e.pieces)
        assertTrue(shades.size > 12)
        assertEquals(0f, shades.first().from, 0f)
        assertEquals(1f, shades.last().to, 1e-4f)
        shades.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, 0f) }
        assertTrue("a tall bubble's gradient runs mostly downwards", shades.first().across < .2f)
    }
}
