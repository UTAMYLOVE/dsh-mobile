package app.dshmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.AreaText
import app.agency.core.model.FavoritePlace
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AreaTags {
    const val FIELD = "ag-area-field"
    const val SAVE = "ag-area-save"
    const val NOTE = "ag-area-note"
    const val PLACE = "ag-place-"
    const val DELETE = "ag-place-delete-"
}

/**
 * 設定 › 附近搜尋 (T16): the coarse area the search uses (a district; never the phone's location, no house number), and his
 * 常去店家 (saved only after his 好 on a card), each one deletable.
 */
@Composable
fun AreaPage(ready: StartState.Ready) {
    val scope = rememberCoroutineScope()
    var changed by remember { mutableIntStateOf(0) }
    val area by produceState<String?>(null, changed) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.store.profile.get()?.areaText }.getOrNull() } }
    }
    val places by produceState(emptyList<FavoritePlace>(), changed) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.favorites() }.getOrDefault(emptyList()) } }
    }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.ag_area_saved)
    Section(stringResource(R.string.ag_set_area)) {
        Note(stringResource(R.string.ag_area_intro))
        Field(stringResource(R.string.ag_area_field), text ?: area.orEmpty(), AreaTags.FIELD) { text = it.take(AreaText.MAX + 5) }
        if (area == null && text == null) Note(stringResource(R.string.ag_area_none))
        note?.let { Text(it, color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.testTag(AreaTags.NOTE)) }
        PinkButton(
            stringResource(R.string.ag_save),
            onClick = {
                val t = text ?: area.orEmpty()
                scope.launch {
                    onMain {
                        val problem = withContext(Dispatchers.IO) { runCatching { ready.brain.setArea(t) }.getOrElse { "沒有存到，再試一次。" } }
                        note = problem ?: saved
                        if (problem == null) { text = null; changed++ }
                    }
                }
            },
            modifier = Modifier.testTag(AreaTags.SAVE),
        )
    }
    Section(stringResource(R.string.ag_favorites)) {
        if (places.isEmpty()) Note(stringResource(R.string.ag_favorites_none))
        places.forEach { p ->
            Row(Modifier.fillMaxWidth().testTag(AreaTags.PLACE + p.id), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title + (p.note?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""), color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                SoftButton(
                    stringResource(R.string.ag_favorite_delete),
                    onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.deleteFavorite(p.id) } }; onMain { changed++ } } },
                    modifier = Modifier.testTag(AreaTags.DELETE + p.id),
                )
            }
        }
    }
}
