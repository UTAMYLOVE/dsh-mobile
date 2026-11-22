package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura

/** His line to the company. Sending clears the field; while the company answers, 送出 waits (one message at a time). */
@Composable
fun Composer(busy: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Sakura.White).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (busy) Text(stringResource(R.string.ag_answering), color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, maxLines = 4,
                placeholder = { Text(stringResource(R.string.ag_composer_hint)) },
                modifier = Modifier.weight(1f).testTag(AgencyTags.COMPOSER),
            )
            PinkButton(
                stringResource(R.string.ag_send),
                onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        onSend(t)
                        text = ""
                    }
                },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.testTag(AgencyTags.SEND),
            )
        }
    }
}
