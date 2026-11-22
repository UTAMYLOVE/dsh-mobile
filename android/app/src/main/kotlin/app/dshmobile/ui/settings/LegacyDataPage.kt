package app.dshmobile.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.agency.core.migrate.ImportKind
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.LegacyStatus
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LegacyTags {
    const val STATUS = "ag-legacy-status"
    const val RETRY = "ag-legacy-retry"
    const val COUNT = "ag-legacy-count-"
    const val SHARE = "ag-legacy-share-"
}

/**
 * 設定 › 舊資料, status only (T06): whether the import is done and how many records of each kind it brought, read from the
 * `import-legacy` row alone. While partial: the line he saw once and 「再試一次」. Files too big to move are listed by name
 * and can be shared out (a copy through the share sheet). Nothing here deletes: removing the legacy folder comes in T09, after
 * the share sheet has offered it.
 */
@Composable
fun LegacyDataPage(agency: AgencyApp) {
    var status by remember { mutableStateOf<LegacyStatus?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.ag_legacy_share_failed)
    LaunchedEffect(reload) { status = withContext(Dispatchers.IO) { runCatching { agency.legacyStatus() }.getOrNull() } }
    Section(stringResource(R.string.ag_set_legacy)) {
        val s = status ?: return@Section
        val headline = when (s.kind) {
            LegacyStatus.Kind.DONE -> R.string.ag_legacy_done
            LegacyStatus.Kind.PARTIAL -> R.string.ag_legacy_partial
            LegacyStatus.Kind.NOTHING -> R.string.ag_legacy_nothing
            LegacyStatus.Kind.NOT_YET -> R.string.ag_legacy_not_yet
        }
        Text(stringResource(headline), color = Sakura.Text, fontSize = 16.sp, modifier = Modifier.testTag(LegacyTags.STATUS))
        if (s.counts.isNotEmpty()) {
            Label(stringResource(R.string.ag_legacy_counts))
            ImportKind.entries.filter { (s.counts[it] ?: 0) > 0 }.forEach { k ->
                Note(stringResource(R.string.ag_legacy_count_row, stringResource(kindLabel(k)), s.counts.getValue(k)), Modifier.testTag(LegacyTags.COUNT + k.name))
            }
        }
        if (s.kind == LegacyStatus.Kind.PARTIAL) {
            PinkButton(
                stringResource(if (retrying) R.string.ag_legacy_retrying else R.string.ag_try_again),
                enabled = !retrying,
                onClick = {
                    retrying = true
                    scope.launch {
                        agency.retryImport()
                        retrying = false
                        reload++
                    }
                },
                modifier = Modifier.testTag(LegacyTags.RETRY),
            )
        }
        val big = s.note?.mediaTooLarge.orEmpty()
        val folder = s.folder
        if (big.isNotEmpty() && folder != null) {
            Note(stringResource(R.string.ag_legacy_too_large))
            big.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    SoftButton(stringResource(R.string.ag_legacy_share), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { shareCopy(context, File(folder, "${LegacyImporter.BASELINE_DIR}/$name")) }.getOrNull() }
                            if (ok == null) note = shareFailed else context.startActivity(ok)
                        }
                    }, modifier = Modifier.testTag(LegacyTags.SHARE + name))
                }
            }
        }
        if (s.folder != null) Note(stringResource(R.string.ag_legacy_kept))
        note?.let { Note(it) }
    }
}

/**
 * A share sheet for a copy of [file] in `cache/export/` (the only folder the FileProvider serves); the original stays where it
 * is. Streams the copy, so a file too large for memory still goes.
 */
internal fun shareCopy(context: Context, file: File): Intent {
    require(file.isFile) { "missing" }
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val copy = File(dir, file.name)
    file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", copy)
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun kindLabel(k: ImportKind): Int = when (k) {
    ImportKind.PROFILE -> R.string.ag_kind_profile
    ImportKind.RULES -> R.string.ag_kind_rules
    ImportKind.MEALS -> R.string.ag_kind_meals
    ImportKind.MEAL_ITEMS -> R.string.ag_kind_meal_items
    ImportKind.PENDING -> R.string.ag_kind_pending
    ImportKind.WEIGHTS -> R.string.ag_kind_weights
    ImportKind.TRAININGS -> R.string.ag_kind_trainings
    ImportKind.FOOD -> R.string.ag_kind_food
    ImportKind.AUDIT -> R.string.ag_kind_audit
    ImportKind.BASELINES -> R.string.ag_kind_baselines
    ImportKind.MEDIA -> R.string.ag_kind_media
    ImportKind.MESSAGES -> R.string.ag_kind_messages
}
