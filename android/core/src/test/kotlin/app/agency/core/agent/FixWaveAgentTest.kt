package app.agency.core.agent

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.llm.Fail
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.calls
import app.agency.core.llm.ScriptedLlm.Companion.fails
import app.agency.core.llm.ToolSpec
import app.agency.core.rules.Fixtures
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Role
import app.agency.core.store.Water
import app.agency.core.store.add
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B9: a reply that fails after a tool already wrote something must not leave his message to be sent (and recorded) again. */
class FixWaveAgentTest {
    private val store = MemStore()
    private val clock = TestClock()

    private class Ctx(val text: String) : ContextSource {
        override fun block() = text
        override val unwellReducedByCode = false
        override val closedFeatureWords = emptyList<String>()
        override val neverBelowKg = 0.0
        override val mayPlayScene = false
        override val mayBeSweet = false
    }

    private class Writer(name: String, private val writes: Boolean) : Tool {
        override val spec = ToolSpec(name, "測試", buildJsonObject {
            put("type", "object"); put("properties", JsonObject(emptyMap())); put("required", JsonArray(emptyList())); put("additionalProperties", false)
        })
        override val feature: Feature? = null
        override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolOut {
            if (writes) ctx.store.add(Days.today(ctx.clock), ctx.clock.now(), Kind.WATER, Water(450))
            return ToolOut(buildJsonObject { put("note", "已記：喝水 0.5 壺") })
        }
    }

    private fun agent(llm: ScriptedLlm, tool: Tool) = Agent(
        llm = llm, small = ScriptedLlm(), store = store, clock = clock, tools = { listOf(tool) },
        toolCtx = { ids -> ToolCtx(store, clock, Fixtures.rules(), StageState(joinedOn = "2030-01-07", stage = 2), ids) },
        context = { Ctx("現在 9:00") }, system = { "系統" },
    )

    @Test fun recordsSavedBeforeAFailedReplyAreNotAskedForAgain() = runBlocking {
        val r = agent(ScriptedLlm(calls("water"), fails(Fail.OFFLINE)), Writer("water", writes = true)).reply("喝了半壺", emptyList())
        assertEquals(SayResult.Ok, r)
        val owner = store.messages(10).single { it.role == Role.OWNER }
        assertTrue(owner.meta[Agent.META_FAILED] != JsonPrimitive(true))
        val note = store.messages(10).single { it.role == Role.NOTE }
        assertTrue(note.text, note.text.contains("已經記下來") && note.text.contains("喝水 0.5 壺"))
        assertEquals(1, store.entries(Days.today(clock), Kind.WATER).size)
    }

    @Test fun aFailureWithNothingWrittenIsStillNotSent() = runBlocking {
        val r = agent(ScriptedLlm(calls("look"), fails(Fail.OFFLINE)), Writer("look", writes = false)).reply("今天怎樣", emptyList())
        assertTrue(r is SayResult.NotSent)
        assertEquals(JsonPrimitive(true), store.messages(10).single { it.role == Role.OWNER }.meta[Agent.META_FAILED])
        assertTrue(store.messages(10).none { it.role == Role.NOTE })
    }
}
