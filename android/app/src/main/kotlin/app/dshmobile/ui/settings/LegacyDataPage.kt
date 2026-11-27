package app.dshmobile.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
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
import app.agency.core.migrate.LegacyMapper
import app.agency.core.model.MediaKind
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.LegacyStatus
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File names of the shared zips (never shown; the share sheet carries them). */
private const val COACH_ZIP = "coach"
private const val CHATS_ZIP = "old-chats"

object LegacyTags {
    const val STATUS = "ag-legacy-status"
    const val RETRY = "ag-legacy-retry"
    const val COUNT = "ag-legacy-count-"
    const val SHARE = "ag-legacy-share-"
    const val FILE = "ag-legacy-file-"
    const val DELETE = "ag-legacy-delete"
    const val SHARE_FOLDER = "ag-legacy-share-folder"
    const val CONFIRM_YES = "ag-legacy-confirm-yes"
    const val CONFIRM_NO = "ag-legacy-confirm-no"
    const val DELETED = "ag-legacy-deleted"
    const val CHATS = "ag-legacy-chats"
    const val CHATS_EXPORT = "ag-legacy-chats-export"
    const val NOVELS_DELETE = "ag-legacy-novels-delete"
    const val NOVELS_CONFIRM_1 = "ag-legacy-novels-confirm-1"
    const val NOVELS_CONFIRM_2 = "ag-legacy-novels-confirm-2"
    const val NOVELS_DELETED = "ag-legacy-novels-deleted"
}

/** 「照片 1」「錄音 2」: a legacy file by its kind and its place among the [all] files of that kind. */
@Composable
internal fun fileLabel(name: String, all: List<String>): String {
    val audio = LegacyMapper.mediaCopy(name, 0).kind == MediaKind.AUDIO
    val same = all.filter { (LegacyMapper.mediaCopy(it, 0).kind == MediaKind.AUDIO) == audio }
    return stringResource(if (audio) R.string.ag_legacy_audio_n else R.string.ag_legacy_photo_n, same.indexOf(name) + 1)
}

/**
 * 設定 › 舊資料: whether the import is done and how many records of each kind it brought, read from the `import-legacy` row
 * alone. While partial: the line he saw once and 「再試一次」. Files too big to move, or still waiting for the vault, are
 * listed by kind and can be shared out (a copy through the share sheet).
 *
 * After a finished import (T09): 刪除舊資料夾 removes the kept `coach.migrated-*` folder after a second confirmation; while a
 * file of it never reached the vault, the whole folder must be shared out first. A partial import's folder is never offered
 * for deletion (its unreadable records may still come). His old dsh conversations and novels can be shared as a zip; the
 * app does not delete them (he has not decided yet).
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
    val deleteFailed = stringResource(R.string.ag_legacy_delete_failed)
    var busy by remember { mutableStateOf(false) }
    var sharedFolder by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var novelStep by remember { mutableIntStateOf(0) }
    var novelsDeleted by remember { mutableStateOf(false) }
    // The onMain wrappers on this page are defensive: on the phone these already run on the main thread; under the Compose
    // test rule they would not. Keep them (AgencyMainThreadTest fails without each).
    LaunchedEffect(reload) { onMain { status = withContext(Dispatchers.IO) { runCatching { agency.legacyStatus() }.getOrNull() } } }
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
        // 再試一次: a partial import runs again now; a failed first import (NOT_YET) too, instead of at the next process start.
        if (s.kind == LegacyStatus.Kind.PARTIAL || s.kind == LegacyStatus.Kind.NOT_YET) {
            PinkButton(
                stringResource(if (retrying) R.string.ag_legacy_retrying else R.string.ag_try_again),
                enabled = !retrying,
                onClick = {
                    retrying = true
                    scope.launch {
                        onMain {
                            if (s.kind == LegacyStatus.Kind.PARTIAL) agency.retryImport() else agency.retryFirstImport()
                            retrying = false
                            reload++
                        }
                    }
                },
                modifier = Modifier.testTag(LegacyTags.RETRY),
            )
        }
        val folder = s.folder
        val lists = listOf(
            R.string.ag_legacy_too_large to s.note?.mediaTooLarge.orEmpty(),
            R.string.ag_legacy_pending to s.note?.mediaPending.orEmpty(),
        )
        if (folder != null) lists.filter { it.second.isNotEmpty() }.forEach { (title, names) ->
            Note(stringResource(title))
            names.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // His file names are Latin (「photo-1795224000000.jpg」): shown by kind and order, the name used only to share.
                    Text(fileLabel(name, names), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f).testTag(LegacyTags.FILE + name))
                    SoftButton(stringResource(R.string.ag_legacy_share), onClick = {
                        scope.launch {
                            onMain {
                                val ok = withContext(Dispatchers.IO) { runCatching { shareCopy(context, File(folder, "${LegacyImporter.BASELINE_DIR}/$name")) }.getOrNull() }
                                if (ok == null) note = shareFailed else context.startActivity(ok)
                            }
                        }
                    }, modifier = Modifier.testTag(LegacyTags.SHARE + name))
                }
            }
        }
        if (s.folder != null && !deleted) Note(stringResource(R.string.ag_legacy_kept))
        if (s.kind == LegacyStatus.Kind.DONE && folder != null && !deleted) {
            val shareFirst = lists.any { it.second.isNotEmpty() }
            Note(stringResource(R.string.ag_legacy_delete_hint))
            if (shareFirst) {
                Note(stringResource(R.string.ag_legacy_share_first))
                SoftButton(stringResource(if (busy) R.string.ag_legacy_preparing else R.string.ag_legacy_share_folder), enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        onMain {
                            val zip = agency.zipForShare(folder, COACH_ZIP)
                            val intent = zip?.let { withContext(Dispatchers.IO) { runCatching { shareCopy(context, it) }.getOrNull() } }
                            busy = false
                            if (intent == null) note = shareFailed else {
                                sharedFolder = true
                                context.startActivity(intent)
                            }
                        }
                    }
                }, modifier = Modifier.testTag(LegacyTags.SHARE_FOLDER))
            }
            if (!confirming) {
                PinkButton(stringResource(R.string.ag_legacy_delete), enabled = !shareFirst || sharedFolder, onClick = { confirming = true }, modifier = Modifier.testTag(LegacyTags.DELETE))
            } else {
                Note(stringResource(R.string.ag_legacy_confirm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftButton(stringResource(R.string.ag_legacy_confirm_no), onClick = { confirming = false }, modifier = Modifier.testTag(LegacyTags.CONFIRM_NO))
                    PinkButton(stringResource(R.string.ag_legacy_confirm_yes), onClick = {
                        scope.launch {
                            onMain {
                                val ok = agency.deleteLegacyFolder(folder)
                                confirming = false
                                if (ok) deleted = true else note = deleteFailed
                            }
                        }
                    }, modifier = Modifier.testTag(LegacyTags.CONFIRM_YES))
                }
            }
        }
        if (deleted) Note(stringResource(R.string.ag_legacy_deleted), Modifier.testTag(LegacyTags.DELETED))
        val chats = remember(reload) { agency.oldChats() }
        if (chats != null) {
            Label(stringResource(R.string.ag_legacy_chats))
            Note(stringResource(R.string.ag_legacy_chats_hint), Modifier.testTag(LegacyTags.CHATS))
            SoftButton(stringResource(if (busy) R.string.ag_legacy_preparing else R.string.ag_legacy_chats_export), enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    onMain {
                        val zip = agency.zipForShare(chats, CHATS_ZIP)
                        val intent = zip?.let { withContext(Dispatchers.IO) { runCatching { shareCopy(context, it) }.getOrNull() } }
                        busy = false
                        if (intent == null) note = shareFailed else context.startActivity(intent)
                    }
                }
            }, modifier = Modifier.testTag(LegacyTags.CHATS_EXPORT))
        }
        // 刪除舊小說 (owner 2026-11-27): the novels only, after two confirmations; the old chats stay.
        val novels = remember(reload) { agency.oldNovels() }
        if (novels != null) {
            when (novelStep) {
                0 -> SoftButton(stringResource(R.string.ag_legacy_novels_delete), onClick = { novelStep = 1 }, modifier = Modifier.testTag(LegacyTags.NOVELS_DELETE))
                1 -> {
                    Note(stringResource(R.string.ag_legacy_novels_confirm_1))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(stringResource(R.string.ag_legacy_confirm_no), onClick = { novelStep = 0 })
                        PinkButton(stringResource(R.string.ag_legacy_novels_next), onClick = { novelStep = 2 }, modifier = Modifier.testTag(LegacyTags.NOVELS_CONFIRM_1))
                    }
                }
                else -> {
                    Note(stringResource(R.string.ag_legacy_novels_confirm_2))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(stringResource(R.string.ag_legacy_confirm_no), onClick = { novelStep = 0 })
                        PinkButton(stringResource(R.string.ag_legacy_novels_yes), onClick = {
                            scope.launch {
                                onMain {
                                    val ok = agency.deleteOldNovels()
                                    novelStep = 0
                                    novelsDeleted = ok
                                    if (!ok) note = deleteFailed
                                    reload++
                                }
                            }
                        }, modifier = Modifier.testTag(LegacyTags.NOVELS_CONFIRM_2))
                    }
                }
            }
        }
        if (novelsDeleted) Note(stringResource(R.string.ag_legacy_novels_deleted), Modifier.testTag(LegacyTags.NOVELS_DELETED))
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
    // A zip made for sharing is in the folder already: copying it onto itself would empty it.
    if (copy.canonicalFile != file.canonicalFile) file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
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
