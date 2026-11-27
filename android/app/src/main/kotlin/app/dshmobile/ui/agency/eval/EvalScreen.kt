package app.dshmobile.ui.agency.eval

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.engine.EvalItem
import app.agency.core.engine.MonthlyResult
import app.agency.core.model.Media
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onboarding.Recorder
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.Segmented
import app.dshmobile.ui.kit.SoftButton

/** Test tags of the evaluation screen. */
object EvalTags {
    const val SCREEN = "ag-eval"
    const val DANCE = "ag-eval-dance"
    const val SEND = "ag-eval-send"
    const val CLOSE = "ag-eval-close"
    const val SELF = "ag-eval-self-"
    const val WEIGHT = "ag-eval-weight"
}

/**
 * 每週評鑑 (定稿 §10: this week's song and dance, one take each, no redo) or 月評 (a solo song, a whole dance, a rotating item,
 * his numbers and the fitness test; the full-body photo is the day's 全身對比照). Recordings are sealed on the phone; nothing
 * of them leaves it (the 月評 suggestion sees only the body photo and two still frames of the dance).
 */
@Composable
fun EvalScreen(agency: AgencyApp, monthly: Boolean, takeVideo: () -> Unit, onClose: () -> Unit) {
    var song by remember { mutableStateOf<Media?>(null) }
    var dance by remember { mutableStateOf<Media?>(null) }
    var rotating by remember { mutableStateOf<Media?>(null) }
    var rotKind by remember { mutableIntStateOf(0) }
    var weight by remember { mutableStateOf("") }
    var waist by remember { mutableStateOf("") }
    var pushups by remember { mutableStateOf("") }
    var plank by remember { mutableStateOf("") }
    val self = remember { mutableStateMapOf<EvalItem, Int>() }
    val video by agency.evalVideo.collectOnMain()
    LaunchedEffect(video) { if (video != null) dance = agency.takeEvalVideo() }
    Column(
        Modifier.fillMaxSize().background(Sakura.Background).verticalScroll(rememberScrollState()).padding(16.dp).testTag(EvalTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(if (monthly) R.string.ag_eval_monthly else R.string.ag_eval_weekly), color = Sakura.Text, fontSize = 22.sp)
        Text(stringResource(R.string.ag_eval_rule), color = Sakura.TextSecondary, fontSize = 14.sp)
        Text(stringResource(R.string.ag_eval_song), color = Sakura.Text, fontSize = 16.sp)
        if (song == null) {
            Recorder(agency, handIn = { file, _ -> agency.sealEvalAudio(file)?.also { song = it } != null }, redo = false)
        } else {
            Text(stringResource(R.string.ag_eval_song_done), color = Sakura.RoseBrown, fontSize = 14.sp)
        }
        Text(stringResource(R.string.ag_eval_dance), color = Sakura.Text, fontSize = 16.sp)
        if (dance == null) {
            SoftButton(stringResource(R.string.ag_eval_dance_take), onClick = takeVideo, modifier = Modifier.testTag(EvalTags.DANCE))
        } else {
            Text(stringResource(R.string.ag_eval_dance_done), color = Sakura.RoseBrown, fontSize = 14.sp)
        }
        val rotKinds = listOf(R.string.ag_eval_rot_rap, R.string.ag_eval_rot_genre, R.string.ag_eval_rot_remix).map { stringResource(it) }
        if (monthly) {
            Text(stringResource(R.string.ag_eval_rotating), color = Sakura.Text, fontSize = 16.sp)
            Segmented(rotKinds, rotKind, { rotKind = it })
            if (rotating == null) {
                if (song != null) Recorder(agency, handIn = { file, _ -> agency.sealEvalAudio(file)?.also { rotating = it } != null }, redo = false)
            } else {
                Text(stringResource(R.string.ag_eval_rotating_done), color = Sakura.RoseBrown, fontSize = 14.sp)
            }
            Text(stringResource(R.string.ag_eval_body_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
            Text(stringResource(R.string.ag_eval_numbers), color = Sakura.Text, fontSize = 16.sp)
            @Composable
            fun number(label: Int, v: String, tag: String?, set: (String) -> Unit) = OutlinedTextField(
                value = v, onValueChange = { t -> set(t.filter { it.isDigit() || it == '.' }.take(5)) }, singleLine = true,
                label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().let { m -> tag?.let { m.testTag(it) } ?: m },
            )
            number(R.string.ag_eval_weight, weight, EvalTags.WEIGHT) { weight = it }
            number(R.string.ag_eval_waist, waist, null) { waist = it }
            number(R.string.ag_eval_pushups, pushups, null) { pushups = it }
            number(R.string.ag_eval_plank, plank, null) { plank = it }
            Text(stringResource(R.string.ag_eval_self), color = Sakura.TextSecondary, fontSize = 13.sp)
            listOf(EvalItem.VOCAL, EvalItem.DANCE, EvalItem.VISUAL, EvalItem.STAR).forEach { item ->
                Text(item.zh, color = Sakura.Text, fontSize = 14.sp)
                Segmented(listOf("1", "2", "3", "4", "5"), (self[item] ?: 3) - 1, { self[item] = it + 1 }, tagPrefix = EvalTags.SELF + item.name)
            }
        }
        val ready = song != null && dance != null && (!monthly || rotating != null)
        PinkButton(stringResource(R.string.ag_eval_send), enabled = ready, modifier = Modifier.testTag(EvalTags.SEND), onClick = {
            val s = song ?: return@PinkButton
            val d = dance ?: return@PinkButton
            if (monthly) {
                val month = java.time.YearMonth.now(app.agency.core.time.AgencyClock.TAIPEI).toString()
                val r = MonthlyResult(
                    month, song = s.id, dance = d.id, rotating = rotating?.id, rotatingKind = rotKinds[rotKind],
                    weightKg = weight.toDoubleOrNull()?.takeIf { it in 30.0..200.0 }, waistCm = waist.toDoubleOrNull()?.takeIf { it in 40.0..200.0 },
                    pushups = pushups.toDoubleOrNull()?.toInt()?.takeIf { it in 0..500 }, plankSec = plank.toDoubleOrNull()?.toInt()?.takeIf { it in 0..3600 },
                )
                agency.monthlyEval(r, listOfNotNull(s, d, rotating), EvalItem.entries.associateWith { self[it] ?: 3 })
            } else {
                agency.weeklyEval(s, d)
            }
            onClose()
        })
        SoftButton(stringResource(R.string.ag_eval_close), onClick = onClose, modifier = Modifier.testTag(EvalTags.CLOSE))
    }
}
