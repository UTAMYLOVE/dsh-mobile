package app.dshmobile.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyApi
import app.dshmobile.R
import app.dshmobile.ui.BodyUi
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.MoneyUi
import app.dshmobile.ui.rememberIoRunner
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.ScoreDot
import app.dshmobile.ui.theme.SheetFrame
import app.dshmobile.ui.theme.Type
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Check-in: five dots, one tap records it (no model call) and closes. */
@Composable
fun CheckInSheet(api: CompanyApi, onClose: () -> Unit, onDone: () -> Unit) {
    val run = rememberIoRunner()
    SheetFrame(stringResource(R.string.checkin_title), onClose) {
        Row(Modifier.fillMaxWidth().testTag("checkin_dots"), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (n in 1..5) {
                ScoreDot(n, picked = false, onClick = {
                    run { api.direct("check_in", buildJsonObject { put("score", n) }) }
                    onDone()
                })
            }
        }
        KText(stringResource(R.string.checkin_hint), size = Type.Small, alpha = 0.7f)
    }
}

/** Check-out: what went well, what to improve, and the mood dots only when mood is open (stage 3 and later). */
@Composable
fun CheckOutSheet(api: CompanyApi, moodOpen: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val run = rememberIoRunner()
    var good by remember { mutableStateOf("") }
    var improve by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf<Int?>(null) }
    SheetFrame(stringResource(R.string.checkout_title), onClose) {
        InkField(good, { good = it }, stringResource(R.string.checkout_good), tag = "checkout_good")
        InkField(improve, { improve = it }, stringResource(R.string.checkout_improve), tag = "checkout_improve")
        if (moodOpen) {
            KText(stringResource(R.string.checkout_mood), size = Type.Small, alpha = 0.8f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (n in 1..5) ScoreDot(n, picked = mood == n, onClick = { mood = n })
            }
        }
        PillButton(stringResource(R.string.btn_checkout), {
            val args = buildJsonObject {
                put("good", good.trim())
                put("improve", improve.trim())
                mood?.let { put("mood", it) }
            }
            run { api.direct("check_out", args) }
            onDone()
        }, Modifier.testTag("checkout_save"))
    }
}

/** Weekly weigh-in: weight, waist, an optional photo. */
@Composable
fun BodySheet(body: BodyUi, media: MediaUi, onClose: () -> Unit, onDone: () -> Unit, startOnPhoto: Boolean = false) {
    val scope = rememberCoroutineScope()
    var kg by remember { mutableStateOf("") }
    var waist by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Attachment.Photo?>(null) }
    val last = app.dshmobile.ui.rememberLoaded { body.last() }
    SheetFrame(stringResource(R.string.body_title), onClose) {
        val photoPart: @Composable () -> Unit = {
            if (startOnPhoto) KText(stringResource(R.string.body_photo_part), bold = true, modifier = Modifier.testTag("body_photo_part"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipPill(
                    if (photo == null) stringResource(R.string.body_photo) else stringResource(R.string.body_photo_done),
                    { scope.launch { photo = media.takePhoto("") } }, Modifier.testTag("body_photo"), Pal.Lavender,
                )
            }
        }
        if (startOnPhoto) photoPart()
        last.value?.forEach { KText(it, size = Type.Small, alpha = 0.7f) }
        InkField(kg, { kg = it }, stringResource(R.string.body_kg), keyboard = KeyboardType.Decimal, tag = "body_kg")
        InkField(waist, { waist = it }, stringResource(R.string.body_waist), keyboard = KeyboardType.Decimal, tag = "body_waist")
        if (!startOnPhoto) photoPart()
        PillButton(stringResource(R.string.btn_save), {
            scope.launch {
                body.log(kg.replace(',', '.').toDoubleOrNull(), waist.replace(',', '.').toDoubleOrNull(), photo)
                onDone()
            }
        }, Modifier.testTag("body_save"))
    }
}

/** Money note: an amount, one of three buckets, a short note. */
@Composable
fun MoneySheet(money: MoneyUi, onClose: () -> Unit, onDone: () -> Unit) {
    val run = rememberIoRunner()
    var amount by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("LIVING") }
    var note by remember { mutableStateOf("") }
    val summary = app.dshmobile.ui.rememberLoaded { money.summary() }
    SheetFrame(stringResource(R.string.money_title), onClose) {
        summary.value?.forEach { KText(it, size = Type.Small, alpha = 0.7f) }
        InkField(amount, { amount = it.filter(Char::isDigit) }, stringResource(R.string.money_amount), keyboard = KeyboardType.Number, tag = "money_amount")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("LIVING" to R.string.money_living, "FUN" to R.string.money_fun, "INCOME" to R.string.money_income).forEach { (k, label) ->
                ChipPill(stringResource(label), { bucket = k }, Modifier.testTag("bucket_$k"), if (bucket == k) Pal.PinkStrong else Pal.Butter)
            }
        }
        InkField(note, { note = it }, stringResource(R.string.money_note), tag = "money_note")
        PillButton(stringResource(R.string.btn_save), {
            val a = amount.toIntOrNull()
            if (a != null && a > 0) {
                run { money.add(a, bucket, note.trim()) }
                onDone()
            }
        }, Modifier.testTag("money_save"), enabled = (amount.toIntOrNull() ?: 0) > 0)
    }
}
