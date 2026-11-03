package app.dshmobile.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ConflatedParseTest {
    private fun para(t: String) = listOf<MdBlock>(MdBlock.Paragraph(AnnotatedString(t)))
    private fun textOf(b: List<MdBlock>) = (b.single() as MdBlock.Paragraph).text.text

    @Test fun slowParsesStillPublishWhileUpdatesKeepArriving() = runBlocking {
        val texts = MutableStateFlow("t0")
        val published = CopyOnWriteArrayList<String>()
        val producerDone = AtomicBoolean(false)
        val publishedBeforeDone = CopyOnWriteArrayList<String>()
        val worker = launch {
            conflatedParse(texts, streaming = true, debounceMs = 5, parse = { Thread.sleep(60); para(it) }) {
                val t = textOf(it)
                published += t
                if (!producerDone.get()) publishedBeforeDone += t
            }
        }
        // 60 updates, 10 ms apart: far faster than the 60 ms parse.
        for (i in 1..60) { texts.value = "t$i"; delay(10) }
        producerDone.set(true)
        withTimeout(5_000) { while (published.lastOrNull() != "t60") delay(10) }
        worker.cancel()
        assertTrue("nothing published while streaming: $published", publishedBeforeDone.size >= 3)
        assertTrue("every update was parsed: ${published.size}", published.size < 61)
        assertEquals("t60", published.last())
    }

    @Test fun nonStreamingPublishesImmediatelyWithoutDelay() = runBlocking {
        val published = CopyOnWriteArrayList<String>()
        val texts = MutableStateFlow("x")
        val job = async(start = CoroutineStart.UNDISPATCHED) { conflatedParse(texts, streaming = false) { published += textOf(it) } }
        withTimeout(5_000) { while (published.isEmpty()) delay(10) }
        job.cancel()
        assertEquals(listOf("x"), published)
    }
}
