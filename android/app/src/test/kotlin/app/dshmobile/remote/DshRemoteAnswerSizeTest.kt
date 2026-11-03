package app.dshmobile.remote

import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.FakeDshServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Controller ruling: DshRemote's unary answers are read up to 16 MiB (DshClient.postJsonForText stays at 1 MiB). */
class DshRemoteAnswerSizeTest {
    private val scope = CoroutineScope(SupervisorJob())
    @Volatile private var size = 0
    private val dsh = FakeDshServer(rpc = { answerOf(size) })
    private val remote = DshRemote(DshClient(OkHttpClient(), dsh.launch), scope)

    @After fun cleanup() { remote.close(); scope.cancel(); dsh.close() }

    /** An `ok:true` answer whose body is exactly [bytes] long. */
    private fun answerOf(bytes: Int): MockResponse =
        MockResponse().setResponseCode(200).setBody(HEAD + "a".repeat(bytes - HEAD.length - TAIL.length) + TAIL)

    @Test fun aTwoMebibyteAnswerIsReadWhole() = runBlocking {
        size = 2 shl 20
        val value = remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
        assertEquals(size - HEAD.length - TAIL.length, value.jsonObject["text"]!!.jsonPrimitive.content.length)
    }

    @Test fun anAnswerOverSixteenMebibytesFailsWithASafeCode() = runBlocking {
        size = (16 shl 20) + 1
        try {
            remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
            fail("an answer over 16 MiB must fail")
        } catch (e: DshRpcException) {
            assertEquals("response-too-large", e.code)
            assertEquals(e.code, DshErrors.safeCode(e.code))
            assertFalse(e.toString(), e.toString().contains("token="))
        }
        size = 16 shl 20
        remote.call("session/list", buildJsonObject { putJsonObject("_request") {} }) // exactly at the cap is fine
        Unit
    }

    private companion object {
        const val HEAD = """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"text":""""
        const val TAIL = """"}}}"""
    }
}
