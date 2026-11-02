package app.dshmobile.config

import android.content.SharedPreferences
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.system.PhantomState

/**
 * [AgentSettings] and the heartbeat interval in app-private preferences (no secrets in here). Values read back are
 * repaired into range, so a corrupt or hand-edited file can never produce an invalid [AgentSettings].
 */
class AgentSettingsStore(private val prefs: SharedPreferences) {
    /** Nothing stored yet gives [AgentSettings.defaults]. A stored Claude model stays stored even without a key; the config writer falls back. */
    fun load(phantom: PhantomState, hasClaudeKey: Boolean): AgentSettings {
        val d = AgentSettings.defaults(phantom, hasClaudeKey)
        return AgentSettings(
            maxConcurrent = prefs.getInt(KEY_CONCURRENT, d.maxConcurrent).coerceIn(AgentSettings.MAX_CONCURRENT_RANGE),
            maxDepth = prefs.getInt(KEY_DEPTH, d.maxDepth).coerceIn(AgentSettings.MAX_DEPTH_RANGE),
            teamSize = prefs.getInt(KEY_TEAM, d.teamSize).coerceIn(AgentSettings.TEAM_SIZE_RANGE),
            leadModel = prefs.getString(KEY_MODEL, null)?.takeIf { AgentModels.find(it) != null } ?: d.leadModel,
            agentTeams = prefs.getBoolean(KEY_TEAMS, d.agentTeams),
        )
    }

    fun save(s: AgentSettings) {
        prefs.edit()
            .putInt(KEY_CONCURRENT, s.maxConcurrent)
            .putInt(KEY_DEPTH, s.maxDepth)
            .putInt(KEY_TEAM, s.teamSize)
            .putString(KEY_MODEL, s.leadModel)
            .putBoolean(KEY_TEAMS, s.agentTeams)
            .apply()
    }

    /** Minutes between heartbeats, always within the scheduler's 30..60. */
    var heartbeatIntervalMin: Int
        get() = prefs.getInt(KEY_HEARTBEAT, HeartbeatScheduler.DEFAULT_INTERVAL_MIN)
            .coerceIn(HeartbeatScheduler.MIN_INTERVAL_MIN, HeartbeatScheduler.MAX_INTERVAL_MIN)
        set(value) {
            prefs.edit().putInt(KEY_HEARTBEAT, value.coerceIn(HeartbeatScheduler.MIN_INTERVAL_MIN, HeartbeatScheduler.MAX_INTERVAL_MIN)).apply()
        }

    companion object {
        const val PREFS = "dshm_agent_settings"
        private const val KEY_CONCURRENT = "max_concurrent"
        private const val KEY_DEPTH = "max_depth"
        private const val KEY_TEAM = "team_size"
        private const val KEY_MODEL = "lead_model"
        private const val KEY_TEAMS = "agent_teams"
        private const val KEY_HEARTBEAT = "heartbeat_min"
    }
}
