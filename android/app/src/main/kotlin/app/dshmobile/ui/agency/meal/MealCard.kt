package app.dshmobile.ui.agency.meal

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.Meal
import app.agency.core.model.PendingLabel
import app.dshmobile.R
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.agency.onboarding.decodeThumb
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Pill
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MealTags {
    const val CARD = "ag-meal-"
    const val THUMB = "ag-meal-thumb-"
    const val BROKEN = "ag-meal-broken-"
    const val LOADING = "ag-meal-loading-"
    const val UNVERIFIED = "ag-meal-unverified-"
    const val PHOTO = "ag-meal-photo"
    const val MENU = "ag-photo-menu"
    const val LATE = "ag-meal-late-"
    const val PICK = "ag-meal-pick"
    const val RECEIPT = "ag-photo-receipt"
    const val BODY = "ag-photo-body"
    const val LABEL = "ag-meal-label-"
    const val VIEWER = "ag-meal-viewer"
    const val DELETE = "ag-meal-delete"
    const val DELETE_YES = "ag-meal-delete-yes"
    const val DELETE_NO = "ag-meal-delete-no"
    const val CLOSE = "ag-meal-close"
    const val FORM = "ag-label-form"
    const val FORM_KCAL = "ag-label-kcal"
    const val FORM_PROTEIN = "ag-label-protein"
    const val FORM_SAVE = "ag-label-save"
    const val FORM_ERROR = "ag-label-error"
}

/** What a meal card can do: take the photo (拍這餐), open the photo, fill a label; a search card: open its link, save it. */
data class MealActions(
    val onPhoto: () -> Unit = {},
    val onOpen: (Meal) -> Unit = {},
    val onLabel: (PendingLabel) -> Unit = {},
    val onLink: (String) -> Unit = {},
    val onSavePlace: (String) -> Unit = {},
    /** 確認 / 不要 on a photo reading (T15b). */
    val onConfirmRead: (String, app.agency.core.vision.ReadDraft) -> Unit = { _, _ -> },
    val onDropRead: (String) -> Unit = {},
)

/**
 * Under the body teacher's line about a meal (T15): its photo small (decrypted into memory only; tap to open it), or 「未證實」
 * with 拍這餐; and one 補標示 per item still waiting for its label.
 */
@Composable
fun MealCard(meal: Meal, pending: List<PendingLabel>, vault: MediaVault?, actions: MealActions, busy: Boolean) {
    Column(Modifier.padding(start = 6.dp, top = 4.dp).testTag(MealTags.CARD + meal.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val photo = meal.photoMediaId
        if (photo != null) {
            val desc = stringResource(R.string.ag_meal_photo_desc)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealThumb(photo, vault, 72.dp, Modifier.clickable { actions.onOpen(meal) }.semantics { contentDescription = desc })
                Pill(stringResource(R.string.ag_meal_verified))
                // 補交 (T15c): a photo picked later than the meal, with its capture time.
                meal.lateAt?.let { t ->
                    val z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
                    Text(stringResource(R.string.ag_meal_late, z.hour, z.minute), color = Sakura.RoseBrown, fontSize = 12.sp, modifier = Modifier.testTag(MealTags.LATE + meal.id))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ag_meal_unverified), color = Sakura.RoseBrown, fontSize = 12.sp,
                    modifier = Modifier.testTag(MealTags.UNVERIFIED + meal.id),
                )
                Chip(stringResource(R.string.ag_meal_photo), onClick = { if (!busy) actions.onPhoto() })
            }
        }
        if (pending.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pending.forEach { p ->
                    Chip(
                        stringResource(R.string.ag_label_button, p.name), onClick = { actions.onLabel(p) },
                        modifier = Modifier.testTag(MealTags.LABEL + p.mealId + "-" + p.itemIdx),
                    )
                }
            }
        }
    }
}

/** A sealed photo, decrypted into memory and shown at [size]; a placeholder line when it cannot be opened. */
@Composable
fun MealThumb(mediaId: String, vault: MediaVault?, size: Dp, modifier: Modifier = Modifier, maxPx: Int = 480) {
    // null while loading; Result.failure when the vault or the decoder gave nothing.
    val state by produceState<Result<Bitmap>?>(null, mediaId, vault) {
        onMain {
            value = withContext(Dispatchers.IO) {
                vault?.let { decodeThumb(it, mediaId, maxPx) }?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val bmp = state?.getOrNull()
    // The tag sits on the box itself: a clickable parent merges what is inside it.
    val tag = when {
        bmp != null -> MealTags.THUMB + mediaId
        state?.isFailure == true -> MealTags.BROKEN + mediaId
        else -> MealTags.LOADING + mediaId
    }
    Box(modifier.size(size).clip(shape).background(Sakura.Background, shape).testTag(tag), contentAlignment = Alignment.Center) {
        when {
            bmp != null -> Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            state?.isFailure == true -> Text(
                stringResource(R.string.ag_meal_photo_broken), color = Sakura.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(4.dp),
            )
        }
    }
}
