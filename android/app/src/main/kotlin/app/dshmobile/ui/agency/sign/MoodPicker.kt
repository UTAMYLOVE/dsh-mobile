package app.dshmobile.ui.agency.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Sakura

/** 心情 after the sign-out (rollout week 3): one tap, 低落 / 普通 / 不錯. */
@Composable
fun MoodPicker(onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Sakura.White, RoundedCornerShape(20.dp)).padding(12.dp)
            .testTag(SignTags.MOOD),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.ag_mood_title), color = Sakura.Text, fontSize = 15.sp)
        listOf(R.string.ag_mood_low to 1, R.string.ag_mood_ok to 2, R.string.ag_mood_good to 3).forEach { (label, level) ->
            Chip(stringResource(label), onClick = { onPick(level) }, modifier = Modifier.testTag(SignTags.MOOD + level))
        }
    }
}
