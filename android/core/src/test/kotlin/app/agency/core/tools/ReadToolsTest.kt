package app.agency.core.tools

import app.agency.core.brain.SnapshotReader
import app.agency.core.llm.ToolCall
import app.agency.core.metering.MutableClock
import app.agency.core.model.CompanyRules
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadToolsTest {
    private val store = InMemoryAgencyStore()
    // 2026-11-23 is a Monday; 12:00 in Taipei.
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z"))
    private val snapshot = SnapshotReader(store, AgencyClock.of(clock))
    private val registry = ToolRegistry(readTools(store, snapshot))

    private fun meal(id: String, day: String, kcal: Int, zero: Boolean = false, cheat: Boolean = false) = Meal(
        id, day, Instant.parse("${day}T04:00:00Z").toEpochMilli(), text = "吃了",
        items = listOf(MealItem("雞腿便當", 1.0, kcal, 30.0, null, null, false, KcalSource.TABLE, zeroCal = zero)), cheat = cheat,
    )

    private fun call(name: String, args: String): JsonObject = runBlocking {
        val r = registry.execute(ToolCall("c", name, args))
        Json.parseToJsonElement(r.content).jsonObject
    }

    @Test fun theSpecsAreStrictAndInTheirFixedOrder() {
        assertEquals(listOf("get_today", "query_records", "get_plan"), registry.specs.map { it.name })
        registry.specs.forEach { s ->
            assertEquals("false", s.parameters["additionalProperties"].toString())
            val props = s.parameters["properties"]!!.jsonObject.keys
            val required = (s.parameters["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
            assertEquals(props, required)
        }
    }

    @Test fun getTodayIsComputedByCode() {
        runBlocking {
            store.meals.insert(meal("m1", "2026-11-23", 714, zero = true))
            store.meals.insert(meal("m0", "2026-11-22", 900, cheat = true)) // last week (Sunday)
            store.water.insert(WaterLog("w1", "2026-11-23", 1, 2))
        }
        val o = call("get_today", "{}")
        assertEquals("2026-11-23", o["day"]!!.jsonPrimitive.content)
        assertEquals("class", o["day_type"]!!.jsonPrimitive.content)
        assertEquals(714, o["kcal"]!!.jsonObject["eaten"]!!.jsonPrimitive.int)
        assertEquals(1700 - 714, o["kcal"]!!.jsonObject["left"]!!.jsonPrimitive.int)
        assertEquals(800, o["kcal"]!!.jsonObject["meal_cap"]!!.jsonPrimitive.int)
        assertEquals(110, o["protein"]!!.jsonObject["left"]!!.jsonPrimitive.int)
        assertEquals(2, o["water"]!!.jsonObject["done_half"]!!.jsonPrimitive.int)
        assertEquals(0, o["cheat"]!!.jsonObject["used_this_week"]!!.jsonPrimitive.int)
        assertEquals(1, o["zero_cal_week"]!!.jsonPrimitive.int)
    }

    @Test fun dayTypesFollowThePlan() {
        assertEquals("no_class", GetTodayTool.dayType(LocalDate.parse("2026-11-25"), rest = false))
        assertEquals("class", GetTodayTool.dayType(LocalDate.parse("2026-11-27"), rest = false))
        assertEquals("rest", GetTodayTool.dayType(LocalDate.parse("2026-11-27"), rest = true))
    }

    @Test fun queryRecordsChecksItsRangeAndCapsTheRows() {
        runBlocking {
            (0 until 45).forEach { i -> store.weights.insert(WeightLog("w$i", "2026-11-20", 1000L + i, 80.0 - i / 10.0)) }
        }
        val o = call("query_records", """{"kind":"weight","from":"2026-11-20","to":"2026-11-23"}""")
        assertEquals(40, (o["rows"] as JsonArray).size)
        assertEquals(5, o["more"]!!.jsonPrimitive.int)
        assertTrue(call("query_records", """{"kind":"weight","from":"2026-11-01","to":"2026-11-23"}""")["error"].toString().contains("BAD_ARGS"))
        assertTrue(call("query_records", """{"kind":"weight","from":"11/20","to":"2026-11-23"}""")["field"].toString().contains("from"))
        assertTrue(call("query_records", """{"kind":"photo","from":"2026-11-20","to":"2026-11-23"}""")["field"].toString().contains("kind"))
    }

    @Test fun getPlanReadsTheRulesAndNeverWrites() {
        runBlocking { store.rules.put(CompanyRules(quotaKcal = 1800), 1) }
        val o = call("get_plan", """{"section":"rules"}""")
        assertEquals(1800, o["quota_kcal"]!!.jsonPrimitive.int)
        assertFalse(call("get_plan", """{"section":"schedule"}""")["set"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, runBlocking { store.rules.history() }.size)
    }

    @Test fun aFailingToolIsFailedWithoutAStack() {
        val broken = object : AgencyTool {
            override val spec = registry.specs[0]
            override suspend fun run(args: JsonObject) = error("secret detail")
        }
        val r = runBlocking { ToolRegistry(listOf(broken)).execute(ToolCall("x", "get_today", "{}")) }
        assertTrue(r.isError)
        assertTrue(r.content.contains("FAILED"))
        assertFalse(r.content.contains("secret"))
    }
}
