package app.dshmobile.ui.agency.training

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.StrengthReport
import app.agency.core.engine.RoutineRunner
import app.agency.core.engine.RoutineStep
import app.agency.core.engine.RunnerEvent
import app.agency.core.engine.StrengthMove
import app.agency.core.model.TrainingKind
import app.dshmobile.R
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.Segmented
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.delay

/** Test tags of the training screens (T18). */
object TrainingTags {
    const val CORE = "ag-train-core"
    const val STRENGTH = "ag-train-strength"
    const val STRETCH = "ag-train-stretch"
    const val WALK = "ag-train-walk"
    const val SCREEN = "ag-train-screen"
    const val START = "ag-train-start"
    const val PAUSE = "ag-train-pause"
    const val RESUME = "ag-train-resume"
    const val NEXT = "ag-train-next"
    const val QUIT = "ag-train-quit"
    const val STEP = "ag-train-step"
    const val TIME = "ag-train-time"
    const val UP_NEXT = "ag-train-up-next"
    const val REPORT = "ag-train-report"
    const val REPS = "ag-train-reps-"
    const val EXTRA = "ag-train-extra-"
    const val SEND = "ag-train-send"
}

/** A beep and a buzz when the move changes (no permission: the view's haptics and a notification-stream tone). */
@Composable
private fun rememberCue(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull() }
    DisposableEffect(tone) { onDispose { runCatching { tone?.release() } } }
    return {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS) }
    }
}

/**
 * The guided timer of one routine (T18): each move's name, picture, time left (or reps) and how to do it, and what comes
 * next; a timed move goes on by itself with a beep and a buzz, a rep move waits for 「做完了」. When it is over: the core and
 * the stretch report at once ([onFinished] with the seconds it really ran); the rounds ask for the last round first.
 * 離開 before the end records nothing.
 */
@Composable
fun TrainingScreen(
    kind: TrainingKind,
    steps: List<RoutineStep>,
    moves: List<StrengthMove>,
    onFinished: (seconds: Int, report: StrengthReport?) -> Unit,
    onClose: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    val runner = remember(steps) { RoutineRunner(steps) }
    var frame by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val cue = rememberCue()
    fun play(events: List<RunnerEvent>) {
        events.forEach { e ->
            cue()
            if (e == RunnerEvent.Finished) {
                running = false
                if (kind == TrainingKind.STRENGTH) reporting = true else onFinished((runner.elapsedMs / 1000).toInt(), null)
            }
        }
        frame++
    }
    LaunchedEffect(running) {
        var last = now()
        while (running && runner.running) {
            delay(TICK_MS)
            val t = now()
            play(runner.tick(t - last))
            last = t
        }
    }
    val title = stringResource(
        when (kind) {
            TrainingKind.CORE7 -> R.string.ag_train_core
            TrainingKind.STRENGTH -> R.string.ag_train_strength
            else -> R.string.ag_train_stretch
        },
    )
    Column(
        Modifier.fillMaxSize().background(Sakura.Background).verticalScroll(rememberScrollState()).padding(20.dp).testTag(TrainingTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Sakura.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        when {
            reporting -> StrengthReportForm(moves, steps.mapNotNull { it.round }.maxOrNull() ?: 1) { report ->
                onFinished((runner.elapsedMs / 1000).toInt(), report.copy(seconds = (runner.elapsedMs / 1000).toInt()))
            }
            !runner.started -> {
                Text(stringResource(R.string.ag_train_intro, steps.count { !it.rest }), color = Sakura.TextSecondary, fontSize = 15.sp)
                steps.filter { !it.rest }.distinctBy { it.name }.forEach { s ->
                    Text("${s.name}　${s.amount}", color = Sakura.Text, fontSize = 15.sp)
                }
                PinkButton(stringResource(R.string.ag_train_start), onClick = {
                    play(runner.start())
                    running = true
                }, modifier = Modifier.testTag(TrainingTags.START))
                SoftButton(stringResource(R.string.ag_train_quit), onClick = onClose, modifier = Modifier.testTag(TrainingTags.QUIT))
            }
            else -> Running(runner, frame, onPause = {
                runner.pause()
                running = false
                frame++
            }, onResume = {
                runner.resume()
                running = true
                frame++
            }, onNext = {
                play(runner.next())
                running = runner.running
            }, onClose = onClose)
        }
    }
}

@Composable
private fun Running(runner: RoutineRunner, frame: Int, onPause: () -> Unit, onResume: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    // [frame] changes whenever the runner does: the runner itself is one object, so it is what makes this redraw.
    frame.let { }
    val step = runner.current
    val total = runner.steps.size
    Text(
        stringResource(R.string.ag_train_progress, runner.index + 1, total) + (step.round?.let { "　" + stringResource(R.string.ag_train_round, it) }.orEmpty()),
        color = Sakura.TextSecondary, fontSize = 14.sp,
    )
    MovePictogram(step.icon, LocalAccent.current.fill, size = 140.dp)
    Text(step.name, color = Sakura.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(TrainingTags.STEP))
    val amount = if (step.seconds != null) stringResource(R.string.ag_train_seconds_left, runner.remainingSeconds) else step.amount
    Text(amount, color = Sakura.Text, fontSize = 40.sp, modifier = Modifier.testTag(TrainingTags.TIME))
    Text(step.how, color = Sakura.TextSecondary, fontSize = 15.sp)
    val upcoming = runner.upcoming
    Text(
        upcoming?.let { stringResource(R.string.ag_train_up_next, it.name + " " + it.amount) } ?: stringResource(R.string.ag_train_last),
        color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(TrainingTags.UP_NEXT),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val nextLabel = if (step.reps != null) R.string.ag_train_done_reps else R.string.ag_train_next
        PinkButton(stringResource(nextLabel), onClick = onNext, modifier = Modifier.testTag(TrainingTags.NEXT))
        if (step.seconds != null) {
            if (runner.paused) {
                SoftButton(stringResource(R.string.ag_train_resume), onClick = onResume, modifier = Modifier.testTag(TrainingTags.RESUME))
            } else {
                SoftButton(stringResource(R.string.ag_train_pause), onClick = onPause, modifier = Modifier.testTag(TrainingTags.PAUSE))
            }
        }
    }
    SoftButton(stringResource(R.string.ag_train_quit_unfinished), onClick = onClose, modifier = Modifier.testTag(TrainingTags.QUIT))
}

/** The last round of the rounds: reps (the plank: seconds) of each move, and how many more he could have done. */
@Composable
private fun StrengthReportForm(moves: List<StrengthMove>, rounds: Int, onSend: (StrengthReport) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { moves.forEach { m -> put(m.key, (m.reps ?: m.seconds ?: 0).toString()) } } }
    var extra by remember { mutableIntStateOf(-1) }
    Column(
        Modifier.fillMaxWidth().background(Sakura.White, RoundedCornerShape(20.dp)).padding(16.dp).testTag(TrainingTags.REPORT),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.ag_train_report_title, rounds), color = Sakura.Text, fontSize = 17.sp)
        moves.forEach { m ->
            val unit = stringResource(if (m.seconds != null) R.string.ag_train_unit_seconds else R.string.ag_train_unit_reps)
            OutlinedTextField(
                value = values[m.key].orEmpty(), onValueChange = { v -> values[m.key] = v.filter(Char::isDigit).take(3) }, singleLine = true,
                label = { Text("${m.name}（$unit）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(TrainingTags.REPS + m.key),
            )
        }
        Text(stringResource(R.string.ag_train_extra), color = Sakura.Text, fontSize = 15.sp)
        Segmented(EXTRA_OPTIONS, extra, onSelect = { extra = it }, tagPrefix = TrainingTags.EXTRA)
        PinkButton(stringResource(R.string.ag_train_send), enabled = extra >= 0 && moves.all { values[it.key]?.toIntOrNull() != null }, onClick = {
            onSend(StrengthReport(rounds, moves.associate { it.key to (values[it.key]?.toIntOrNull() ?: 0) }, extra, fromTimer = true))
        }, modifier = Modifier.testTag(TrainingTags.SEND))
    }
}

private const val TICK_MS = 200L
private const val TONE_VOLUME = 80
private const val TONE_MS = 250

/** 還能多做幾下: 0 to 5 (5 is 「5 下以上」). */
private val EXTRA_OPTIONS = listOf("0", "1", "2", "3", "4", "5+")
