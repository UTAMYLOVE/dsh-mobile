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
}
