package app.dshmobile.overview

import app.dshmobile.chat.SessionRow
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.Clock

/** Everything the overview page shows, at [nowMs]. Money is the app's own estimate (CostMeter and PriceTable), never dsh's. */
data class OverviewState(
    val deepseekUsd: Double,
    val claudeUsd: Double,
    val monthUsd: Double,
    val alertUsd: Double,
    /** Null while dsh is not connected. */
    val agentsRunning: Int?,
    val agentsLimit: Int,
    /** When the next heartbeat is due; null when none is scheduled. */
    val nextHeartbeatAtMs: Long?,
    val heartbeatIntervalMin: Int,
    val nowMs: Long,
    val reminders: List<Reminder>,
    val recent: List<SessionRow>,
    /** The running dsh's version when this app version has not verified it; null when verified or unknown. */
    val unverifiedDsh: String?,
) {
    /** Whole minutes until the next heartbeat, rounded up, never negative; null without a schedule. */
    val nextHeartbeatMin: Int?
        get() = nextHeartbeatAtMs?.let { (((it - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt() }

    /** How far through the interval we are (1 − remaining / interval), within 0..1; null without a schedule. */
    val heartbeatProgress: Float?
        get() = nextHeartbeatMin?.let { 1f - it.toFloat() / heartbeatIntervalMin.coerceAtLeast(1) }?.coerceIn(0f, 1f)
}

/**
 * Builds [OverviewState] from the cost meter, the reminder log and the live flows; [tickMs] (default a minute) moves `nowMs` so
 * the heartbeat countdown advances. The state is only computed while somebody collects, so collect it only while the overview is shown.
 */
class OverviewSource(
    private val meter: CostMeter,
    private val reminders: ReminderLog,
    private val agentsLimit: () -> Int,
    private val heartbeatIntervalMin: () -> Int,
    private val nextHeartbeatAt: Flow<Long?>,
    private val agentsRunning: Flow<Int?>,
    private val recent: Flow<List<SessionRow>>,
    private val unverifiedDshVersion: () -> String?,
    private val clock: Clock,
    private val tickMs: Long = 60_000,
) {
    private fun ticks(): Flow<Long> = flow {
        while (true) {
            emit(clock.millis())
            delay(tickMs)
        }
    }

    fun state(): Flow<OverviewState> = combine(
        combine(meter.revision, reminders.revision, ticks()) { _, _, now -> now },
        nextHeartbeatAt, agentsRunning, recent,
    ) { now, next, running, rows ->
        OverviewState(
            deepseekUsd = meter.todayUsd(Provider.DEEPSEEK),
            claudeUsd = meter.todayUsd(Provider.ANTHROPIC),
            monthUsd = meter.monthUsd(),
            alertUsd = meter.alertUsd,
            agentsRunning = running,
            agentsLimit = agentsLimit(),
            nextHeartbeatAtMs = next,
            heartbeatIntervalMin = heartbeatIntervalMin(),
            nowMs = now,
            reminders = reminders.recent(),
            recent = rows,
            unverifiedDsh = unverifiedDshVersion(),
        )
    }
}
