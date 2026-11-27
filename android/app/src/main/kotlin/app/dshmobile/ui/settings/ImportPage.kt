package app.dshmobile.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.importing.ClaudeExport
import app.agency.core.importing.ExportConversation
import app.agency.core.importing.ImportPlan
import app.agency.core.importing.Importer
import app.agency.core.importing.LedgerLine
import app.agency.core.importing.Mapping
import app.agency.core.importing.Planned
import app.agency.core.importing.Table
import app.agency.core.importing.Tables
import app.agency.core.model.ImportBatch
import app.agency.core.model.ImportTarget
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImportTags {
    const val TARGET = "ag-imp-target-"
    const val PASTE = "ag-imp-paste"
    const val READ_PASTE = "ag-imp-read-paste"
    const val FILE = "ag-imp-file"
    const val FIELD = "ag-imp-field-"
    const val SUMMARY = "ag-imp-summary"
    const val ROW = "ag-imp-row-"
    const val FUN = "ag-imp-fun-"
    const val GO = "ag-imp-go"
    const val DONE = "ag-imp-done"
    const val UNDO = "ag-imp-undo-"
    const val CONV = "ag-imp-conv-"
    const val READ_CONV = "ag-imp-read-conv"
    const val UNREAD = "ag-imp-unread"
    const val ASK_MODEL = "ag-imp-ask-model"
    const val NOTE = "ag-imp-note"
}

private const val MIME_TEXT = "text/*"
private const val MIME_JSON = "application/json"
private const val MIME_ZIP = "application/zip"
private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_ANY = "application/octet-stream"
private const val EXT_ZIP = ".zip"
private const val EXT_JSON = ".json"
private const val EXT_XLSX = ".xlsx"

/** The columns each target needs, in the mapper's order. */
private enum class Field(val label: Int) { DATE(R.string.ag_imp_f_date), AMOUNT(R.string.ag_imp_f_amount), ITEM(R.string.ag_imp_f_item), CATEGORY(R.string.ag_imp_f_category), KCAL(R.string.ag_imp_f_kcal), WEIGHT(R.string.ag_imp_f_weight), TEXT(R.string.ag_imp_f_text) }

private fun fields(t: ImportTarget) = when (t) {
    ImportTarget.LEDGER -> listOf(Field.DATE, Field.AMOUNT, Field.ITEM, Field.CATEGORY)
    ImportTarget.MEALS -> listOf(Field.DATE, Field.ITEM, Field.KCAL)
    ImportTarget.WEIGHTS -> listOf(Field.DATE, Field.WEIGHT)
    ImportTarget.MEMOS -> listOf(Field.TEXT, Field.DATE)
}

private fun Mapping.get(f: Field): Int? = when (f) {
    Field.DATE -> date; Field.AMOUNT -> amount; Field.ITEM -> item; Field.CATEGORY -> category; Field.KCAL -> kcal; Field.WEIGHT -> weight; Field.TEXT -> text
}

private fun Mapping.with(f: Field, c: Int?): Mapping = when (f) {
    Field.DATE -> copy(date = c); Field.AMOUNT -> copy(amount = c); Field.ITEM -> copy(item = c); Field.CATEGORY -> copy(category = c)
    Field.KCAL -> copy(kcal = c); Field.WEIGHT -> copy(weight = c); Field.TEXT -> copy(text = c)
}

/**
 * 設定 › 匯入資料 (T15c): 記帳, 餐點, 體重 or 備忘錄 from a file (CSV, XLSX, JSON, text; picked with the system file picker, no
 * permission), from text he pastes (a table Claude made for him), or from his claude.ai data export (a zip with
 * conversations.json: he picks conversations, the phone reads his lines, and only the lines it cannot read may go to the model,
 * after his tap). Everything is read on the phone; he checks the columns, sees what would be written (duplicates skipped) and
 * nothing is saved until 「匯入」; each import has one 復原.
 */
@Composable
fun ImportPage(ready: StartState.Ready) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(ImportTarget.LEDGER) }
    var paste by remember { mutableStateOf("") }
    var table by remember { mutableStateOf<Table?>(null) }
    var source by remember { mutableStateOf(Importer.PASTE) }
    var mapping by remember { mutableStateOf<Mapping?>(null) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var conversations by remember { mutableStateOf<List<ExportConversation>?>(null) }
    val chosen = remember { mutableStateOf(setOf<String>()) }
    var lines by remember { mutableStateOf<List<LedgerLine>>(emptyList()) }
    var changed by remember { mutableIntStateOf(0) }
    val history by produceState(emptyList<ImportBatch>(), changed) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.imports() }.getOrDefault(emptyList()) } }
    }
    val unreadable = stringResource(R.string.ag_imp_unreadable)
    val tooBig = stringResource(R.string.ag_imp_too_big)

    fun load(t: Table, key: String) {
        table = t
        source = key
        conversations = null
        scope.launch { onMain { mapping = withContext(Dispatchers.IO) { runCatching { ready.brain.importMapping(target, t, key) }.getOrNull() } } }
    }

    LaunchedEffect(table, mapping, target) {
        val t = table
        val m = mapping
        plan = if (t == null || m == null) null else withContext(Dispatchers.IO) { runCatching { ready.brain.planImport(target, t, m, source) }.getOrNull() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            onMain {
                val read = withContext(Dispatchers.IO) { runCatching { readFile(context, uri) }.getOrNull() }
                if (read == null) { note = tooBig; return@onMain }
                val (name, bytes) = read
                val export = withContext(Dispatchers.Default) { if (name.endsWith(EXT_ZIP, true) || name.endsWith(EXT_JSON, true)) ClaudeExport.conversations(bytes, java.time.ZoneId.systemDefault()) else null }
                when {
                    export != null && export.isNotEmpty() -> { conversations = export; table = null; mapping = null; source = Importer.CLAUDE; note = null }
                    name.endsWith(EXT_XLSX, true) -> withContext(Dispatchers.Default) { Tables.xlsx(bytes) }?.let { load(it, name); note = null } ?: run { note = unreadable }
                    else -> load(withContext(Dispatchers.Default) { Tables.text(String(bytes, Charsets.UTF_8)) }, name).also { note = null }
                }
            }
        }
    }

    Section(stringResource(R.string.ag_set_import)) {
        Note(stringResource(R.string.ag_imp_intro))
        Label(stringResource(R.string.ag_imp_target))
        ImportTarget.entries.forEach { t ->
            Choice(t.zh, target == t, ImportTags.TARGET + t.name) { target = t; table?.let { load(it, source) } }
        }
        SoftButton(stringResource(R.string.ag_imp_file), onClick = {
            runCatching { picker.launch(arrayOf(MIME_TEXT, MIME_JSON, MIME_ZIP, MIME_XLSX, MIME_ANY)) }
        }, modifier = Modifier.testTag(ImportTags.FILE))
        Note(stringResource(R.string.ag_imp_paste_howto))
        OutlinedTextField(paste, { paste = it.take(200_000) }, label = { Text(stringResource(R.string.ag_imp_paste)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag(ImportTags.PASTE))
        SoftButton(stringResource(R.string.ag_imp_read_paste), onClick = { load(Tables.text(paste), Importer.PASTE) }, modifier = Modifier.testTag(ImportTags.READ_PASTE))
        note?.let { Text(it, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.testTag(ImportTags.NOTE)) }
    }

    conversations?.let { list ->
        Section(stringResource(R.string.ag_imp_conversations)) {
            list.take(200).forEach { c ->
                Row(Modifier.fillMaxWidth().testTag(ImportTags.CONV + c.id), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.id in chosen.value, onCheckedChange = { on -> chosen.value = if (on) chosen.value + c.id else chosen.value - c.id })
                    Text(c.name + "（" + c.lines.size + "）", color = Sakura.Text, fontSize = 14.sp)
                }
            }
            SoftButton(stringResource(R.string.ag_imp_read_conv), enabled = chosen.value.isNotEmpty(), onClick = {
                lines = ClaudeExport.lines(list.filter { it.id in chosen.value }, java.time.LocalDate.now())
                target = ImportTarget.LEDGER
                load(ClaudeExport.table(lines), Importer.CLAUDE)
                conversations = list
            }, modifier = Modifier.testTag(ImportTags.READ_CONV))
            val unread = lines.filter { it.amount == null }
            if (unread.isNotEmpty()) {
                Text(stringResource(R.string.ag_imp_unread, unread.size), color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.testTag(ImportTags.UNREAD))
                unread.take(5).forEach { Note("・" + it.raw.take(40)) }
                SoftButton(stringResource(R.string.ag_imp_ask_model, minOf(unread.size, app.agency.core.importing.LineReader.MAX_LINES)), onClick = {
                    scope.launch {
                        onMain {
                            val got = withContext(Dispatchers.IO) { runCatching { ready.brain.readLedgerLines(unread) }.getOrNull() }
                            if (got == null) { note = unreadable; return@onMain }
                            lines = lines.filter { it.amount != null } + got
                            load(ClaudeExport.table(lines), Importer.CLAUDE)
                            conversations = list
                        }
                    }
                }, modifier = Modifier.testTag(ImportTags.ASK_MODEL))
            }
        }
    }

    val t = table
    val m = mapping
    if (t != null && m != null) {
        Section(stringResource(R.string.ag_imp_columns)) {
            fields(target).forEach { f ->
                val col = m.get(f)
                val name = col?.let { t.headers.getOrNull(it) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(f.label) + "：" + (if (col == null) stringResource(R.string.ag_imp_none) else stringResource(R.string.ag_imp_col, col + 1, name.orEmpty())),
                        color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    // 換一欄: the next column, then none, then the first again.
                    SoftButton(stringResource(R.string.ag_imp_next_col), onClick = {
                        val next = when {
                            col == null -> 0
                            col + 1 < t.headers.size -> col + 1
                            else -> null
                        }
                        mapping = m.with(f, next)
                    }, modifier = Modifier.testTag(ImportTags.FIELD + f.name))
                }
            }
            Label(stringResource(R.string.ag_imp_preview))
            t.rows.take(5).forEach { r -> Note(r.joinToString("｜")) }
        }
        plan?.let { p ->
            Section(stringResource(R.string.ag_imp_plan)) {
                Text(stringResource(R.string.ag_imp_counts, p.fresh.size, p.duplicates, p.bad), color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(ImportTags.SUMMARY))
                p.rows.filter { it.status != Planned.Status.DUPLICATE }.take(12).forEach { r -> Note(describe(r), Modifier.testTag(ImportTags.ROW + r.line)) }
                if (target == ImportTarget.LEDGER && p.categories.isNotEmpty()) {
                    Label(stringResource(R.string.ag_imp_buckets))
                    p.categories.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ImportTags.FUN + c)) {
                            Checkbox(checked = c in m.funCategories, onCheckedChange = { on -> mapping = m.copy(funCategories = if (on) m.funCategories + c else m.funCategories - c) })
                            Text(stringResource(R.string.ag_imp_fun, c), color = Sakura.Text, fontSize = 14.sp)
                        }
                    }
                }
                val done = stringResource(R.string.ag_imp_done)
                PinkButton(stringResource(R.string.ag_imp_go, p.fresh.size), enabled = p.fresh.isNotEmpty(), onClick = {
                    scope.launch {
                        onMain {
                            val b = withContext(Dispatchers.IO) { runCatching { ready.brain.commitImport(p) }.getOrNull() }
                            note = b?.let { done.format(it.ids.size) } ?: unreadable
                            if (b != null) { table = null; mapping = null; plan = null; paste = ""; changed++ }
                        }
                    }
                }, modifier = Modifier.testTag(ImportTags.GO))
            }
        }
    }

    Section(stringResource(R.string.ag_imp_history)) {
        if (history.isEmpty()) Note(stringResource(R.string.ag_imp_history_none))
        history.take(8).forEach { b ->
            Column(Modifier.fillMaxWidth().testTag(ImportTags.DONE + b.id), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val when_ = java.time.Instant.ofEpochMilli(b.at).atZone(java.time.ZoneId.systemDefault())
                Text(
                    stringResource(R.string.ag_imp_batch, when_.monthValue, when_.dayOfMonth, b.target.zh, b.ids.size, b.skipped) +
                        (if (b.undoneAt != null) stringResource(R.string.ag_imp_undone) else ""),
                    color = Sakura.Text, fontSize = 14.sp,
                )
                if (b.undoneAt == null) {
                    SoftButton(stringResource(R.string.ag_imp_undo), onClick = {
                        scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.undoImport(b.id) } }; onMain { changed++ } }
                    }, modifier = Modifier.testTag(ImportTags.UNDO + b.id))
                }
            }
        }
    }
}

@Composable
private fun describe(r: Planned): String = when (r.status) {
    Planned.Status.BAD -> stringResource(R.string.ag_imp_bad, r.line, r.reason.orEmpty())
    else -> listOfNotNull(r.day, r.amount?.let { "$it 元" }, r.kg?.let { "$it 公斤" }, r.kcal?.let { "$it 大卡" }, r.item, r.category).joinToString("、")
}

/** The picked file's name and bytes (at most 20 MB). */
private fun readFile(context: Context, uri: Uri): Pair<String, ByteArray>? {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "檔案"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 20 * 1024 * 1024) return null
        }
        out.toByteArray()
    } ?: return null
    return name to bytes
}
