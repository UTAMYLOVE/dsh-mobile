package app.dshmobile.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodTableTest {
    private val table = FoodTable.seed(0)

    @Test fun theSeedIsTheOwnersFiveEntries() {
        assertEquals(5, table.size)
        val byName = table.associateBy { it.name }
        assertEquals(629, byName.getValue("給力盒子 南加州烤雞腿").kcal)
        assertEquals(51.0, byName.getValue("給力盒子 南加州烤雞腿").proteinG!!, 0.0)
        assertEquals(205, byName.getValue("全家 紐奧良風味烤雞鮮蔬三明治").kcal)
        byName.getValue("炸臭豆腐一盤").let { assertEquals(565, it.kcal); assertTrue(it.fried); assertTrue(it.estimated) }
        byName.getValue("蚵仔煎一盤").let { assertEquals(516, it.kcal); assertFalse(it.fried); assertTrue(it.estimated) }
        byName.getValue("蚵仔麵線一碗").let { assertEquals(415, it.kcal); assertTrue(it.estimated) }
    }

    @Test fun lookupUnderstandsHowTheOwnerSaysIt() {
        assertEquals("給力盒子 南加州烤雞腿", FoodTable.lookup(table, "南加州烤雞腿")?.name)
        assertEquals("給力盒子 南加州烤雞腿", FoodTable.lookup(table, "給力盒子　南加州烤雞腿")?.name) // full-width space
        assertEquals("全家 紐奧良風味烤雞鮮蔬三明治", FoodTable.lookup(table, "全家紐奧良風味烤雞鮮蔬三明治")?.name)
        assertEquals("炸臭豆腐一盤", FoodTable.lookup(table, "臭豆腐")?.name)
        assertEquals("蚵仔煎一盤", FoodTable.lookup(table, "蚵仔煎一盤")?.name)
        assertNull(FoodTable.lookup(table, "牛肉麵"))
        assertNull(FoodTable.lookup(table, "臭豆腐蚵仔煎")) // two entries tie: ask, never guess
    }

    @Test fun portionsRoundUp() {
        val e = FoodTable.lookup(table, "南加州烤雞腿")!!
        assertEquals(1258, FoodTable.tableItem(e, "南加州烤雞腿", 2.0, false).kcal)
    }

    @Test fun estimatesTakeTheHighEndPlusTheMargin() {
        assertEquals(715, FoodTable.estimateKcal(650, 1.0, 10))
        assertEquals(650, FoodTable.estimateKcal(650, 1.0, 0))
        assertEquals(1073, FoodTable.estimateKcal(650, 1.5, 10))
        val i = FoodTable.estimateItem("牛肉麵", 1.0, false, 650, 25.0, 10)
        assertEquals(KcalSource.ESTIMATE, i.source)
        assertEquals(25.0, i.proteinG!!, 0.0)
    }

    @Test fun friedWordsCountEvenWhenTheModelSaysNo() {
        assertTrue(FoodTable.isFried("鹽酥雞", false, null))
        assertTrue(FoodTable.isFried("臭豆腐", false, FoodTable.lookup(table, "臭豆腐")))
        assertFalse(FoodTable.isFried("蚵仔麵線", false, null))
    }
}
