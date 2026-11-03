package app.dshmobile.config

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.system.PhantomState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSettingsStoreTest {
    private val prefs = InMemorySharedPreferences()
    private val store = AgentSettingsStore(prefs)

    @Test fun nothingSavedMeansDefaults() {
        assertEquals(AgentSettings.defaults(PhantomState.DISABLED, false), store.load(PhantomState.DISABLED, false))
        assertEquals(6, store.load(PhantomState.ENABLED, true).maxConcurrent)
    }

    @Test fun saveThenLoadRoundTrips() {
        val s = AgentSettings(maxConcurrent = 12, maxDepth = 3, teamSize = 5, leadModel = "claude-opus-5-5", agentTeams = false)
        store.save(s)
        assertEquals(s, AgentSettingsStore(prefs).load(PhantomState.ENABLED, hasClaudeKey = true))
    }

    @Test fun savedValuesWinOverPhantomDefaults() {
        store.save(AgentSettings(maxConcurrent = 3, leadModel = "deepseek-flash"))
        assertEquals(3, store.load(PhantomState.DISABLED, false).maxConcurrent)
    }

    @Test fun aStoredClaudeModelIsKeptEvenWithoutAKey() {
        // The writer falls back when it renders; the stored choice survives a key being cleared and added again.
        store.save(AgentSettings(maxConcurrent = 6, leadModel = "claude-sonnet-5-5"))
        assertEquals("claude-sonnet-5-5", store.load(PhantomState.UNKNOWN, hasClaudeKey = false).leadModel)
    }

    @Test fun outOfRangeAndUnknownStoredValuesAreRepaired() {
        prefs.edit().putInt("max_concurrent", 500).putInt("max_depth", -4).putInt("team_size", 0)
            .putString("lead_model", "no-such-model").apply()
        val s = store.load(PhantomState.UNKNOWN, false)
        assertEquals(32, s.maxConcurrent)
        assertEquals(0, s.maxDepth)
        assertEquals(1, s.teamSize)
        assertEquals("deepseek-flash", s.leadModel)
    }

    @Test fun heartbeatIntervalDefaultsClampsAndRoundTrips() {
        assertEquals(45, store.heartbeatIntervalMin)
        store.heartbeatIntervalMin = 35
        assertEquals(35, AgentSettingsStore(prefs).heartbeatIntervalMin)
        store.heartbeatIntervalMin = 5
        assertEquals(30, store.heartbeatIntervalMin)
        store.heartbeatIntervalMin = 500
        assertEquals(60, store.heartbeatIntervalMin)
        prefs.edit().putInt("heartbeat_min", 1).apply()
        assertEquals(30, store.heartbeatIntervalMin)
    }
}
