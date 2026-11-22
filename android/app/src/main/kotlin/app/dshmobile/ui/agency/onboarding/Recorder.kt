package app.dshmobile.ui.agency.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.media.AudioRecorder
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 清唱錄音 in the app (arch T07): 開始錄音 (the microphone permission is asked here, the first time), 停 (or three minutes),
 * 聽一次, 重錄, 交出. Only 交出 keeps it: the plaintext file is sealed into the vault and deleted; leaving the screen throws an
 * unfinished one away.
 */
@Composable
fun Recorder(agency: AgencyApp) {
    val context = LocalContext.current
    val recorder = remember { AudioRecorder(AudioRecorder.recordDir(context), agency.recorderEngine()) }
    val player = remember { agency.playerEngine() }
    val state by recorder.state.collectOnMain()
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            recorder.discard()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) recorder.start()
    }
    fun start() {
        val has = agency.hasRecorderSeam() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (has) recorder.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(state) {
        val s = state
        while (s is AudioRecorder.State.Recording && recorder.state.value == s) {
            elapsed = System.currentTimeMillis() - s.startedAt
            delay(250)
        }
        if (s is AudioRecorder.State.Recorded) elapsed = s.durationMs
    }
    Text(
        stringResource(R.string.ag_ob_rec_time, clock(elapsed)), color = Sakura.Text, fontSize = 22.sp,
        modifier = Modifier.testTag(OnboardingTags.REC_TIME),
    )
    when {
        denied -> Text(stringResource(R.string.ag_ob_rec_no_permission), color = Sakura.TextSecondary, fontSize = 13.sp)
        state is AudioRecorder.State.Failed -> Text(stringResource(R.string.ag_ob_rec_failed), color = Sakura.TextSecondary, fontSize = 13.sp)
        saving -> Text(stringResource(R.string.ag_ob_rec_saving), color = Sakura.TextSecondary, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val s = state) {
            AudioRecorder.State.Idle, AudioRecorder.State.Failed ->
                PinkButton(stringResource(R.string.ag_ob_rec_start), onClick = { elapsed = 0; start() }, modifier = Modifier.testTag(OnboardingTags.REC_START))
            is AudioRecorder.State.Recording ->
                PinkButton(stringResource(R.string.ag_ob_rec_stop), onClick = recorder::stop, modifier = Modifier.testTag(OnboardingTags.REC_STOP))
            is AudioRecorder.State.Recorded -> {
                SoftButton(
                    stringResource(if (playing) R.string.ag_ob_rec_stop_play else R.string.ag_ob_rec_play),
                    onClick = {
                        if (playing) {
                            player.stop()
                            playing = false
                        } else {
                            playing = true
                            player.play(s.file) { scope.launch { onMain { playing = false } } }
                        }
                    },
                    modifier = Modifier.testTag(OnboardingTags.REC_PLAY),
                )
                SoftButton(stringResource(R.string.ag_ob_rec_redo), onClick = {
                    player.stop()
                    playing = false
                    recorder.redo()
                    elapsed = 0
                }, modifier = Modifier.testTag(OnboardingTags.REC_REDO))
                PinkButton(stringResource(R.string.ag_ob_rec_hand_in), enabled = !saving, onClick = {
                    player.stop()
                    playing = false
                    val file = recorder.take() ?: return@PinkButton
                    saving = true
                    scope.launch {
                        agency.handInRecording(file, s.durationMs)
                        onMain { saving = false }
                    }
                }, modifier = Modifier.testTag(OnboardingTags.REC_HAND_IN))
            }
        }
    }
}

/** 0:00 … 3:00. */
internal fun clock(ms: Long): String {
    val sec = (ms / 1000).coerceIn(0, AudioRecorder.MAX_MS / 1000L)
    return String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
}
