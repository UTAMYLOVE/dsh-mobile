package app.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dshmobile.R
import app.dshmobile.service.DshFailReason
import app.dshmobile.service.DshFailure
import app.dshmobile.service.DshPhase
import app.dshmobile.service.DshStatus
import app.dshmobile.service.monotonicMs
import kotlinx.coroutines.delay

/** After this long in one phase the line says it takes longer than usual. */
const val DSH_SLOW_MS = 30_000L

/** After this long in one phase the hard 「強制結束 dsh 並重啟」 is offered. */
const val DSH_STUCK_MS = 90_000L

object DshStatusTags {
    const val PANEL = "dsh-status"
    const val PHASE = "dsh-status-phase"
    const val SLOW = "dsh-status-slow"
    const val FORCE = "dsh-status-force"
    const val FAILURE = "dsh-status-failure"
    const val APP_SETTINGS = "dsh-status-app-settings"
}

/**
 * The detailed dsh status (stage 2 §A/§G): the exact phase, seconds in it, what it waits on, 「比平常久」 after 30 s, the hard
 * restart after 90 s, and on failure the step and the reason in plain words. When even the hard stop leaves old processes the
 * only cure is Android's force stop, so it links to the app's system settings page.
 */
@Composable
fun DshStatusView(
    status: DshStatus,
    onForceRestart: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = ::monotonicMs,
) {
    var nowMs by remember { mutableLongStateOf(now()) }
    LaunchedEffect(status.phase, status.sinceMs) {
        while (true) {
            nowMs = now()
            delay(1_000)
        }
    }
    val failure = status.failure
    if (!status.busy && failure == null && status.leftover == 0) return
    val seconds = ((nowMs - status.sinceMs).coerceAtLeast(0) / 1_000).toInt()
    Column(modifier.testTag(DshStatusTags.PANEL), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status.busy) {
            Text(
                "${phaseText(status.phase)} · $seconds 秒" + (waitingText(status)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(DshStatusTags.PHASE).semantics { liveRegion = LiveRegionMode.Polite },
            )
            val inPhase = nowMs - status.sinceMs
            if (inPhase >= DSH_SLOW_MS) {
                Text(stringResource(R.string.dsh_status_slow), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(DshStatusTags.SLOW))
            }
            if (inPhase >= DSH_STUCK_MS) {
                TextButton(onClick = onForceRestart, modifier = Modifier.testTag(DshStatusTags.FORCE)) { Text(stringResource(R.string.dsh_status_force)) }
            }
        }
        if (failure != null) {
            Text(
                failureText(failure),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(DshStatusTags.FAILURE).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (failure.reason == DshFailReason.STILL_RUNNING) {
                TextButton(onClick = onForceRestart, modifier = Modifier.testTag(DshStatusTags.FORCE)) { Text(stringResource(R.string.dsh_status_force)) }
                Text(stringResource(R.string.dsh_status_android_force), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenAppSettings, modifier = Modifier.testTag(DshStatusTags.APP_SETTINGS)) { Text(stringResource(R.string.dsh_status_open_app_settings)) }
            }
        }
    }
}

@Composable
private fun phaseText(phase: DshPhase): String = stringResource(
    when (phase) {
        DshPhase.STOPPING -> R.string.dsh_phase_stopping
        DshPhase.SWEEPING -> R.string.dsh_phase_sweeping
        DshPhase.STARTING -> R.string.dsh_phase_starting
        DshPhase.HEALTH_CHECK -> R.string.dsh_phase_health
        DshPhase.REGISTERING -> R.string.dsh_phase_registering
        DshPhase.RUNNING -> R.string.dsh_phase_running
        DshPhase.STOPPED -> R.string.dsh_phase_stopped
    },
)

@Composable
private fun waitingText(s: DshStatus): String? = when {
    (s.phase == DshPhase.STOPPING || s.phase == DshPhase.SWEEPING) && s.remaining > 0 -> stringResource(R.string.dsh_wait_processes, s.remaining)
    s.phase == DshPhase.HEALTH_CHECK && s.port != null -> stringResource(R.string.dsh_wait_port, s.port)
    s.phase == DshPhase.STARTING -> stringResource(R.string.dsh_wait_launch)
    else -> null
}

@Composable
private fun failureText(f: DshFailure): String {
    val step = phaseText(f.step)
    val why = when (f.reason) {
        DshFailReason.STILL_RUNNING -> stringResource(R.string.dsh_fail_still_running, f.number ?: 0)
        DshFailReason.LAUNCH_TIMEOUT -> stringResource(R.string.dsh_fail_launch_timeout, f.number ?: 0)
        DshFailReason.EXITED_EARLY -> stringResource(R.string.dsh_fail_exited, f.number ?: -1)
        DshFailReason.HEALTH_FAILED -> stringResource(R.string.dsh_fail_health)
        DshFailReason.ROOTFS_MISSING -> stringResource(R.string.dsh_fail_rootfs)
        DshFailReason.LAUNCH_ERROR -> stringResource(R.string.dsh_fail_launch_error)
        DshFailReason.OTHER -> stringResource(R.string.dsh_fail_other)
    }
    return stringResource(R.string.dsh_fail_line, step, why)
}
