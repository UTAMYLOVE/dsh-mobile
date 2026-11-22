package app.dshmobile.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.agency.core.engine.IdolCardFacts
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.IdolSetup
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.MemberColour
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 設定 › 練習生資料 (arch T10): 藝名 and 代表色, whenever he wants (the card's 「先不要」 does not hide them here). */
@Composable
fun TraineePage(agency: AgencyApp, ready: StartState.Ready) {
    var saved by remember { mutableIntStateOf(0) }
    val facts by produceState<IdolCardFacts?>(null, saved) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.card() }.getOrNull() } }
    }
    val f = facts ?: return
    Section(stringResource(R.string.ag_set_trainee)) {
        Text(
            stringResource(R.string.ag_trainee_now, f.stageName ?: stringResource(R.string.ag_card_no_name), colourName(f.memberColor)),
            color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(TRAINEE_NOW),
        )
        if (MemberColour.parse(f.memberColor)?.let { MemberColour.accentOf(it).adjusted } == true) Note(stringResource(R.string.ag_trainee_adjusted))
        IdolSetup(agency, f, onSaved = { saved++ }, onSkip = null)
    }
}

const val TRAINEE_NOW = "ag-trainee-now"

@Composable
private fun colourName(hex: String?): String =
    MemberColour.PRESETS.firstOrNull { it.second.equals(hex, ignoreCase = true) }?.first ?: stringResource(R.string.ag_trainee_no_colour)
