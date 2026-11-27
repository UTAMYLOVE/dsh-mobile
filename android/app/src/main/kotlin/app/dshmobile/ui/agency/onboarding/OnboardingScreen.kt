package app.dshmobile.ui.agency.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.agency.core.brain.handlers.BaselineSteps
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.rules.CoachRules
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.MediaNotice
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActions
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.ButtonSize
import app.dshmobile.ui.kit.Card
import app.dshmobile.ui.kit.GhostButton
import app.dshmobile.ui.kit.IconTile
import app.dshmobile.ui.kit.Icons
import app.dshmobile.ui.kit.LineIcon
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.ProgressBar
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import app.dshmobile.ui.kit.Status
import app.dshmobile.ui.kit.StatusPill
import app.dshmobile.ui.kit.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 入社建檔 as its own page (redesign 03): it opens by itself on the first open and from 入社資料 later. 「已完成 N／7 項」 with
 * its bar; five cards for the seven items (身高體重, three photos, 清唱錄音, 伏地挺身, 棒式), each with 已完成／還沒做／已跳過 and
 * 跳過 while not done; every answer is saved at once (nothing is asked in the conversation). The footer lists 「還差：…」 by name,
 * 先存著，之後補 (leave) and 完成建檔 (once every item is done or skipped); after that her one line comes in the conversation.
 */
@Composable
fun OnboardingScreen(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions, onClose: () -> Unit) {
    val revision by ready.brain.revision.collectOnMain()
    val status by produceState<OnboardingStatus?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.onboardingStatus() }.getOrNull() } }
    }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { runCatching { block() } } }
    }
    val s = status
    val done = s?.done.orEmpty()
    val skipped = s?.skipped.orEmpty()
    val total = OnboardingStep.entries.size
    val read = s?.completed == true
    Column(Modifier.fillMaxSize().background(Sakura.Page).imePadding().testTag(OnboardingTags.SCREEN)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.ag_ob_leave)
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp).semantics { contentDescription = back }.testTag(OnboardingTags.BACK)) { LineIcon(Icons.Back) }
            Text(stringResource(R.string.ag_ob_title), style = Type.Title)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (read) Modifier.testTag(OnboardingTags.FILE_DIALOG) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ag_ob_done_count, done.size, total), style = Type.Heading, modifier = Modifier.weight(1f).testTag(OnboardingTags.COUNT))
                    Text(stringResource(R.string.ag_ob_autosave), style = Type.Support)
                }
                ProgressBar(done.size.toFloat() / total)
                Text(stringResource(R.string.ag_ob_leave_hint), style = Type.Caption)
            }
            val notice by agency.mediaNotice.collectOnMain()
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
                    style = Type.Support.copy(color = Sakura.RoseBrown), modifier = Modifier.testTag(OnboardingTags.MEDIA_NOTICE),
                )
            }
            BodyCard(s, done, skipped, onSave = { h, w -> run { ready.brain.onboardingBody(h, w) } }, onSkip = { run { ready.brain.onboardingSkip(OnboardingStep.BODY) } })
            PhotosCard(agency, s, done, skipped, actions.takePhoto, onSkip = { OnboardingStep.PHOTOS.filter { it !in done }.forEach { st -> run { ready.brain.onboardingSkip(st) } } })
            RecordingCard(agency, s, done, skipped, onSkip = { run { ready.brain.onboardingSkip(OnboardingStep.RECORDING) } })
            PushupsCard(s, done, skipped, onSave = { n -> run { ready.brain.onboardingPushups(n) } }, onSkip = { run { ready.brain.onboardingSkip(OnboardingStep.PUSHUPS) } })
            PlankCard(s, done, skipped, onSave = { sec -> run { ready.brain.onboardingPlank(sec) } }, onSkip = { run { ready.brain.onboardingSkip(OnboardingStep.PLANK) } })
        }
        Footer(s, read, onLater = onClose, onFinish = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { ready.brain.finishOnboarding() } }
                onMain {
                    actions.askNotifications()
                    onClose()
                }
            }
        })
    }
}

/** Test tags of the 入社建檔 page (03), its cards and the older names the tests keep. */
object OnboardingTags {
    const val SCREEN = "ob-screen"
    const val BACK = "ob-back"
    const val COUNT = "ob-count"
    const val STEP = "ob-step-"
    const val STATUS = "ob-status-"
    const val SKIP = "ob-skip-"
    const val HEIGHT = "ob-height"
    const val WEIGHT = "ob-weight"
    const val BODY_SAVE = "ob-body-save"
    const val EDIT = "ob-edit-"
    const val PUSH_FIELD = "ob-push-field"
    const val PUSH_SAVE = "ob-push-save"
    const val FINISH = "ob-finish"
    const val LATER = "ob-later"
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
    const val BANNER = "ob-banner"
    const val FILE = "ob-file"
    const val FILE_DIALOG = "ob-file-dialog"
    const val FILE_PLAY = "ob-file-play"
    const val MEDIA_NOTICE = "ob-media-notice"
}

@Composable
private fun StepHeader(
    icon: ImageVector, title: String, sub: String?, step: OnboardingStep, done: Boolean, skipped: Boolean,
    status: String? = null, trailing: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, tone = if (done) Sakura.AquaSoft else Sakura.Background, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Label)
            sub?.let { Text(it, style = Type.Support) }
        }
        val kind = when {
            done -> Status.Done
            skipped -> Status.Skipped
            else -> Status.Todo
        }
        StatusPill(
            kind,
            status ?: stringResource(
                when (kind) {
                    Status.Done -> R.string.ag_ob_status_done
                    Status.Skipped -> R.string.ag_ob_status_skipped
                    else -> R.string.ag_ob_status_todo
                },
            ),
            modifier = Modifier.testTag(OnboardingTags.STATUS + step.name),
        )
        trailing()
    }
}

@Composable
private fun SkipButton(step: OnboardingStep, onSkip: () -> Unit) {
    GhostButton(stringResource(R.string.ag_ob_skip), onClick = onSkip, modifier = Modifier.testTag(OnboardingTags.SKIP + step.name))
}

@Composable
private fun BodyCard(s: OnboardingStatus?, done: Set<OnboardingStep>, skipped: Set<OnboardingStep>, onSave: (Double, Double) -> Unit, onSkip: () -> Unit) {
    val b = s?.baseline
    val isDone = OnboardingStep.BODY in done
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.testTag(OnboardingTags.STEP + OnboardingStep.BODY.name)) {
        val value = if (isDone && b != null) stringResource(R.string.ag_ob_body_value, CoachRules.kg(b.heightCm!!), CoachRules.kg(b.weightKg!!)) else null
        StepHeader(Icons.Ruler, stringResource(R.string.ag_ob_body_title), value, OnboardingStep.BODY, isDone, OnboardingStep.BODY in skipped) {
            if (isDone && !editing) GhostButton(stringResource(R.string.ag_ob_edit), onClick = { editing = true }, modifier = Modifier.testTag(OnboardingTags.EDIT + OnboardingStep.BODY.name))
            else if (!isDone) SkipButton(OnboardingStep.BODY, onSkip)
        }
        if (!isDone || editing) {
            var h by rememberSaveable { mutableStateOf(b?.heightCm?.let(CoachRules::kg).orEmpty()) }
            var w by rememberSaveable { mutableStateOf(b?.weightKg?.let(CoachRules::kg).orEmpty()) }
            val hv = h.toDoubleOrNull()
            val wv = w.toDoubleOrNull()
            val ok = hv != null && wv != null && hv in 120.0..230.0 && wv in 30.0..250.0
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.ag_ob_height), h, { h = it }, OnboardingTags.HEIGHT, Modifier.weight(1f))
                NumberField(stringResource(R.string.ag_ob_weight), w, { w = it }, OnboardingTags.WEIGHT, Modifier.weight(1f))
            }
            if (h.isNotEmpty() && w.isNotEmpty() && !ok) Text(stringResource(R.string.ag_ob_body_bad), style = Type.Support.copy(color = Sakura.RoseBrown))
            SoftButton(stringResource(R.string.ag_save), onClick = { if (ok) { onSave(hv!!, wv!!); editing = false } }, enabled = ok, modifier = Modifier.testTag(OnboardingTags.BODY_SAVE))
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onValue(v.filter { it.isDigit() || it == '.' }.take(5)) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(12.dp), modifier = modifier.testTag(tag),
    )
}

@Composable
private fun photoTake(step: OnboardingStep): String = stringResource(
    when (step) {
        OnboardingStep.PHOTO_FRONT -> R.string.ag_ob_take_front
        OnboardingStep.PHOTO_SIDE -> R.string.ag_ob_take_side
        else -> R.string.ag_ob_take_back
    },
)

@Composable
private fun PhotosCard(
    agency: AgencyApp, s: OnboardingStatus?, done: Set<OnboardingStep>, skipped: Set<OnboardingStep>, onTake: (OnboardingStep) -> Unit, onSkip: () -> Unit,
) {
    val photos = OnboardingStep.PHOTOS
    val left = photos.count { it !in done }
    val allDone = left == 0
    val anySkipped = photos.any { it in skipped }
    val next = photos.firstOrNull { it !in done }
    Card(Modifier.testTag(OnboardingTags.STEP + OnboardingStep.PHOTO_FRONT.name)) {
        StepHeader(
            Icons.Camera, stringResource(R.string.ag_ob_photos_title), stringResource(R.string.ag_ob_photos_sub), OnboardingStep.PHOTO_FRONT, allDone,
            anySkipped && !allDone, status = if (!allDone && left < photos.size) stringResource(R.string.ag_ob_photos_left, left) else null,
        ) { if (!allDone) SkipButton(OnboardingStep.PHOTO_FRONT, onSkip) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { step ->
                Column(Modifier.weight(1f).testTag(OnboardingTags.STEP + step.name), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val media = s?.baseline?.let { BaselineSteps.mediaOf(it, step) }
                    if (media != null) {
                        Box {
                            Thumb(media, agency.mediaVault(), Modifier.fillMaxWidth().aspectRatio(.75f).clickable { onTake(step) })
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Sakura.Aqua, CircleShape), contentAlignment = Alignment.Center) {
                                LineIcon(Icons.Check, tint = Sakura.AquaInk, size = 14.dp)
                            }
                        }
                    } else {
                        // The frame is the button, and its words are the button's (「拍背面」).
                        val accent = LocalAccent.current.fill
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(.75f).drawBehind {
                                drawRoundRect(accent, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                            }.clickable { onTake(step) }.then(if (step == next) Modifier.testTag(OnboardingTags.TAKE_PHOTO) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LineIcon(Icons.Camera)
                                Text(photoTake(step), style = Type.Label)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.ag_ob_photo_state, photoLabel(step), stringResource(if (step in done) R.string.ag_ob_status_done else if (step in skipped) R.string.ag_ob_status_skipped else R.string.ag_ob_status_todo)),
                        style = Type.Caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(agency: AgencyApp, s: OnboardingStatus?, done: Set<OnboardingStep>, skipped: Set<OnboardingStep>, onSkip: () -> Unit) {
    val isDone = OnboardingStep.RECORDING in done
    Card(Modifier.testTag(OnboardingTags.STEP + OnboardingStep.RECORDING.name)) {
        StepHeader(Icons.Mic, stringResource(R.string.ag_ob_rec_title), stringResource(R.string.ag_ob_rec_sub), OnboardingStep.RECORDING, isDone, OnboardingStep.RECORDING in skipped) {
            if (!isDone) SkipButton(OnboardingStep.RECORDING, onSkip)
        }
        val rec = s?.baseline?.recording
        if (isDone && rec != null) Playback(agency, rec) else Recorder(agency)
    }
}

/** The recording handed in, to listen to again (decrypted into memory only). */
@Composable
private fun Playback(agency: AgencyApp, rec: String) {
    val vault = agency.mediaVault() ?: return
    val player = remember { agency.playerEngine() }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.stop() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton(stringResource(if (playing) R.string.ag_ob_rec_stop_play else R.string.ag_ob_rec_play), icon = Icons.Play, onClick = {
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
        if (failed) Text(stringResource(R.string.ag_ob_file_cannot_open), style = Type.Support)
    }
}

@Composable
private fun PushupsCard(s: OnboardingStatus?, done: Set<OnboardingStep>, skipped: Set<OnboardingStep>, onSave: (Int) -> Unit, onSkip: () -> Unit) {
    val isDone = OnboardingStep.PUSHUPS in done
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.testTag(OnboardingTags.STEP + OnboardingStep.PUSHUPS.name)) {
        val value = s?.baseline?.pushupsMax?.takeIf { isDone }?.let { stringResource(R.string.ag_ob_push_value, it) } ?: stringResource(R.string.ag_ob_push_sub)
        StepHeader(Icons.Dumbbell, stringResource(R.string.ag_ob_push_title), value, OnboardingStep.PUSHUPS, isDone, OnboardingStep.PUSHUPS in skipped) {
            if (isDone && !editing) GhostButton(stringResource(R.string.ag_ob_edit), onClick = { editing = true }, modifier = Modifier.testTag(OnboardingTags.EDIT + OnboardingStep.PUSHUPS.name))
            else if (!isDone) SkipButton(OnboardingStep.PUSHUPS, onSkip)
        }
        if (!isDone || editing) {
            var n by rememberSaveable { mutableStateOf("") }
            val v = n.toIntOrNull()?.takeIf { it in 0..500 }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = n, onValueChange = { t -> n = t.filter(Char::isDigit).take(3) }, singleLine = true,
                    label = { Text(stringResource(R.string.ag_ob_push_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).testTag(OnboardingTags.PUSH_FIELD),
                )
                SoftButton(stringResource(R.string.ag_save), onClick = { v?.let { onSave(it); editing = false } }, enabled = v != null, modifier = Modifier.testTag(OnboardingTags.PUSH_SAVE))
            }
        }
    }
}

@Composable
private fun PlankCard(s: OnboardingStatus?, done: Set<OnboardingStep>, skipped: Set<OnboardingStep>, onSave: (Int) -> Unit, onSkip: () -> Unit) {
    val isDone = OnboardingStep.PLANK in done
    var timing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.testTag(OnboardingTags.STEP + OnboardingStep.PLANK.name)) {
        val value = s?.baseline?.plankMaxSec?.takeIf { isDone }?.let { stringResource(R.string.ag_ob_plank_value, it) } ?: stringResource(R.string.ag_ob_plank_sub)
        StepHeader(Icons.Clock, stringResource(R.string.ag_ob_plank_title), value, OnboardingStep.PLANK, isDone, OnboardingStep.PLANK in skipped) {
            if (!isDone) SkipButton(OnboardingStep.PLANK, onSkip)
        }
        if (!isDone || timing) PlankTimer(onHandIn = { sec -> onSave(sec); timing = false })
        else GhostButton(stringResource(R.string.ag_ob_plank_again), onClick = { timing = true })
    }
}

@Composable
private fun Footer(s: OnboardingStatus?, read: Boolean, onLater: () -> Unit, onFinish: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier.fillMaxWidth().background(Sakura.White, shape).border(1.dp, Sakura.Divider, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val missing = s?.missing.orEmpty()
        val names = missing.map { short(it) }
        Text(
            if (missing.isEmpty()) stringResource(R.string.ag_ob_nothing_missing) else stringResource(R.string.ag_ob_missing_list, names.joinToString("、")),
            style = Type.Support, modifier = Modifier.testTag(OnboardingTags.MISSING),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (read) {
                PinkButton(stringResource(R.string.ag_ob_close), onClick = onLater, modifier = Modifier.weight(1f), size = ButtonSize.Large)
            } else {
                SoftButton(stringResource(R.string.ag_ob_save_later), onClick = onLater, modifier = Modifier.weight(1f).testTag(OnboardingTags.LATER), size = ButtonSize.Large)
                PinkButton(stringResource(R.string.ag_ob_finish), onClick = onFinish, enabled = s?.canFinish == true, modifier = Modifier.weight(1f).testTag(OnboardingTags.FINISH), size = ButtonSize.Large)
            }
        }
    }
}

/** 「背面照」 for a photo, the item's name otherwise (the footer's 「還差：…」). */
@Composable
internal fun short(step: OnboardingStep): String = when (step) {
    OnboardingStep.PHOTO_FRONT, OnboardingStep.PHOTO_SIDE, OnboardingStep.PHOTO_BACK -> photoLabel(step) + stringResource(R.string.ag_ob_photo_suffix)
    else -> step.zh
}
