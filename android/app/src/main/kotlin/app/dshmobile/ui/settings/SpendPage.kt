package app.dshmobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.dshmobile.R
import app.dshmobile.agency.metering.CostMeter
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.SoftButton
import java.util.Locale

object SpendTags {
    const val TODAY = "ag-spend-today"
    const val ALERT = "ag-spend-alert"
    const val CAP = "ag-spend-cap"
    const val SAVE = "ag-spend-save"
    const val SUGGEST = "ag-spend-suggest"
    const val NOTE = "ag-spend-note"
}

/**
 * 設定 › 花費上限: today's and this month's spend, the alert amount and the daily cap (CostMeter, prefs `dshm_cost`, unchanged).
 * With no cap ever set, 1 US dollar a day is suggested, not forced (Q7).
 */
@Composable
fun SpendPage(meter: CostMeter) {
    val revision by meter.revision.collectOnMain()
    val today = remember(revision) { meter.todayUsd() }
    val month = remember(revision) { meter.monthUsd() }
    val cap = remember(revision) { meter.hardCapUsd }
    var alert by remember(revision) { mutableStateOf(usd(meter.alertUsd)) }
    var capText by remember(revision) { mutableStateOf(cap?.let(::usd).orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }
    val bad = stringResource(R.string.ag_spend_bad)
    val saved = stringResource(R.string.ag_saved)
    Section(stringResource(R.string.ag_set_spend)) {
        Note(stringResource(R.string.ag_spend_today, usd(today)), Modifier.testTag(SpendTags.TODAY))
        Note(stringResource(R.string.ag_spend_month, usd(month)))
        Field(stringResource(R.string.ag_spend_alert), alert, SpendTags.ALERT, number = true) { alert = it }
        Field(stringResource(R.string.ag_spend_cap), capText, SpendTags.CAP, number = true) { capText = it }
        PinkButton(stringResource(R.string.ag_save), onClick = {
            val a = amount(alert)
            val c = if (capText.isBlank()) null else amount(capText)
            if (a == null || (capText.isNotBlank() && c == null)) {
                note = bad
            } else {
                meter.alertUsd = a
                meter.hardCapUsd = c
                note = saved
            }
        }, modifier = Modifier.testTag(SpendTags.SAVE))
        if (cap == null) {
            Note(stringResource(R.string.ag_spend_suggest))
            SoftButton(stringResource(R.string.ag_spend_use_suggest), onClick = { meter.hardCapUsd = SUGGESTED_CAP_USD; note = saved }, modifier = Modifier.testTag(SpendTags.SUGGEST))
        }
        note?.let { Note(it, Modifier.testTag(SpendTags.NOTE)) }
    }
}

const val SUGGESTED_CAP_USD = 1.0

/** A dollar amount he typed: a number from 0 to 1000; null otherwise. */
internal fun amount(s: String): Double? = s.trim().replace('，', '.').toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }

internal fun usd(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
