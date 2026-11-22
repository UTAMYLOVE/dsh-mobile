package app.dshmobile.ui.agency.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.SearchResult
import app.dshmobile.R
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Sakura

object ResultTags {
    const val CARD = "ag-result-"
    const val OPEN = "ag-result-open-"
    const val SAVE = "ag-result-save-"
}

/**
 * One nearby result (T16), built by code from the stored search result: its title and summary as the search gave them (the
 * link stays in code: 打開 hands it to the browser), the 估算 note, and 存成常去店家 (a proposal; saved only on his 好).
 */
@Composable
fun SearchResultCard(r: SearchResult, onOpen: (String) -> Unit, onSave: (String) -> Unit, busy: Boolean) {
    Column(
        Modifier.padding(start = 6.dp, top = 4.dp).widthIn(max = 300.dp).background(Sakura.Background, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag(ResultTags.CARD + r.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(r.title, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        r.summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
        Text(stringResource(R.string.ag_result_estimate), color = Sakura.TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.ag_result_open), onClick = { onOpen(r.url) }, modifier = Modifier.testTag(ResultTags.OPEN + r.id))
            Chip(stringResource(R.string.ag_result_save), onClick = { if (!busy) onSave(r.id) }, modifier = Modifier.testTag(ResultTags.SAVE + r.id))
        }
    }
}
