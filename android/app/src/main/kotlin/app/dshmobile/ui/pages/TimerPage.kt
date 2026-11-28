package app.dshmobile.ui.pages

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.dshmobile.R
import app.dshmobile.ui.TimerPlan
import app.dshmobile.ui.TimerUi
import app.dshmobile.ui.rememberIoRunner
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PageFrame
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.PillShape
import app.dshmobile.ui.theme.Type
import kotlinx.coroutines.delay

/** One stretch of the timer: a move or a rest between rounds. */
class TimerSegment(val round: Int, val moveIndex: Int, val name: String, val seconds: Int, val rest: Boolean)

/** Where the timer is at a given elapsed time. [segment] is null once finished. */
class TimerState(val segment: TimerSegment?, val index: Int, val remaining: Int, val finished: Boolean)

/**
 * The per-move countdown as a pure function of elapsed seconds, so it stays right when the app was in the background:
 * the page feeds it wall-clock time, never a tick count.
 */
class TimerEngine(plan: TimerPlan) {
    val segments: List<TimerSegment> = buildList {
        for (r in 0 until plan.rounds) {
            plan.moves.forEachIndexed { i, m -> add(TimerSegment(r, i, m.name, m.seconds.coerceAtLeast(1), rest = false)) }
            if (r < plan.rounds - 1 && plan.restSeconds > 0) add(TimerSegment(r, -1, "", plan.restSeconds, rest = true))
        }
    }
    val totalSeconds: Int = segments.sumOf { it.seconds }

    fun at(elapsed: Int): TimerState {
        var left = elapsed.coerceAtLeast(0)
        segments.forEachIndexed { i, s ->
            if (left < s.seconds) return TimerState(s, i, s.seconds - left, false)
            left -= s.seconds
        }
        return TimerState(null, segments.size, 0, true)
    }

    /** Elapsed seconds at which the segment after [index] begins. */
    fun endOf(index: Int): Int = segments.take(index + 1).sumOf { it.seconds }
}

private fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/**
 * The training timer. 「開始」 starts it, 「暫停」 holds it, 「跳過」 jumps to the next move. At the end he says
 * 輕鬆 / 剛好 / 吃力; the time the app spent in the background is recorded with it.
 */
@Composable
fun TimerPage(
    timer: TimerUi,
    task: String,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    tickMs: Long = 250,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    val plan = rememberLoaded(task) { timer.plan(task) }.value
    val run = rememberIoRunner()
    var startedAt by remember { mutableLongStateOf(-1L) }
    var pausedAt by remember { mutableLongStateOf(-1L) }
    var pausedTotal by remember { mutableLongStateOf(0L) }
    var skipped by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(0L) }
    var stoppedAt by remember { mutableLongStateOf(-1L) }
    var awayMs by remember { mutableLongStateOf(0L) }
    val running = startedAt >= 0 && pausedAt < 0

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (running) stoppedAt = clock() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (stoppedAt >= 0) {
            awayMs += clock() - stoppedAt
            stoppedAt = -1L
        }
    }
    LaunchedEffect(running) {
        while (running) {
            now = clock()
            delay(tickMs)
        }
    }

    PageFrame(plan?.title ?: stringResource(R.string.timer_title), onBack) {
        if (plan == null) return@PageFrame
        val engine = remember(plan) { TimerEngine(plan) }
        val ref = if (pausedAt >= 0) pausedAt else now
        val elapsed = if (startedAt < 0) 0 else (((ref - startedAt - pausedTotal) / 1000).toInt() + skipped).coerceAtLeast(0)
        val st = engine.at(elapsed)
        if (st.finished && startedAt >= 0) {
            KText(stringResource(R.string.timer_done), size = Type.Heading, bold = true, modifier = Modifier.testTag("timer_done"))
            KText(stringResource(R.string.timer_feel), size = Type.Body)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("EASY" to R.string.feel_easy, "OK" to R.string.feel_ok, "HARD" to R.string.feel_hard).forEach { (k, label) ->
                    PillButton(stringResource(label), {
                        val minutes = ((engine.totalSeconds + 59) / 60).coerceAtLeast(1)
                        val away = (awayMs / 1000).toInt()
                        run { timer.finish(task, plan.rounds, minutes, k, away) }
                        onFinished()
                    }, Modifier.testTag("feel_$k"), if (k == "OK") Pal.Mint else Pal.Butter)
                }
            }
            return@PageFrame
        }
        Block(Modifier.testTag("timer_card"), fill = Pal.White) {
            val seg = st.segment
            KText(
                if (startedAt < 0) stringResource(R.string.timer_ready)
                else if (seg?.rest == true) stringResource(R.string.timer_rest) else seg?.name ?: "",
                size = 26.sp, bold = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("timer_move"),
            )
            KText(
                mmss(if (startedAt < 0) plan.moves.firstOrNull()?.seconds ?: 0 else st.remaining), size = 64.sp, bold = true,
                align = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("timer_clock"),
            )
            if (seg != null && startedAt >= 0) {
                KText(
                    stringResource(R.string.timer_round, seg.round + 1, plan.rounds), size = Type.Small, alpha = 0.7f,
                    align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            val frac = if (engine.totalSeconds == 0) 0f else (elapsed.toFloat() / engine.totalSeconds).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(12.dp).clip(PillShape).background(Pal.Paper).border(2.dp, Pal.Ink, PillShape)) {
                Box(Modifier.fillMaxWidth(frac).height(12.dp).background(Pal.PinkStrong))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            when {
                startedAt < 0 -> PillButton(stringResource(R.string.timer_start), {
                    startedAt = clock(); now = startedAt
                }, Modifier.testTag("timer_start"))
                pausedAt >= 0 -> PillButton(stringResource(R.string.timer_resume), {
                    pausedTotal += clock() - pausedAt; pausedAt = -1L
                }, Modifier.testTag("timer_resume"), Pal.Mint)
                else -> PillButton(stringResource(R.string.timer_pause), { pausedAt = clock() }, Modifier.testTag("timer_pause"), Pal.Butter)
            }
            if (startedAt >= 0) {
                PillButton(stringResource(R.string.timer_skip), {
                    // jump to where the next segment begins
                    skipped += (engine.endOf(st.index) - elapsed).coerceAtLeast(1)
                }, Modifier.testTag("timer_skip"), Pal.Lavender)
            }
        }
        // the moves of the round, for a glance
        Block(fill = Pal.Paper) {
            plan.moves.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    KText(m.name, size = Type.Small); KText(mmss(m.seconds), size = Type.Small, alpha = 0.7f)
                }
            }
            Column { KText(stringResource(R.string.timer_rounds_note, plan.rounds, plan.restSeconds), size = Type.Tiny, alpha = 0.6f, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
