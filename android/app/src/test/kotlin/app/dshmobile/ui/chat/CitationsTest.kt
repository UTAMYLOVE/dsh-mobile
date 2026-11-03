package app.dshmobile.ui.chat

import app.dshmobile.chat.WebSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationsTest {
    private val sources = listOf(
        WebSource("https://a.example.com/one", "One"),
        WebSource("https://b.example.com/two", "Two"),
        WebSource("https://c.example.com/three", "Three"),
    )

    @Test fun citedSourcesAreNumberedInSourceOrder() {
        // Mentioned three, then one: numbered by the sources' order, each once.
        val text = "見 [三](https://c.example.com/three)，另見 https://a.example.com/one 與 [再一次](https://c.example.com/three)。"
        assertEquals(listOf(1, 3), citedSources(text, sources))
        assertEquals("①③", circled(listOf(1, 3)))
        assertEquals("⑳(21)", circled(listOf(20, 21)))
    }

    @Test fun noLinksNoCites() {
        assertEquals(emptyList<Int>(), citedSources("明天晴，沒有連結。", sources))
        assertEquals(emptyList<Int>(), citedSources("https://a.example.com/one", emptyList()))
        // A different page on the same site is not that source.
        assertEquals(emptyList<Int>(), citedSources("https://a.example.com/other", sources))
    }

    @Test fun aTrailingSlashDifferenceStillCites() {
        assertEquals(listOf(2), citedSources("看 https://b.example.com/two/ 這頁", sources))
        assertEquals(listOf(1), citedSources("看 https://a.example.com/one", listOf(WebSource("https://a.example.com/one/", ""))))
    }
}
