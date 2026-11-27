package app.dshmobile.ui.agency.eval

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onboarding.Recorder
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.Segmented
import app.dshmobile.ui.kit.SoftButton

const val SINGING = "ag-singing"
const val SINGING_CONSENT = "ag-singing-consent"

/**
 * 唱歌分析 (T19d): record a take; the phone draws its pitch curve and computes 音準, 音域, 氣長 and 音量; with an imported
 * reference (a karaoke song file or a MIDI melody, or one of his own takes as the 示範) it compares by code. With lyrics and
 * his one-time yes the take goes to speech to text for 咬字; otherwise no recording ever leaves the phone.
 */
@Composable
fun SingingScreen(agency: AgencyApp, onClose: () -> Unit) {
    val ref by agency.singingRef.collectOnMain()
    val view by agency.singing.collectOnMain()
    var mode by remember { mutableIntStateOf(0) }
    var demo by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(agency.settings.dictionConsent) }
    var note by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) agency.importReference(uri) { ok -> note = if (ok) R.string.ag_sing_ref_ok else R.string.ag_sing_ref_bad }
    }
    Column(
        Modifier.fillMaxSize().background(Sakura.Background).verticalScroll(rememberScrollState()).padding(16.dp).testTag(SINGING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.ag_sing_title), color = Sakura.Text, fontSize = 22.sp)
        Text(ref?.let { stringResource(R.string.ag_sing_ref_has, it.size) } ?: stringResource(R.string.ag_sing_ref_none), color = Sakura.TextSecondary, fontSize = 14.sp)
        note?.let { Text(stringResource(it), color = Sakura.RoseBrown, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(stringResource(R.string.ag_sing_import), onClick = { runCatching { pick.launch(arrayOf("*/*")) } })
            if (ref != null) SoftButton(stringResource(R.string.ag_sing_clear), onClick = agency::clearReference)
        }
        Segmented(listOf(stringResource(R.string.ag_sing_a_cappella), stringResource(R.string.ag_sing_backing), stringResource(R.string.ag_sing_as_demo)), mode, { mode = it; demo = it == 2 })
        OutlinedTextField(
            value = lyrics, onValueChange = { lyrics = it.take(500) }, label = { Text(stringResource(R.string.ag_sing_lyrics)) }, modifier = Modifier.fillMaxWidth(),
        )
        if (lyrics.isNotBlank() && !consent) {
            Column(Modifier.background(Sakura.White, RoundedCornerShape(16.dp)).padding(12.dp).testTag(SINGING_CONSENT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ag_sing_consent), color = Sakura.Text, fontSize = 14.sp)
                PinkButton(stringResource(R.string.ag_sing_consent_yes), onClick = { agency.settings.dictionConsent = true; consent = true })
            }
        }
        Recorder(agency, handIn = { file, _ -> agency.handInSinging(file, if (consent) lyrics else "", aCappella = mode != 1, asDemo = demo) })
        view?.let { v ->
            remember(v) { BitmapFactory.decodeByteArray(v.chart, 0, v.chart.size)?.asImageBitmap() }?.let {
                Image(it, contentDescription = stringResource(R.string.ag_sing_chart), modifier = Modifier.fillMaxWidth())
            }
            v.lines.forEach { Text(it, color = Sakura.Text, fontSize = 14.sp) }
        }
        SoftButton(stringResource(R.string.ag_eval_close), onClick = onClose)
    }
}
