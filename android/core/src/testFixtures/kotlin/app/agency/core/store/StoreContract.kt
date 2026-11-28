package app.agency.core.store

import app.agency.core.agent.Card
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What every [Store] must do. [MemStore] and the phone's encrypted store both run it. */
abstract class StoreContract {
    abstract fun newStore(): Store

    private val d1: LocalDate = LocalDate.of(2030, 1, 7)
    private val t0: Instant = Instant.parse("2030-01-07T01:00:00Z")
    private fun obj(n: Int) = JsonObject(mapOf("n" to JsonPrimitive(n)))

    @Test fun entriesComeBackByDayAndKindOldestFirst() {
        val s = newStore()
        val b = s.add(d1, t0.plusSeconds(60), Kind.WATER, obj(2))
        val a = s.add(d1, t0, Kind.WATER, obj(1))
        s.add(d1, t0, Kind.MEAL, obj(3))
        s.add(d1.plusDays(1), t0, Kind.WATER, obj(4))
        assertEquals(listOf(a.id, b.id), s.entries(d1, Kind.WATER).map { it.id })
        assertEquals(3, s.entries(d1).size)
        assertEquals(4, s.entries(d1, d1.plusDays(1)).size)
        assertEquals(a, s.entry(a.id))
    }

    @Test fun updateAndRemove() {
        val s = newStore()
        val e = s.add(d1, t0, Kind.WATER, obj(1))
        assertTrue(s.update(e.id, obj(9)))
        assertEquals(obj(9), s.entry(e.id)!!.data)
        assertTrue(s.remove(e.id))
        assertNull(s.entry(e.id))
        assertFalse(s.update(e.id, obj(1)))
        assertFalse(s.remove(e.id))
    }

    @Test fun docs() {
        val s = newStore()
        assertNull(s.doc("k"))
        s.putDoc("k", "{\"a\":1}")
        assertEquals("{\"a\":1}", s.doc("k"))
        s.putDoc("k", null)
        assertNull(s.doc("k"))
    }

    @Test fun messagesPageBackwardsAndKeepCards() {
        val s = newStore()
        val ids = (1..5).map { s.addMessage(t0.plusSeconds(it.toLong()), Role.OWNER, "m$it").id }
        assertEquals(listOf("m4", "m5"), s.messages(2).map { it.text })
        assertEquals(listOf("m2", "m3"), s.messages(2, beforeId = ids[3]).map { it.text })
        val m = s.addMessage(t0, Role.MANAGER, "card", listOf(Card.Score("check_in"), Card.Summary("t", listOf("a", "b"))))
        s.updateMessage(m.copy(cards = listOf(Card.Score("check_in", picked = 4), m.cards[1])))
        val back = s.messages(1).single()
        assertEquals(Card.Score("check_in", 4), back.cards[0])
        assertEquals(Card.Summary("t", listOf("a", "b")), back.cards[1])
    }
}
