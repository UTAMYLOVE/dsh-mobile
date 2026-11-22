package app.agency.core.tools

import app.agency.core.brain.SnapshotReader
import app.agency.core.llm.ToolCall
import app.agency.core.memory.MemoryApplier
import app.agency.core.metering.MutableClock
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Message
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.Who
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tools T12 adds (arch 1.4: 3, 4, 5, 7, 8, 9, 10): strict schemas, numbers by code, proposals only, errors in three classes. */
class ChatToolsTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z")) // Monday 12:00 in Taipei
    private val ac = AgencyClock.of(clock)
    private val snapshot = SnapshotReader(store, ac)
    private var n = 0
    private val owner = Message("o-1", clock.millis(), Who.OWNER, text = "我喜歡雞腿")
    private val ctx = RoundContext(owner, emptyMap(), LocalDate.parse("2026-11-23"))
    private val registry = ToolRegistry(chatTools(store, snapshot, ac, { "p-${++n}" }, MemoryApplier(store, ac), ctx) { true })

    private fun call(name: String, args: String): Pair<Boolean, JsonObject> = runBlocking {
        val r = registry.execute(ToolCall("c", name, args))
        r.isError to Json.parseToJsonElement(r.content).jsonObject
    }

    private fun eat(kcal: Int) = runBlocking {
        store.meals.insert(Meal("m-$kcal", "2026-11-23", clock.millis(), text = "吃了", items = listOf(MealItem("便當", 1.0, kcal, 20.0, null, null, false, KcalSource.TABLE))))
    }

    @Test fun allTenAreStrictAndInTheirFixedOrder() {
        assertEquals(
            listOf("get_today", "query_records", "lookup_food", "compute", "search_places", "get_plan", "propose_change", "propose_meal_order", "save_place", "remember"),
            registry.specs.map { it.name },
        )
        fun strict(o: JsonObject) {
            if (o["type"]?.jsonPrimitive?.content != "object") return
            assertEquals("false", o["additionalProperties"].toString())
            assertEquals(o["properties"]!!.jsonObject.keys, (o["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
            o["properties"]!!.jsonObject.values.forEach { p ->
                val po = p.jsonObject
                po["items"]?.let { strict(it.jsonObject) }
                if (po["type"]?.jsonPrimitive?.content == "object") strict(po)
            }
        }
        registry.specs.forEach { strict(it.parameters) }
    }

    @Test fun getTodaySaysWhetherHeIsRiding() {
        assertTrue(call("get_today", "{}").second["riding"]!!.jsonPrimitive.boolean)
    }

    @Test fun lookupFoodFindsTheTableAndNeverInventsOne() {
        val (_, hit) = call("lookup_food", """{"query":"南加州烤雞腿"}""")
        assertEquals("給力盒子 南加州烤雞腿", hit["match"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val (_, miss) = call("lookup_food", """{"query":"牛肉麵"}""")
        assertEquals(JsonNull, miss["match"])
        assertTrue(call("lookup_food", """{"query":""}""").first)
    }

    @Test fun computeDoesTheArithmeticOnTodaysNumbers() {
        eat(900)
        val (_, after) = call("compute", """{"op":"after_eating","kcal":700,"protein_g":40}""")
        assertEquals(100, after["kcal_left_after"]!!.jsonPrimitive.int)
        assertEquals(80, after["protein_left_after"]!!.jsonPrimitive.int)
        assertTrue(after["within_meal_cap"]!!.jsonPrimitive.boolean)
        assertTrue(after["above_floor"]!!.jsonPrimitive.boolean)
        val (_, over) = call("compute", """{"op":"over_quota","kcal":null,"protein_g":null}""")
        assertEquals(0, over["over_kcal"]!!.jsonPrimitive.int)
        val (bad, err) = call("compute", """{"op":"after_eating","kcal":null,"protein_g":null}""")
        assertTrue(bad)
        assertEquals("BAD_ARGS", err["error"]!!.jsonPrimitive.content)
        assertEquals("kcal", err["field"]!!.jsonPrimitive.content)
    }

    @Test fun searchIsStubbedWithNoResults() {
        val (err, out) = call("search_places", """{"dish":"雞胸便當"}""")
        assertFalse(err)
        assertTrue(out["results"]!!.jsonArray.isEmpty())
    }

    @Test fun aMealOrderIsAProposalWithTableKcalWithinTheCap() {
        eat(300)
        val (err, out) = call("propose_meal_order", """{"slot":"dinner","items":[{"name":"南加州烤雞腿","portion":1}],"place_result_id":null}""")
        assertFalse(out.toString(), err)
        assertEquals(629, out["kcal"]!!.jsonPrimitive.int)
        assertEquals(1700 - 300 - 629, out["kcal_left_after"]!!.jsonPrimitive.int)
        val p = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        assertEquals(ProposalKind.MEAL_ORDER, p.kind)
        assertEquals(listOf(p.id), ctx.proposals)
        // Over the single-meal cap, an unknown food, or a place that is not this round's: refused, nothing written.
        assertTrue(call("propose_meal_order", """{"slot":"dinner","items":[{"name":"南加州烤雞腿","portion":2}],"place_result_id":null}""").second.toString().contains("超過單餐上限"))
        assertTrue(call("propose_meal_order", """{"slot":"dinner","items":[{"name":"牛肉麵","portion":1}],"place_result_id":null}""").first)
        assertTrue(call("propose_meal_order", """{"slot":"dinner","items":[{"name":"南加州烤雞腿","portion":1}],"place_result_id":"r-9"}""").first)
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.size)
    }

    @Test fun savePlaceOnlyForAResultOfThisRound() {
        runBlocking { store.places.putResult(SearchResult("r-1", SearchKind.PLACE, "q", "https://x.example", "某某小館", null, null, null, 1.5, 0)) }
        assertTrue(call("save_place", """{"result_id":"r-1","note":"雞胸便當"}""").first)
        ctx.searchResults += "r-1"
        assertFalse(call("save_place", """{"result_id":"r-1","note":"雞胸便當"}""").first)
        assertEquals(ProposalKind.SAVE_PLACE, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single().kind)
        assertTrue(runBlocking { store.places.favorites() }.isEmpty()) // nothing saved before his yes
    }

    @Test fun proposeChangeChecksTheSectionAndTheDayOrder() {
        val (e1, o1) = call("propose_change", """{"section":"meals","ops":[{"path":"class_wake","value":"08:00"}],"reason":"早一點"}""")
        assertTrue(e1 && o1["reason"]!!.jsonPrimitive.content.contains("不在這一段"))
        val (e2, o2) = call("propose_change", """{"section":"meals","ops":[{"path":"dinner","value":"20:30"}],"reason":"晚一點吃"}""")
        assertTrue(e2 && o2["reason"]!!.jsonPrimitive.content.contains("21:00"))
        assertTrue(call("propose_change", """{"section":"schedule","ops":[{"path":"class_sleep_latest","value":"03:00"}],"reason":"晚一點睡"}""").first)
        assertTrue(call("propose_change", """{"section":"schedule","ops":[{"path":"class_wake","value":"8點"}],"reason":"早一點"}""").first)
        assertTrue(call("propose_change", """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"Wake up"}""").first)
        val (ok, out) = call("propose_change", """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"早一點起來"}""")
        assertFalse(ok)
        assertEquals("pending_owner", out["status"]!!.jsonPrimitive.content)
    }

    @Test fun rememberAppliesAtMostTwoInATurn() {
        val ev = """{"op":"add","block":"likes","item_id":null,"text":"他喜歡雞腿","quote":"喜歡雞腿"}"""
        val (_, out) = call("remember", """{"events":[$ev]}""")
        assertEquals(1, out["applied"]!!.jsonArray.size)
        assertEquals(1, ctx.memoryEvents.size)
        val (_, again) = call("remember", """{"events":[${ev.replace("他喜歡雞腿", "他很喜歡雞腿")},$ev]}""")
        assertEquals(1, again["applied"]!!.jsonArray.size)
        assertEquals(1, again["rejected"]!!.jsonArray.size)
        assertEquals(0, ctx.memoryLeft)
    }
}
