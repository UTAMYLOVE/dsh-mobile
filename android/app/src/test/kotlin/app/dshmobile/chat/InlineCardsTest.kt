package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCardsTest {
    @Test fun aBlockBecomesACardAndLeavesTheText() {
        val (body, cards) = InlineCards.split("已記：餐飲 午餐 -120\n\n<!--dshm-card ledger\n項目: 午餐\n金額: -120\n類別：餐飲\n-->")
        assertEquals("已記：餐飲 午餐 -120", body)
        assertEquals(listOf(InlineCard(InlineCard.Type.LEDGER, listOf("項目" to "午餐", "金額" to "-120", "類別" to "餐飲"))), cards)
        assertEquals("-120", cards[0]["金額"])
    }

    @Test fun severalBlocksKeepTheirOrderAndTheTextAround() {
        val text = "兩檔都漲。\n<!--dshm-card quote\n名稱: 台積電\n價格: 1,085\n-->\n<!--dshm-card quote\n名稱: 聯發科\n價格: 1,420\n-->\n要看走勢再跟我說。"
        val (body, cards) = InlineCards.split(text)
        assertEquals("兩檔都漲。\n\n要看走勢再跟我說。", body)
        assertEquals(listOf("台積電", "聯發科"), cards.map { it["名稱"] })
    }

    @Test fun weatherNeedsNoFieldOthersDo() {
        assertEquals(listOf(InlineCard.Type.WEATHER), InlineCards.split("會下雨。<!--dshm-card weather-->").second.map { it.type })
        assertTrue(InlineCards.split("好。<!--dshm-card ledger\n-->").second.isEmpty())
        assertTrue(InlineCards.split("好。<!--dshm-card unknown\n甲: 乙\n-->").second.isEmpty())
    }

    @Test fun aBlockStillStreamingShowsNothing() {
        assertEquals("已記", InlineCards.split("已記\n<!--dshm-card ledger\n項目: 午").first)
        assertEquals("已記", InlineCards.split("已記\n<!--dshm-c").first)
        assertEquals("a <!-- note --> b", InlineCards.split("a <!-- note --> b").first)
    }

    @Test fun longValuesAndTooManyCardsAreCut() {
        val many = (1..9).joinToString("") { "<!--dshm-card reminder\n內容: 第 $it 件\n-->" }
        assertEquals(InlineCards.MAX, InlineCards.split(many).second.size)
        assertEquals(InlineCards.MAX_CHARS, InlineCards.split("<!--dshm-card reminder\n內容: ${"長".repeat(200)}\n-->").second[0]["內容"]!!.length)
    }

    @Test fun theSkillsTeachTheBlocksTheAppReads() {
        for (type in InlineCard.Type.entries) assertTrue(type.id, app.dshmobile.config.AppSkills.all.values.any { it.contains("<!--dshm-card ${type.id}") })
        // every example block in the skills parses to a card
        val blocks = app.dshmobile.config.AppSkills.all.values.flatMap { Regex("<!--dshm-card[\\s\\S]*?-->").findAll(it).map { m -> m.value }.toList() }
        assertEquals(blocks.size, blocks.sumOf { InlineCards.split(it).second.size })
    }
}
