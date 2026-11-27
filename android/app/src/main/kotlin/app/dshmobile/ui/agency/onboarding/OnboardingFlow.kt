package app.dshmobile.ui.agency.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.agency.core.brain.handlers.BaselineSteps
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.Baseline
import app.agency.core.rules.CoachRules
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.MediaNotice
import app.dshmobile.ui.agency.AgencyActions
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of 入社建檔. */
object OnboardingTags {
    const val PANEL = "ob-panel"
    const val STEP = "ob-step-"
    const val SKIP = "ob-skip"
    const val CONFIRM = "ob-confirm"
    const val TAKE_PHOTO = "ob-take-photo"
    const val THUMB = "ob-thumb-"
    const val REC_TIME = "ob-rec-time"
    const val REC_START = "ob-rec-start"
    const val REC_STOP = "ob-rec-stop"
    const val REC_PLAY = "ob-rec-play"
    const val REC_REDO = "ob-rec-redo"
    const val REC_HAND_IN = "ob-rec-hand-in"
    const val PLANK_TIME = "ob-plank-time"
    const val PLANK_START = "ob-plank-start"
    const val PLANK_STOP = "ob-plank-stop"
    const val PLANK_HAND_IN = "ob-plank-hand-in"
    const val MISSING = "ob-missing"
    const val HAND_IN_LATER = "ob-hand-in-later"
    const val FILE = "ob-file"
    const val FILE_DIALOG = "ob-file-dialog"
    const val FILE_PLAY = "ob-file-play"
    const val MEDIA_NOTICE = "ob-media-notice"
}

/**
 * The open 入社建檔 question's panel, above the composer (arch T07): which item of seven, and its way to answer — 對 for the
 * numbers read out, 拍照 for the three photos, the recorder, the plank stopwatch — plus 跳過. Words he types answer too (the
 * brain reads them first); 對, 跳過 and the stopwatch send the same words he could type, so the conversation shows them.
 */
@Composable
fun OnboardingFlow(agency: AgencyApp, status: OnboardingStatus, actions: AgencyActions) {
    val step = status.open ?: return
    val notice by agency.mediaNotice.collectOnMain()
    Panel(title = stringResource(R.string.ag_ob_title), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag(OnboardingTags.PANEL)) {
        Text(
            stringResource(R.string.ag_ob_progress, step.ordinal + 1, OnboardingStep.entries.size, step.zh),
            color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(OnboardingTags.STEP + step.name),
        )
        when (step) {
            OnboardingStep.BODY -> {
                Text(stringResource(R.string.ag_ob_body_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
                Chip(stringResource(R.string.ag_ob_confirm), onClick = { agency.send("對") }, modifier = Modifier.testTag(OnboardingTags.CONFIRM))
            }
            OnboardingStep.PHOTO_FRONT, OnboardingStep.PHOTO_SIDE, OnboardingStep.PHOTO_BACK ->
                ThreePhotos(step, status.baseline, agency.mediaVault(), actions.takePhoto)
            OnboardingStep.RECORDING -> Recorder(agency)
            OnboardingStep.PUSHUPS -> Text(stringResource(R.string.ag_ob_type_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
            OnboardingStep.PLANK -> PlankTimer(onHandIn = { sec -> agency.send("棒式 $sec 秒") })
        }
        notice?.let {
            Text(
                stringResource(
                    when (it) {
                        MediaNotice.NO_CAMERA -> R.string.ag_ob_no_camera
                        MediaNotice.LOCKED -> R.string.ag_ob_locked
                        MediaNotice.NOT_SAVED -> R.string.ag_ob_not_saved
                        MediaNotice.NO_CAMERA_PERMISSION -> R.string.ag_media_no_camera_permission
                    },
                ),
                color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.testTag(OnboardingTags.MEDIA_NOTICE),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SoftButton(stringResource(R.string.ag_ob_skip), onClick = { agency.send("跳過") }, modifier = Modifier.testTag(OnboardingTags.SKIP))
        }
    }
}

/**
 * 入社資料 on the trainee card: 「入社資料缺 N 項」 with 補交 once 入社建檔 was walked through, and the button that opens what
 * was handed in.
 */
@Composable
fun BaselineBadges(agency: AgencyApp, status: OnboardingStatus) {
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status.completed && status.open == null && status.missing.isNotEmpty()) {
            Text(
                stringResource(R.string.ag_ob_missing, status.missing.size), color = Sakura.Text, fontSize = 12.sp,
                modifier = Modifier.testTag(OnboardingTags.MISSING),
            )
            Chip(stringResource(R.string.ag_ob_hand_in_later), onClick = agency::reopenOnboarding, modifier = Modifier.testTag(OnboardingTags.HAND_IN_LATER))
        }
        if (status.baseline != null) Chip(stringResource(R.string.ag_ob_file), onClick = { open = true }, modifier = Modifier.testTag(OnboardingTags.FILE))
    }
    val b = status.baseline
    if (open && b != null) BaselineFile(agency, b) { open = false }
}

/** What 入社建檔 holds: the numbers, the three photos (decrypted into memory only) and the recording to listen to again. */
@Composable
private fun BaselineFile(agency: AgencyApp, b: Baseline, onClose: () -> Unit) {
    val vault = agency.mediaVault()
    val player = remember { agency.playerEngine() }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.stop() } }
    Dialog(onDismissRequest = onClose) {
        Panel(title = stringResource(R.string.ag_ob_file), modifier = Modifier.testTag(OnboardingTags.FILE_DIALOG)) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val missing = stringResource(R.string.ag_ob_file_missing)
                Text(
                    if (b.heightCm != null && b.weightKg != null) stringResource(R.string.ag_ob_file_body, CoachRules.kg(b.heightCm!!), CoachRules.kg(b.weightKg!!))
                    else OnboardingStep.BODY.zh + " " + missing,
                    color = Sakura.Text, fontSize = 14.sp,
                )
                Text(b.pushupsMax?.let { stringResource(R.string.ag_ob_file_pushups, it) } ?: (OnboardingStep.PUSHUPS.zh + " " + missing), color = Sakura.Text, fontSize = 14.sp)
                Text(b.plankMaxSec?.let { stringResource(R.string.ag_ob_file_plank, it) } ?: (OnboardingStep.PLANK.zh + " " + missing), color = Sakura.Text, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnboardingStep.PHOTOS.forEach { step ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Thumb(BaselineSteps.mediaOf(b, step), vault, Modifier.fillMaxWidth().aspectRatio(.75f))
                            Text(photoLabel(step) + if (BaselineSteps.has(b, step)) "" else " " + missing, color = Sakura.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                val rec = b.recording
                if (rec == null || vault == null) {
                    Text(stringResource(R.string.ag_ob_file_recording) + " " + missing, color = Sakura.Text, fontSize = 14.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ag_ob_file_recording), color = Sakura.Text, fontSize = 14.sp)
                        SoftButton(stringResource(if (playing) R.string.ag_ob_rec_stop_play else R.string.ag_ob_rec_play), onClick = {
                            if (playing) {
                                player.stop()
                                playing = false
                            } else {
                                playing = true
                                scope.launch {
                                    val bytes = withContext(Dispatchers.IO) { runCatching { vault.get(rec) }.getOrNull() }
                                    onMain {
                                        if (bytes == null) {
                                            failed = true
                                            playing = false
                                        } else {
                                            player.play(bytes) { scope.launch { onMain { playing = false } } }
                                        }
                                    }
                                }
                            }
                        }, modifier = Modifier.testTag(OnboardingTags.FILE_PLAY))
                        if (failed) Text(stringResource(R.string.ag_ob_file_cannot_open), color = Sakura.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            PinkButton(stringResource(R.string.ag_ob_close), onClick = onClose)
        }
    }
}
