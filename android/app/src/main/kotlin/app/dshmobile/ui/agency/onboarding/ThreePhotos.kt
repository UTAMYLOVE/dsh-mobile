package app.dshmobile.ui.agency.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.BaselineSteps
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.Baseline
import app.dshmobile.R
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The label of each of the three photos. */
@Composable
fun photoLabel(step: OnboardingStep): String = stringResource(
    when (step) {
        OnboardingStep.PHOTO_FRONT -> R.string.ag_ob_front
        OnboardingStep.PHOTO_SIDE -> R.string.ag_ob_side
        else -> R.string.ag_ob_back
    },
)

/**
 * 全身照 正面 → 側面 → 背面 (arch T07): the three slots (the ones handed in show their thumbnail), the hint to use the same spot
 * and light, and 拍照 for the open one, which opens the system camera.
 */
@Composable
fun ThreePhotos(open: OnboardingStep, baseline: Baseline?, vault: MediaVault?, onTake: (OnboardingStep) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnboardingStep.PHOTOS.forEach { step ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(baseline?.let { BaselineSteps.mediaOf(it, step) }, vault, Modifier.fillMaxWidth().aspectRatio(.75f))
                Text(
                    photoLabel(step) + if (baseline != null && BaselineSteps.has(baseline, step)) " ・ " + stringResource(R.string.ag_ob_done_mark) else "",
                    color = if (step == open) Sakura.Text else Sakura.TextSecondary, fontSize = 13.sp,
                )
            }
        }
    }
    Text(stringResource(R.string.ag_ob_photo_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
    PinkButton(stringResource(R.string.ag_ob_take_photo) + "（" + photoLabel(open) + "）", onClick = { onTake(open) }, modifier = Modifier.testTag(OnboardingTags.TAKE_PHOTO))
}

/** A photo from the vault, decrypted into memory only and shown small; an empty box when there is none. */
@Composable
fun Thumb(mediaId: String?, vault: MediaVault?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, mediaId, vault) {
        if (mediaId == null || vault == null) return@produceState
        onMain { value = withContext(Dispatchers.IO) { decodeThumb(vault, mediaId) } }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Sakura.Background, shape).then(if (mediaId != null) Modifier.testTag(OnboardingTags.THUMB + mediaId) else Modifier), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth()) }
    }
}

/** The vault's plaintext decoded at a quarter size or smaller; null when it cannot be opened or decoded. */
internal fun decodeThumb(vault: MediaVault, id: String, maxPx: Int = 480): Bitmap? = runCatching {
    val bytes = vault.get(id)
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } finally {
        bytes.fill(0)
    }
}.getOrNull()
