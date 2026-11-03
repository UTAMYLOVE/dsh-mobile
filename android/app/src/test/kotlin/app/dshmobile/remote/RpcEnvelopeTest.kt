package app.dshmobile.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcEnvelopeTest {
    @Test fun requestHasExactlyTheProtocolKeys() {
        val o = Json.parseToJsonElement(RpcEnvelope.request("session/list", buildJsonObject { putJsonObject("_request") {} }, "r1")).jsonObject
        assertEquals(setOf("type", "rpcId", "method", "payload"), o.keys)
        assertEquals("client-request", o["type"]!!.jsonPrimitive.content)
        assertEquals("r1", o["rpcId"]!!.jsonPrimitive.content)
        assertEquals("session/list", o["method"]!!.jsonPrimitive.content)
        assertEquals(setOf("args"), o["payload"]!!.jsonObject.keys)
        assertEquals(buildJsonObject { putJsonObject("_request") {} }, o["payload"]!!.jsonObject["args"])
    }

    @Test fun okResultReturnsTheValue() {
        val r = RpcEnvelope.parse("""{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"items":[]}}}""")
        assertEquals(RpcResult.Ok(buildJsonObject { putJsonArray("items") {} }), r)
    }

    @Test fun okWithoutValueIsJsonNull() {
        assertEquals(RpcResult.Ok(JsonNull), RpcEnvelope.parse("""{"type":"server-response","rpcId":"r1","result":{"ok":true}}"""))
    }

    @Test fun errResultCarriesCodeAndMessage() {
        val r = RpcEnvelope.parse(
            """{"type":"server-response","rpcId":"r1","result":{"ok":false,"error":{"code":"gateway/internal","message":"spawn xdg-user-dir ENOENT","details":{}}}}""",
        )
        assertEquals(RpcResult.Err("gateway/internal", "spawn xdg-user-dir ENOENT"), r)
    }

    @Test fun garbageIsAnErrNotAThrow() {
        for (body in listOf("<html>", "", "[]", """{"result":"x"}""", """{"result":{"ok":"maybe"}}""")) {
            val r = RpcEnvelope.parse(body)
            assertTrue("$body → $r", r is RpcResult.Err)
            assertEquals("unexpected-answer", (r as RpcResult.Err).code)
        }
    }
}
