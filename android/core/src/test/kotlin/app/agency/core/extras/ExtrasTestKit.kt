package app.agency.core.extras

import app.agency.core.base.TestClock
import app.agency.core.day.DayState
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.singing.Pitch
import app.agency.core.stage.StageState
import app.agency.core.store.MemStore
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.serialization.json.JsonNull

/** Shared helpers of the extras tests. Everything here is invented. */
internal object Kit {
    const val RATE = 16_000
    val rules: Rules = Fixtures.rules()

    /** Notes as (MIDI, ms); a MIDI of 0 is silence. */
    fun take(vararg notes: Pair<Int, Int>, detune: Double = 0.0, amp: Double = 8000.0): ShortArray {
        val out = ArrayList<Short>()
        var phase = 0.0
        for ((m, ms) in notes) {
            val hz = if (m == 0) 0.0 else Pitch.hz(m * 100 + detune)
            repeat(RATE * ms / 1000) {
                out += if (m == 0) 0 else (amp * sin(phase)).toInt().toShort()
                phase += 2 * PI * hz / RATE
            }
        }
        return out.toShortArray()
    }

    fun ok(text: String): ChatResult =
        ChatResult.Ok(text, emptyList(), Turn.Assistant(Wire.OPENAI, JsonNull, text, emptyList()), null)

    class FakeLlm(private val reply: ChatResult) : LlmClient {
        val requests = ArrayList<ChatRequest>()
        override suspend fun chat(request: ChatRequest): ChatResult {
            requests += request
            return reply
        }
    }

    const val GOOD_SUGGESTION =
        """{"dance":{"score":3,"reason":"動作大致跟上拍子"},"visual":{"score":4,"reason":"表情自然"},""" +
            """"star":{"score":2,"reason":"舞台上還不夠放開"}}"""

    fun stage(stage: Int, joined: LocalDate = TestClock.MONDAY.minusDays(60), startedOn: LocalDate = joined) =
        StageState(joinedOn = joined.toString(), stage = stage, startedOn = startedOn.toString())

    fun stateOf(store: MemStore, day: LocalDate, stage: StageState, clock: TestClock): DayState =
        DayState.of(day, store.entries(day), store.entries(day.minusDays(14), day.minusDays(1)), rules, stage, clock.zone)
}
