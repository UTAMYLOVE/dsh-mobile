package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowUpsTest {
    @Test fun blockIsRemovedAndParsed() {
        val (body, items) = FollowUps.split("本週加權指數上漲。\n\n<!--dshm-followups\n外資買了哪些？\n- 下週要注意什麼？\n-->")
        assertEquals("本週加權指數上漲。", body)
        assertEquals(listOf("外資買了哪些？", "下週要注意什麼？"), items)
    }

    @Test fun noBlockLeavesTextAlone() = assertEquals("只是回答" to emptyList<String>(), FollowUps.split("只是回答"))

    @Test fun aStreamingHalfBlockIsHidden() {
        assertEquals("答案", FollowUps.split("答案\n\n<!--dshm-follow").first)
        assertEquals("答案", FollowUps.split("答案\n\n<!--dshm-followups\n外資").first)
        assertEquals(emptyList<String>(), FollowUps.split("答案\n\n<!--dshm-followups\n外資").second)
        assertEquals("a <!-- b", FollowUps.split("a <!-- b").first)
    }

    @Test fun moreThanThreeAreCutAndLongOnesDropped() {
        val (_, items) = FollowUps.split("x\n<!--dshm-followups\n一\n二\n三\n四\n" + "長".repeat(31) + "\n-->")
        assertEquals(listOf("一", "二", "三"), items)
    }
}
