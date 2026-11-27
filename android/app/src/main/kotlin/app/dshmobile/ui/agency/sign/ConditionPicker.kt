package app.dshmobile.ui.agency.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.engine.IllKind
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.Segmented

/** Test tags of the sign-in score and the bedtime review (T19). */
object SignTags {
    const val SIGN_IN = "ag-sign-in"
    const val SIGN_OUT = "ag-sign-out"
    const val PICKER = "ag-condition"
    const val SCORE = "ag-condition-score-"
    const val SLEEP = "ag-condition-sleep-"
    const val ENERGY = "ag-condition-energy-"
    const val BODY = "ag-condition-body-"
    const val SEND = "ag-condition-send"
    const val REVIEW = "ag-review"
    const val GOOD = "ag-review-good"
    const val FIX = "ag-review-fix"
    const val REVIEW_SEND = "ag-review-send"
    const val MOOD = "ag-mood-"
}

/** 好 / 普通 / 不好 as the 1–5 the record keeps. */
private val LEVELS = listOf(5, 3, 1)

/**
 * 狀態打分 after the sign-in (T19): the score 1–5, how he slept, his energy, and his body (沒事, 生病, 受傷). Only 生病 or 受傷
 * goes on to the manager's today-only proposal; a low score is just written down (the plan stays).
 */
@Composable
fun ConditionPicker(onSend: (score: Int, sleep: Int?, energy: Int?, ill: IllKind?) -> Unit) {
    var score by rememberSaveable { mutableIntStateOf(-1) }
    var sleep by rememberSaveable { mutableIntStateOf(-1) }
    var energy by rememberSaveable { mutableIntStateOf(-1) }
    var body by rememberSaveable { mutableIntStateOf(0) }
    val levels = listOf(stringResource(R.string.ag_level_good), stringResource(R.string.ag_level_ok), stringResource(R.string.ag_level_bad))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.White, RoundedCornerShape(20.dp)).padding(16.dp)
            .testTag(SignTags.PICKER),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ag_condition_title), color = Sakura.Text, fontSize = 16.sp)
        Segmented((1..5).map { it.toString() }, score, onSelect = { score = it }, tagPrefix = SignTags.SCORE)
        Text(stringResource(R.string.ag_condition_sleep), color = Sakura.TextSecondary, fontSize = 13.sp)
        Segmented(levels, sleep, onSelect = { sleep = it }, tagPrefix = SignTags.SLEEP)
        Text(stringResource(R.string.ag_condition_energy), color = Sakura.TextSecondary, fontSize = 13.sp)
        Segmented(levels, energy, onSelect = { energy = it }, tagPrefix = SignTags.ENERGY)
        Text(stringResource(R.string.ag_condition_body), color = Sakura.TextSecondary, fontSize = 13.sp)
        Segmented(
            listOf(stringResource(R.string.ag_condition_fine), IllKind.SICK.zh, IllKind.INJURY.zh), body, onSelect = { body = it }, tagPrefix = SignTags.BODY,
        )
        PinkButton(stringResource(R.string.ag_condition_send), enabled = score >= 0, onClick = {
            onSend(score + 1, LEVELS.getOrNull(sleep), LEVELS.getOrNull(energy), listOf(null, IllKind.SICK, IllKind.INJURY)[body])
        }, modifier = Modifier.testTag(SignTags.SEND))
    }
}
