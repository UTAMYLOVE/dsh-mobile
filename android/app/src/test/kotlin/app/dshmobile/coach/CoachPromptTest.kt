package app.dshmobile.coach

import app.dshmobile.coach.CoachRig.Companion.item
import app.dshmobile.coach.CoachRig.Companion.parsed
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptTest {
    private val rig = CoachRig()

    @After fun tearDown() = rig.close()

    @Test fun theCoachPromptIsTheOwnersTextWordForWord() {
        val spec = File("src/test/resources/coach/section5.txt").readText()
        assertEquals(spec, COACH_SYSTEM_VERBATIM)
        assertTrue(CoachPrompt.SYSTEM.startsWith(spec))
    }

    @Test fun thePromptStaysSmallHoweverLongTheChat() {
        runBlocking {
            rig.model.parse = { ModelReply.Ok(parsed("meal", item("給力盒子南加州烤雞腿", 0.1))) }
            repeat(40) { rig.engine.handle("吃了".padEnd(5000, '飯')) }
        }
        assertTrue(rig.store.messages.value.size >= 80)
        val (system, turns) = rig.engine.promptFor("今天好累")
        assertTrue(turns.size <= 10)
        assertEquals(Role.USER, turns.first().role)
        turns.zipWithNext().forEach { (a, b) -> assertTrue(a.role != b.role) }
        val total = system.length + turns.sumOf { t -> t.parts.sumOf { (it as Part.Text).text.length } }
        assertTrue("prompt was $total characters", total <= 12_000)
    }

    @Test fun theFactsCarryTheCodesNumbers() {
        runBlocking {
            rig.model.parse = { ModelReply.Ok(parsed("meal", item("給力盒子南加州烤雞腿"))) }
            rig.engine.handle("吃了給力盒子南加州烤雞腿")
        }
        val facts = (rig.engine.promptFor("嗯").second.last().parts.first() as Part.Text).text
        assertTrue(facts, facts.contains("剩 1071 大卡"))
        assertTrue(facts, facts.contains("放縱餐：星期五"))
        assertTrue(facts, facts.contains("不是放縱餐"))
    }

    @Test fun theGuardDropsInventedNumbersButKeepsDeadlines() {
        val facts = "剩 1071 大卡"
        assertEquals("30 分鐘內傳截圖。", NumberGuard.filter("30 分鐘內傳截圖。", facts, ""))
        assertEquals("現在去練。", NumberGuard.filter("再吃 300 大卡。現在去練。", facts, ""))
        assertEquals("你還有 1071 大卡。", NumberGuard.filter("你還有 1071 大卡。", facts, ""))
    }
}
