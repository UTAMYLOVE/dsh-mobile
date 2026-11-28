package app.agency.core.agent.tools

import app.agency.core.agent.Hit
import app.agency.core.agent.Ports
import app.agency.core.agent.SearchPort
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.stage.StageState
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Violation
import app.agency.core.store.writeDoc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A store, a clock on a Monday and the fixture rules, at a chosen stage, for calling tools directly. */
internal class ToolRig(stage: Int = 2, val rules: Rules = Fixtures.rules(), val search: SearchPort? = FakeSearch()) {
    val store = MemStore()
    val clock = TestClock()
    val monday = TestClock.MONDAY

    init {
        val joined = monday.minusDays(((stage - 1) * 7).toLong())
        store.writeDoc(Doc.STAGE, StageState(joinedOn = joined.toString(), stage = stage, startedOn = monday.toString()))
        store.writeDoc(Doc.RULES, rules)
    }

    fun stage(): StageState = app.agency.core.stage.Today.stage(store, monday)

    fun ctx(photos: List<String> = emptyList(), gallery: List<Boolean> = emptyList()) =
        ToolCtx(store, clock, rules, stage(), photos, Ports(search), gallery)

    fun at(hhmm: String) = clock.set(Days.today(clock), hhmm)

    fun run(name: String, json: String, photos: List<String> = emptyList(), gallery: List<Boolean> = emptyList()): ToolOut {
        val tool = Tools.named(name) ?: error("no tool $name")
        return runBlocking { tool.run(Json.parseToJsonElement(json).jsonObject, ctx(photos, gallery)) }
    }

    fun violations(): List<String> = store.entries(Days.today(clock), Kind.VIOLATION).map { it.read<Violation>().code }

    class FakeSearch : SearchPort {
        var n = 0
        override suspend fun videos(query: String): List<Hit> = (1..6).map {
            n++
            Hit("影音教室第${n}集", "https://video.example.test/$n", "摘要", "重點一：先深呼吸再開口說話。重點二：每天固定練習十分鐘。重點三：錄音回放檢查自己。")
        }

        override suspend fun places(query: String): List<Hit> = listOf(Hit("測試餐盒店", "https://place.example.test/1", "便當"))
    }
}

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content
internal fun JsonObject.o(k: String): JsonObject = this[k]!!.jsonObject
internal fun ToolOut.err(): String = facts["error"]!!.jsonPrimitive.content
