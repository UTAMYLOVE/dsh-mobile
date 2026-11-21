package app.dshmobile.coach

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachStoreTest {
    private val dir: File = Files.createTempDirectory("coach-store").toFile()

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun everythingComesBackAfterARestart() {
        val a = CoachStore(dir) { 1000L }
        val item = MealItem("牛肉麵", 1.5, 1073, 37.5, null, null, fried = false, source = KcalSource.ESTIMATE, estimateHigh = 650)
        a.update {
            it.copy(
                meals = listOf(Meal("m1", 5, "2026-11-21", "吃了牛肉麵", listOf(item), cheat = false, offPlan = false)),
                weights = listOf(WeightEntry("w1", 6, "2026-11-21", 79.2, 88.0)),
                pending = listOf(PendingFood("m1", 0, "牛肉麵", 1073, 7)),
            )
        }
        a.append(CoachMessage("x1", 8, Speaker.OWNER, "吃了牛肉麵", null))
        a.append(CoachMessage("x2", 9, Speaker.COACH, "記下", "meal"))
        val b = CoachStore(dir) { 2000L }
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.messages.value, b.messages.value)
    }

    @Test fun aCutLastLineIsSkippedAndAStaleTempIsIgnored() {
        val a = CoachStore(dir) { 1L }
        a.append(CoachMessage("x1", 1, Speaker.OWNER, "嗨", null))
        File(dir, "messages.jsonl").appendText("""{"id":"x2","at":2,"who":"COA""")
        File(dir, "state.json.tmp").writeText("garbage")
        val b = CoachStore(dir) { 1L }
        assertEquals(listOf("嗨"), b.messages.value.map { it.text })
        assertEquals(5, b.state.value.food.size)
        b.append(CoachMessage("x3", 3, Speaker.COACH, "好", null))
        assertEquals(listOf("嗨", "好"), CoachStore(dir) { 1L }.messages.value.map { it.text })
    }

    @Test fun aDeletedSeedStaysDeleted() {
        val a = CoachStore(dir) { 1L }
        a.update { it.copy(food = it.food.drop(1)) }
        assertEquals(4, CoachStore(dir) { 1L }.state.value.food.size)
    }
}
