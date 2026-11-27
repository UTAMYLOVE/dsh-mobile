package app.dshmobile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.agency.core.backup.BackupCipher
import app.agency.core.backup.BackupException
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.backup.BackupSummary
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.launch

object BackupTags {
    const val PASSWORD = "ag-bk-password"
    const val AGAIN = "ag-bk-again"
    const val EXPORT = "ag-bk-export"
    const val PICK = "ag-bk-pick"
    const val OPEN_PASSWORD = "ag-bk-open-password"
    const val CHECK = "ag-bk-check"
    const val SUMMARY = "ag-bk-summary"
    const val RESTORE = "ag-bk-restore"
    const val RESTORE_YES = "ag-bk-restore-yes"
    const val NOTE = "ag-bk-note"
}

private const val MIME_BACKUP = "application/octet-stream"
private const val MIME_ANY = "*/*"
private const val WRITE = "wt"
private const val STAMP = "yyyyMMdd"
private const val T_MESSAGE = "message"
private const val T_MEAL = "meal"
private const val T_LEDGER = "ledger_entry"
private const val T_MEMO = "memo"

/** The rows of a backup in his words (the tables he knows). */
@Composable
private fun describe(s: BackupSummary): String {
    val z = java.time.Instant.ofEpochMilli(s.createdAt).atZone(java.time.ZoneId.systemDefault())
    fun n(t: String) = s.rows[t] ?: 0
    return stringResource(R.string.ag_bk_summary, z.year, z.monthValue, z.dayOfMonth, n(T_MESSAGE), n(T_MEAL), n(T_LEDGER), n(T_MEMO), s.media)
}

/**
 * 設定 › 加密備份 (owner 2026-11-23): 匯出 seals every record and photo with his password (at least eight characters, typed twice)
 * into one file he keeps where he likes; 匯回 checks the file with the password, shows what it holds, and replaces everything
 * only after two taps. The API keys are never in the file: on a new phone he types them again in 模型與金鑰.
 */
@Composable
fun BackupPage(agency: AgencyApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var openPw by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    var summary by remember { mutableStateOf<BackupSummary?>(null) }
    var sure by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val weak = stringResource(R.string.ag_bk_weak)
    val mismatch = stringResource(R.string.ag_bk_mismatch)
    val exported = stringResource(R.string.ag_bk_exported)
    val failed = stringResource(R.string.ag_bk_failed)
    val wrong = stringResource(R.string.ag_bk_wrong)
    val other = stringResource(R.string.ag_bk_other)
    val restored = stringResource(R.string.ag_bk_restored)

    fun why(e: Throwable?): String = when ((e as? BackupException)?.reason) {
        BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, BackupException.Reason.TRUNCATED -> wrong
        BackupException.Reason.NOT_A_BACKUP, BackupException.Reason.NEWER_VERSION -> other
        else -> failed
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_BACKUP)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val password = pw.toCharArray()
        scope.launch {
            onMain {
                val r = agency.exportBackup({ context.contentResolver.openOutputStream(uri, WRITE) }, password)
                note = if (r.isSuccess) exported else failed
                if (r.isSuccess) { pw = ""; again = "" }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        file = uri
        summary = null
        sure = false
    }

    Section(stringResource(R.string.ag_bk_export_title)) {
        Note(stringResource(R.string.ag_bk_intro))
        Field(stringResource(R.string.ag_bk_password), pw, BackupTags.PASSWORD, secret = true) { pw = it.take(128) }
        Field(stringResource(R.string.ag_bk_again), again, BackupTags.AGAIN, secret = true) { again = it.take(128) }
        PinkButton(stringResource(R.string.ag_bk_export), onClick = {
            note = when {
                pw.length < BackupCipher.MIN_PASSWORD -> weak
                pw != again -> mismatch
                else -> null.also {
                    val stamp = java.text.SimpleDateFormat(STAMP, java.util.Locale.ROOT).format(java.util.Date())
                    runCatching { create.launch(context.getString(R.string.ag_bk_file_name, stamp)) }
                }
            }
        }, modifier = Modifier.testTag(BackupTags.EXPORT))
    }
    Section(stringResource(R.string.ag_bk_import_title)) {
        Note(stringResource(R.string.ag_bk_import_intro))
        SoftButton(stringResource(R.string.ag_bk_pick), onClick = { runCatching { open.launch(arrayOf(MIME_BACKUP, MIME_ANY)) } }, modifier = Modifier.testTag(BackupTags.PICK))
        val f = file
        if (f != null) {
            Field(stringResource(R.string.ag_bk_password), openPw, BackupTags.OPEN_PASSWORD, secret = true) { openPw = it.take(128) }
            SoftButton(stringResource(R.string.ag_bk_check), onClick = {
                val password = openPw.toCharArray()
                scope.launch {
                    onMain {
                        val r = agency.backupSummary({ context.contentResolver.openInputStream(f) }, password)
                        summary = r.getOrNull()
                        note = if (r.isSuccess) null else why(r.exceptionOrNull())
                    }
                }
            }, modifier = Modifier.testTag(BackupTags.CHECK))
        }
        summary?.let { s ->
            Text(describe(s), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(BackupTags.SUMMARY))
            if (!sure) {
                SoftButton(stringResource(R.string.ag_bk_restore), onClick = { sure = true }, modifier = Modifier.testTag(BackupTags.RESTORE))
            } else {
                Note(stringResource(R.string.ag_bk_restore_sure))
                PinkButton(stringResource(R.string.ag_bk_restore_yes), onClick = {
                    val password = openPw.toCharArray()
                    val uri = file ?: return@PinkButton
                    scope.launch {
                        onMain {
                            val r = agency.restoreBackup({ context.contentResolver.openInputStream(uri) }, password)
                            note = if (r.isSuccess) restored else why(r.exceptionOrNull())
                            sure = false
                        }
                    }
                }, modifier = Modifier.testTag(BackupTags.RESTORE_YES))
            }
        }
        note?.let { Text(it, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.testTag(BackupTags.NOTE)) }
    }
}
