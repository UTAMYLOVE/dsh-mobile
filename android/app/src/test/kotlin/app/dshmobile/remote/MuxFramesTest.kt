package app.dshmobile.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuxFramesTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun clientFramesHaveExactKeySets() {
        val args = buildJsonObject { put("request", buildJsonObject { put("sessionId", "session-1") }) }
        val open = obj(MuxFrames.open("s1", "session/follow", args))
        assertEquals(setOf("type", "streamId", "endpoint", "payload"), open.keys)
        assertEquals(obj("""{"type":"open","streamId":"s1","endpoint":"session/follow","payload":{"args":{"request":{"sessionId":"session-1"}}}}"""), open)

        assertEquals(obj("""{"type":"item","streamId":"s1","value":"x"}"""), obj(MuxFrames.item("s1", JsonPrimitive("x"))))
        val bare = obj(MuxFrames.item("s1", null))
        assertEquals(setOf("type", "streamId"), bare.keys)
        assertEquals(obj("""{"type":"item","streamId":"s1","value":null}"""), obj(MuxFrames.item("s1", JsonNull)))

        assertEquals(obj("""{"type":"end","streamId":"s1"}"""), obj(MuxFrames.end("s1")))
        assertEquals(obj("""{"type":"cancel","streamId":"s1"}"""), obj(MuxFrames.cancel("s1")))
    }

    @Test fun serverFramesParse() {
        assertEquals(ServerFrame.Item("s1", obj("""{"type":"baseline","value":{"items":[]}}""")),
            MuxFrames.parse("""{"type":"item","streamId":"s1","value":{"type":"baseline","value":{"items":[]}}}"""))
        assertEquals(ServerFrame.Item("s1", null), MuxFrames.parse("""{"type":"item","streamId":"s1"}"""))
        assertEquals(ServerFrame.Item("s1", JsonNull), MuxFrames.parse("""{"type":"item","streamId":"s1","value":null}"""))
        assertEquals(ServerFrame.Error("s2", "session/not-found", "no such session"),
            MuxFrames.parse("""{"type":"error","streamId":"s2","error":{"code":"session/not-found","message":"no such session","details":{}}}"""))
        assertEquals(ServerFrame.Error("s2", "refused", ""), MuxFrames.parse("""{"type":"error","streamId":"s2","error":{"code":"see http://x/?token=y"}}"""))
        assertEquals(ServerFrame.Error("s2", "refused", ""), MuxFrames.parse("""{"type":"error","streamId":"s2"}"""))
        assertEquals(ServerFrame.End("s3"), MuxFrames.parse("""{"type":"end","streamId":"s3"}"""))
    }

    @Test fun unknownOrBrokenFramesAreNull() {
        for (text in listOf(
            """{"type":"ping"}""", """{"type":"ping","streamId":"s1"}""", "[]", "not json", "", "null", "42",
            """{"type":"item"}""", """{"type":"end","streamId":""}""", """{"type":"item","streamId":5}""", """{"streamId":"s1"}""",
            """{"type":7,"streamId":"s1"}""",
        )) {
            assertNull(text, MuxFrames.parse(text))
        }
    }
}
