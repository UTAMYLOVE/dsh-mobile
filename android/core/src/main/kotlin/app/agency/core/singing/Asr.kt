package app.agency.core.singing

import app.agency.core.llm.CallChecks
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.agency.core.metering.UsageParser
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpOutcome
import app.agency.core.net.Transport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The only code that sends a recording (T19d 咬字), and only after his one-time consent ([consented]): DeepSeek's speech to text
 * (api-docs.deepseek.com, 「Speech to Text (ASR)」 guide and Models & Pricing, read 2026-11-27): `POST /v1/tools/asr`,
 * multipart with one `file` field (16-bit PCM WAV), the language detected from the audio, at most 120 seconds and 8 MiB; the
 * answer `{request_id, text, duration_ms}`; billed by audio time (list price 0.116 US dollars an hour, booked at list price).
 * Through the one network gate, under the spend cap and the call budget, metered.
 */
class DeepSeekAsr(
    private val transport: Transport,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val consented: () -> Boolean,
    private val url: HttpUrl = URL.toHttpUrl(),
) {
    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failed(val fail: Fail?) : Result
    }

    suspend fun transcribe(wav: ByteArray): Result {
        if (!consented()) return Result.Failed(null)
        val pcm = Wav.read(wav) ?: return Result.Failed(Fail.BAD_RESPONSE)
        if (wav.size > MAX_BYTES || pcm.samples.size.toLong() * 1000 / pcm.rate > MAX_MS) return Result.Failed(Fail.BAD_RESPONSE)
        val k = key()
        checks.blocked(k)?.let { return Result.Failed(it) }
        checks.takeBudget()?.let { return Result.Failed(it) }
        val outcome = transport.postFile(url, mapOf("Authorization" to "Bearer ${CallChecks.usableKey(k)}"), "file", "take.wav", wav, "audio/wav")
        Fail.of(outcome)?.let { return Result.Failed(it) }
        val o = UsageParser.parseObject((outcome as HttpOutcome.Response).body) ?: return Result.Failed(Fail.BAD_RESPONSE)
        val ms = (o["duration_ms"] as? JsonPrimitive)?.longOrNull ?: (pcm.samples.size.toLong() * 1000 / pcm.rate)
        runCatching { usage.record(Provider.DEEPSEEK, MODEL, Usage(0, 0, asrMs = ms)) }
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Result.Failed(Fail.BAD_RESPONSE)
        return Result.Ok(text)
    }

    companion object {
        const val URL = "https://api.deepseek.com/v1/tools/asr"
        const val MODEL = "deepseek-asr"
        const val MAX_BYTES = 8 shl 20
        const val MAX_MS = 120_000L
        /** List price, US dollars per hour of audio. */
        const val USD_PER_HOUR = 0.116
    }
}
