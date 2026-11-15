package app.dshmobile.ui.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思考強度 comes back from dsh's own modelSelection when the app process died and the cache is new. */
class ChatUiCacheTest {
    private fun sel(json: String) = Json.parseToJsonElement(json)

    @Test fun aNewCacheTakesTheStrengthDshUsesNext() {
        val ui = ChatUiCache()
        ui.seedEffort(sel("""{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"low"},"next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"}}"""))
        assertEquals("max", ui.effort)
        assertTrue(ui.effortKnown)
    }

    @Test fun withoutNextItIsTheLastUsedOne() {
        val ui = ChatUiCache()
        ui.seedEffort(sel("""{"lastUsed":{"provider":"anthropic","model":"claude-opus-5-5","reasoningEffort":"xhigh"},"next":null}"""))
        assertEquals("xhigh", ui.effort)
    }

    /** dsh records its own default when nothing was chosen; that stays 預設, so the label does not turn into 高 by itself. */
    @Test fun dshsDefaultStrengthStaysTheDefault() {
        val ui = ChatUiCache()
        ui.seedEffort(sel("""{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"},"next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"}}"""))
        assertNull(ui.effort)
    }

    @Test fun aStrengthTheModelDoesNotHaveIsNotTaken() {
        val ui = ChatUiCache()
        // DeepSeek has no 中 (medium)
        ui.seedEffort(sel("""{"lastUsed":null,"next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"medium"}}"""))
        assertNull(ui.effort)
    }

    @Test fun aChosenNextModelWithoutAStrengthIsTheDefault() {
        val ui = ChatUiCache()
        ui.seedEffort(sel("""{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"},"next":{"provider":"deepseek-official","model":"deepseek-v4-pro"}}"""))
        assertNull("next says no strength was chosen", ui.effort)
    }

    @Test fun theOwnersChoiceIsNeverOverwritten() {
        val ui = ChatUiCache()
        ui.effort = null // the owner picked 預設 before dsh's projection arrived
        ui.seedEffort(sel("""{"lastUsed":null,"next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"}}"""))
        assertNull(ui.effort)
        val picked = ChatUiCache().apply { effort = "low" }
        picked.seedEffort(sel("""{"lastUsed":null,"next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"}}"""))
        assertEquals("low", picked.effort)
    }

    @Test fun onlyTheFirstProjectionCounts() {
        val ui = ChatUiCache()
        ui.seedEffort(null) // not loaded yet: nothing decided
        ui.seedEffort(sel("""{"lastUsed":null,"next":null}""")) // a new conversation: 預設
        assertNull(ui.effort)
        ui.seedEffort(sel("""{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"},"next":null}"""))
        assertNull("no change by itself later", ui.effort)
    }

    @Test fun reviewRoundsReportsOnlyRealChanges() {
        val ui = ChatUiCache()
        val seen = mutableListOf<Int>()
        ui.onReviewRounds = { seen += it }
        ui.reviewRounds = 2
        ui.reviewRounds = 2
        ui.reviewRounds = 0
        assertEquals(listOf(2, 0), seen)
    }
}
