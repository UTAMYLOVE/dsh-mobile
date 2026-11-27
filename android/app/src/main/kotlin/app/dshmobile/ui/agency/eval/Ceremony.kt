package app.dshmobile.ui.agency.eval

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.engine.Levels
import app.agency.core.model.LessonCategory
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura

/** The 升級典禮 screen (T19e): the subject and its new level on a pink stage, and the level card he takes. */
@Composable
fun CeremonyScreen(subject: LessonCategory, level: Int, onOk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sakura.Background, Sakura.White))).padding(24.dp).testTag(CEREMONY),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✦ ✿ ✦", color = Sakura.RoseBrown, fontSize = 28.sp)
        Text(stringResource(R.string.ag_ceremony_title), color = Sakura.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.background(Sakura.White, RoundedCornerShape(24.dp)).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ag_ceremony_line, subject.zh, Levels.name(level)), color = Sakura.Text, fontSize = 22.sp)
        }
        Text(stringResource(R.string.ag_ceremony_sub), color = Sakura.TextSecondary, fontSize = 15.sp)
        PinkButton(stringResource(R.string.ag_ceremony_ok), onClick = onOk, modifier = Modifier.testTag(CEREMONY_OK))
    }
}

const val CEREMONY = "ag-ceremony"
const val CEREMONY_OK = "ag-ceremony-ok"
