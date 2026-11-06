package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTest {
    @Test fun paragraphsAreReadWithoutMarksCodeOrLinks() {
        val md = "## 本週重點\n\n1. **加權指數**上漲 [來源](https://x.tw/a)\n\n```\ncode\n```\n\n看 https://x.tw/b 這裡"
        assertEquals(listOf("本週重點", "1. 加權指數上漲 來源", "看 這裡"), Speaker.paragraphs(md))
    }
}
