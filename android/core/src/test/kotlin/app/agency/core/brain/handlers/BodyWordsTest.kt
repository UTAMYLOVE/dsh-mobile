package app.agency.core.brain.handlers

import app.agency.core.engine.IllKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code decides illness and 「想少做」 from his words (agency-links §3b); invented sentences. */
class BodyWordsTest {
    @Test fun illnessInjuryAndUnclear() {
        assertEquals(IllKind.SICK to "發燒", BodyWords.illness("我好像發燒了"))
        assertEquals(IllKind.SICK to "拉肚子", BodyWords.illness("今天一直拉肚子"))
        assertEquals(IllKind.INJURY to "扭到", BodyWords.illness("騎車腳扭到了"))
        assertEquals(IllKind.UNKNOWN to "不舒服", BodyWords.illness("今天身體不舒服"))
        assertNull(BodyWords.illness("我沒有發燒，只是累"))
        assertNull(BodyWords.illness("不是感冒啦"))
        assertNull(BodyWords.illness("好累喔腿很痠"))
    }

    @Test fun tiredOrSoreIsNoIllness() {
        listOf("好累", "沒睡好", "腿很痠", "不想動").forEach { assertNull(it, BodyWords.illness(it)) }
    }

    @Test fun askingToDoLess() {
        listOf("今天可以少做一輪嗎", "我不想練", "核心不做可以嗎", "肌力兩輪就好", "今天減量").forEach { assertTrue(it, BodyWords.asksLess(it)) }
        listOf("肌力做完了", "等等要去練", "晚餐少吃一點可以嗎").forEach { assertFalse(it, BodyWords.asksLess(it)) }
    }

    @Test fun lowMood() {
        assertTrue(BodyWords.moodLow("今天心情不好"))
        assertFalse(BodyWords.moodLow("今天心情很好"))
    }
}
