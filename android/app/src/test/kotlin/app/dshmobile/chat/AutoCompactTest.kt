package app.dshmobile.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactTest {
    @Test fun aLongIdleConversationIsCondensedOnce() {
        assertFalse(AutoCompact.due(null, running = false, lastAt = 0))
        assertFalse(AutoCompact.due(AutoCompact.TOKENS - 1, running = false, lastAt = 0))
        assertTrue(AutoCompact.due(AutoCompact.TOKENS, running = false, lastAt = 0))
        assertFalse("never in the middle of a turn", AutoCompact.due(500_000, running = true, lastAt = 0))
        // the size only changes with the next turn: the same number does not condense again
        assertFalse(AutoCompact.due(130_000, running = false, lastAt = 130_000))
        // it did not help and the conversation kept growing: again after a real step, not every turn
        assertFalse(AutoCompact.due(140_000, running = false, lastAt = 130_000))
        assertTrue(AutoCompact.due(160_000, running = false, lastAt = 130_000))
        // it helped (the prompt fell back under the line), then grew past it again
        assertFalse(AutoCompact.due(40_000, running = false, lastAt = 130_000))
    }

    /**
     * After a dsh restart the app has forgotten when it condensed (lastAt 0). dsh's last request still said 150k, but its
     * compaction-aware projection says the next prompt is 40k: not condensed again, and the context bar is not full.
     */
    @Test fun aCondensedConversationIsNotCondensedAgainAfterADshRestart() {
        val snapshot = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"snapshot","cursor":9,"records":[],
               "projections":{"values":{"contextPressure":{"pressureTokens":150000,"projectedTokens":40000,"contextWindow":1000000}}}}""",
        )
        val s = ConversationReducer.reduce(ConversationState("s1"), snapshot)
        assertFalse(AutoCompact.due(s.context?.tokens, running = false, lastAt = 0))
        assertTrue(s.context!!.fraction!! < 0.05f)
        // not yet condensed: still over the line, so it is
        val big = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"snapshot","cursor":9,"records":[],
               "projections":{"values":{"contextPressure":{"pressureTokens":150000,"projectedTokens":152000,"contextWindow":1000000}}}}""",
        )
        assertTrue(AutoCompact.due(ConversationReducer.reduce(ConversationState("s1"), big).context?.tokens, running = false, lastAt = 0))
    }
}
