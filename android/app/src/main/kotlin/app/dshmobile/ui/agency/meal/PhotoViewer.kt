package app.dshmobile.ui.agency.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.agency.core.model.Meal
import app.dshmobile.R
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton

/**
 * A meal's photo, large (T15): decrypted into memory only, never written anywhere. 刪掉照片 asks once more before the meal goes
 * back to 「未證實」.
 */
@Composable
fun PhotoViewer(meal: Meal, vault: MediaVault?, onDelete: () -> Unit, onClose: () -> Unit) {
    val id = meal.photoMediaId ?: return
    var asking by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.background(Sakura.White, RoundedCornerShape(20.dp)).padding(16.dp).testTag(MealTags.VIEWER),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MealThumb(id, vault, 280.dp, maxPx = 1600)
            if (asking) {
                Text(stringResource(R.string.ag_meal_photo_delete_ask), color = Sakura.Text, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinkButton(stringResource(R.string.ag_meal_photo_delete_yes), onClick = onDelete, modifier = Modifier.testTag(MealTags.DELETE_YES))
                    SoftButton(stringResource(R.string.ag_cancel), onClick = { asking = false }, modifier = Modifier.testTag(MealTags.DELETE_NO))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(stringResource(R.string.ag_meal_photo_delete), onClick = { asking = true }, modifier = Modifier.testTag(MealTags.DELETE))
                    PinkButton(stringResource(R.string.ag_close), onClick = onClose, modifier = Modifier.testTag(MealTags.CLOSE))
                }
            }
        }
    }
}
