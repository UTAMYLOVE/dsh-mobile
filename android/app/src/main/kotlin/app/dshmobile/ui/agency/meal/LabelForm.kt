package app.dshmobile.ui.agency.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.MealDesk
import app.agency.core.model.PendingLabel
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton

/** What the label form found wrong, or the numbers to save. */
sealed interface LabelInput {
    data class Ok(val kcal: Int, val proteinG: Double?) : LabelInput
    data class Bad(val kcal: Boolean, val protein: Boolean) : LabelInput

    companion object {
        /** kcal a whole number 1..5000 (per portion, as the label says), protein empty or 0..500 (arch T15). */
        fun parse(kcal: String, protein: String): LabelInput {
            val k = kcal.trim().toIntOrNull()?.takeIf { it in MealDesk.LABEL_KCAL }
            val pText = protein.trim().replace('，', '.')
            val p = if (pText.isEmpty()) null else pText.toDoubleOrNull()?.takeIf { it in MealDesk.LABEL_PROTEIN }
            val badP = pText.isNotEmpty() && p == null
            return if (k == null || badP) Bad(k == null, badP) else Ok(k, p)
        }
    }
}

/**
 * 補標示 (T15): the kcal and protein of one item, typed from its package or the menu (a photo of the label is only evidence;
 * the model never sees it). The numbers replace the estimate and enter the food table, by code.
 */
@Composable
fun LabelForm(pending: PendingLabel, onSave: (Int, Double?) -> Unit, onClose: () -> Unit) {
    var kcal by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf<String?>(null) }
    val badKcal = stringResource(R.string.ag_label_bad_kcal)
    val badProtein = stringResource(R.string.ag_label_bad_protein)
    // Inline above the composer (no dialog window: a text field there kept the test clock from going idle).
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.White, RoundedCornerShape(20.dp)).padding(16.dp)
            .testTag(MealTags.FORM),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.ag_label_title, pending.name), color = Sakura.Text, fontSize = 17.sp)
        Text(stringResource(R.string.ag_label_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
        OutlinedTextField(
            value = kcal, onValueChange = { kcal = it.take(6) }, singleLine = true, label = { Text(stringResource(R.string.ag_label_kcal)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag(MealTags.FORM_KCAL),
        )
        OutlinedTextField(
            value = protein, onValueChange = { protein = it.take(6) }, singleLine = true, label = { Text(stringResource(R.string.ag_label_protein)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag(MealTags.FORM_PROTEIN),
        )
        bad?.let { Text(it, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.testTag(MealTags.FORM_ERROR)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinkButton(
                stringResource(R.string.ag_save),
                onClick = {
                    when (val r = LabelInput.parse(kcal, protein)) {
                        is LabelInput.Ok -> onSave(r.kcal, r.proteinG)
                        is LabelInput.Bad -> bad = if (r.kcal) badKcal else badProtein
                    }
                },
                modifier = Modifier.testTag(MealTags.FORM_SAVE),
            )
            SoftButton(stringResource(R.string.ag_cancel), onClick = onClose)
        }
    }
}
