package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.Icons
import app.dshmobile.ui.kit.LineIcon
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.Type

/**
 * His line to the company (redesign 01/02): a rounded field 「跟小葵姐說…」 and the round 送出 button. Sending clears the field;
 * while the company answers, 送出 waits (one message at a time). [text] is hoisted so a quick chip can start a line (「我想學」).
 */
@Composable
fun Composer(busy: Boolean, onSend: (String) -> Unit, text: String? = null, onText: ((String) -> Unit)? = null) {
    var own by rememberSaveable { mutableStateOf("") }
    val value = text ?: own
    val set: (String) -> Unit = onText ?: { own = it }
    val accent = LocalAccent.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().background(Sakura.Page).padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        if (busy) Text(stringResource(R.string.ag_answering), style = Type.Caption, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value, onValueChange = set, maxLines = 4,
                placeholder = { Text(stringResource(R.string.ag_composer_hint), color = Sakura.TextSecondary) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Sakura.White, unfocusedContainerColor = Sakura.White,
                    focusedBorderColor = accent.fill, unfocusedBorderColor = Sakura.MilkTea,
                    focusedTextColor = Sakura.Text, unfocusedTextColor = Sakura.Text, cursorColor = accent.fill,
                ),
                textStyle = Type.Body,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(AgencyTags.COMPOSER),
            )
            val send = stringResource(R.string.ag_send)
            val enabled = !busy && value.isNotBlank()
            IconButton(
                onClick = {
                    val t = value.trim()
                    if (t.isNotEmpty()) {
                        onSend(t)
                        set("")
                        // The keyboard goes once a line is sent: the 今天 card and the conversation get their room back.
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                },
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(containerColor = accent.fill, disabledContainerColor = accent.fill.copy(alpha = .35f)),
                modifier = Modifier.size(48.dp).semantics { contentDescription = send }.testTag(AgencyTags.SEND),
            ) {
                Box(Modifier.size(48.dp).background(accent.fill.copy(alpha = if (enabled) 1f else .35f), CircleShape), contentAlignment = Alignment.Center) {
                    LineIcon(Icons.Send, tint = accent.onFill)
                }
            }
        }
    }
}
