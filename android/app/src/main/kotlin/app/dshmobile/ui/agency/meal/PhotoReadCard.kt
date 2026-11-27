package app.dshmobile.ui.agency.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.PhotoRead
import app.agency.core.model.PhotoReadKind
import app.agency.core.model.PhotoReadStatus
import app.agency.core.vision.ReadDraft
import app.agency.core.vision.ReadRow
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton

object ReadTags {
    const val CARD = "ag-read-"
    const val KEEP = "ag-read-keep-"
    const val NAME = "ag-read-name-"
    const val NUMBER = "ag-read-number-"
    const val CONFIRM = "ag-read-confirm-"
    const val DROP = "ag-read-drop-"
    const val DONE = "ag-read-done-"
}

/** One editable row: kept or not, its name, its number (大卡, or 元 on a receipt), 娛樂費 on a receipt. */
private class RowState(val row: ReadRow) {
    var keep = androidx.compose.runtime.mutableStateOf(true)
    var name = androidx.compose.runtime.mutableStateOf(row.name)
    var number = androidx.compose.runtime.mutableStateOf((row.amount ?: row.kcal)?.toString().orEmpty())
    var entertainment = androidx.compose.runtime.mutableStateOf(row.entertainment)
}

/**
 * Under the body teacher's line about a photo she read (T15b): the rows to keep or fix, then 確認 (only now do they count) or
 * 不要. Once decided it says so.
 */
@Composable
fun PhotoReadCard(read: PhotoRead, actions: MealActions, busy: Boolean) {
    val draft = remember(read.id, read.json) { ReadDraft.of(read.json) } ?: return
    Column(Modifier.padding(start = 6.dp, top = 4.dp).fillMaxWidth().testTag(ReadTags.CARD + read.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (read.status != PhotoReadStatus.WAITING) {
            Text(
                stringResource(if (read.status == PhotoReadStatus.CONFIRMED) R.string.ag_read_confirmed else R.string.ag_read_dropped),
                color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.testTag(ReadTags.DONE + read.id),
            )
            return@Column
        }
        val receipt = read.kind == PhotoReadKind.RECEIPT
        val rows = remember(read.id) { mutableStateListOf<RowState>().apply { draft.rows.forEach { add(RowState(it)) } } }
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = r.keep.value, onCheckedChange = { r.keep.value = it }, modifier = Modifier.testTag(ReadTags.KEEP + i))
                OutlinedTextField(r.name.value, { r.name.value = it.take(30) }, singleLine = true, modifier = Modifier.weight(1f).testTag(ReadTags.NAME + i))
                OutlinedTextField(
                    r.number.value, { v -> r.number.value = v.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(stringResource(if (receipt) R.string.ag_read_yuan else R.string.ag_read_kcal), fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp).testTag(ReadTags.NUMBER + i),
                )
            }
            if (receipt) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = r.entertainment.value, onCheckedChange = { r.entertainment.value = it })
                    Text(stringResource(R.string.ag_read_fun), color = Sakura.TextSecondary, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinkButton(stringResource(R.string.ag_read_confirm), enabled = !busy, onClick = {
                val kept = rows.filter { it.keep.value && it.name.value.isNotBlank() }.map { s ->
                    val n = s.number.value.toIntOrNull()
                    if (receipt) s.row.copy(name = s.name.value.trim(), amount = n, entertainment = s.entertainment.value)
                    else s.row.copy(name = s.name.value.trim(), kcal = n, printed = s.row.printed && n == s.row.kcal)
                }
                actions.onConfirmRead(read.id, draft.copy(rows = kept))
            }, modifier = Modifier.testTag(ReadTags.CONFIRM + read.id))
            SoftButton(stringResource(R.string.ag_read_drop), enabled = !busy, onClick = { actions.onDropRead(read.id) }, modifier = Modifier.testTag(ReadTags.DROP + read.id))
        }
    }
}
