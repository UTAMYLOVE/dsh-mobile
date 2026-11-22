package app.dshmobile.ui.agency.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.delay

/** The plank stopwatch's arithmetic: whole seconds between start and stop (or now), never negative. */
class Stopwatch(private val now: () -> Long) {
    var startedAt: Long? = null
        private set
    var stoppedAt: Long? = null
        private set

    fun start() {
        startedAt = now()
        stoppedAt = null
    }

    fun stop() {
        if (startedAt != null && stoppedAt == null) stoppedAt = now()
    }

    val running: Boolean get() = startedAt != null && stoppedAt == null

    fun seconds(): Int {
        val s = startedAt ?: return 0
        return (((stoppedAt ?: now()) - s).coerceAtLeast(0) / 1000).toInt()
    }
}

/**
 * 棒式最久幾秒 with the in-app stopwatch (arch T07): 開始計時 → 撐不住了 → 交出 N 秒, which sends 「棒式 N 秒」 as his answer
 * (the same words he could type). Too short a hold (under 5 s) is not offered.
 */
@Composable
fun PlankTimer(onHandIn: (Int) -> Unit, now: () -> Long = System::currentTimeMillis) {
    val watch = remember { Stopwatch(now) }
    var started by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var shown by remember { mutableLongStateOf(0L) }
    LaunchedEffect(started, stopped) {
        while (watch.running) {
            shown = watch.seconds().toLong()
            delay(200)
        }
        shown = watch.seconds().toLong()
    }
    Text("$shown", color = Sakura.Text, fontSize = 34.sp, modifier = Modifier.testTag(OnboardingTags.PLANK_TIME))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            !started || (stopped && watch.seconds() < MIN_SEC) -> PinkButton(stringResource(R.string.ag_ob_plank_start), onClick = {
                watch.start()
                started = true
                stopped = false
            }, modifier = Modifier.testTag(OnboardingTags.PLANK_START))
            !stopped -> PinkButton(stringResource(R.string.ag_ob_plank_stop), onClick = {
                watch.stop()
                stopped = true
            }, modifier = Modifier.testTag(OnboardingTags.PLANK_STOP))
            else -> {
                val sec = watch.seconds()
                PinkButton(stringResource(R.string.ag_ob_plank_hand_in, sec), onClick = { onHandIn(sec) }, modifier = Modifier.testTag(OnboardingTags.PLANK_HAND_IN))
                SoftButton(stringResource(R.string.ag_ob_plank_again), onClick = {
                    watch.start()
                    stopped = false
                })
            }
        }
    }
}

private const val MIN_SEC = 5
