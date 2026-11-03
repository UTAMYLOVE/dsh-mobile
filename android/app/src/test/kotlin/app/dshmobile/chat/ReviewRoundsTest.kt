package app.dshmobile.chat

import app.dshmobile.remote.PromptPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRoundsTest {
    @Test fun zeroRoundsLeavesThePromptAlone() {
        val parts = listOf(PromptPart.Text("寫一章"))
        assertSame(parts, ReviewRounds.decorate(0, parts))
    }

    @Test fun roundsAppendTheReviewerInstructionToTheFirstText() {
        val img = PromptPart.Image("image/png", "AAAA", "a.png")
        val out = ReviewRounds.decorate(2, listOf(img, PromptPart.Text("寫一章")))
        assertSame(img, out[0])
        val text = (out[1] as PromptPart.Text).text
        assertTrue(text.startsWith("寫一章" + ReviewRounds.MARKER))
        assertTrue(text.contains("審查員") && text.contains("重複 2 輪"))
    }

    @Test fun roundsAreCappedAndAnAttachmentOnlyPromptGetsTheInstructionAsText() {
        val out = ReviewRounds.decorate(9, listOf(PromptPart.File("r1", "a.pdf")))
        assertEquals(2, out.size)
        assertTrue((out[0] as PromptPart.Text).text.contains("重複 ${ReviewRounds.MAX} 輪"))
    }

    @Test fun theBubbleShowsOnlyTheOwnersWords() {
        val sent = (ReviewRounds.decorate(1, listOf(PromptPart.Text("幫我查天氣")))[0] as PromptPart.Text).text
        assertEquals("幫我查天氣", ReviewRounds.strip(sent))
        assertEquals("no marker", ReviewRounds.strip("no marker"))
    }
}
