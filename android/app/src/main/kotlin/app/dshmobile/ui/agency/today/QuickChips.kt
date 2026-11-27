package app.dshmobile.ui.agency.today

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.agency.core.brain.Discussion
import app.agency.core.engine.IdolCardFacts
import app.agency.core.schedule.Feature
import app.dshmobile.R
import app.dshmobile.agency.media.PhotoTarget
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.meal.MealTags
import app.dshmobile.ui.kit.Chip

/**
 * The quick chips above the composer (redesign 01/02): each says the words it stands for. Week 1 (only 理論課): 我想學… (puts
 * 「我想學」 in the composer), 換一支影片, 這裡有問題. From week 2: 喝完一壺, 喝了半壺, 出門跑單, 收工 and the meal extras (相簿,
 * 菜單, 收據) as each opens; 這裡有問題 always. The row scrolls sideways.
 */
@Composable
fun QuickChips(
    facts: IdolCardFacts?,
    busy: Boolean,
    onSay: (String) -> Unit,
    onPrefill: (String) -> Unit,
    onPhoto: (PhotoTarget) -> Unit,
    onPick: (PhotoTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = facts?.open.orEmpty()
    fun has(f: Feature) = f in open
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (has(Feature.WATER)) {
            val full = stringResource(R.string.ag_water_full)
            val half = stringResource(R.string.ag_water_half)
            Chip(full, onClick = { onSay(full) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_FULL))
            Chip(half, onClick = { onSay(half) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_HALF))
        }
        if (has(Feature.RIDE)) {
            val start = stringResource(R.string.ag_ride_start)
            val end = stringResource(R.string.ag_ride_end)
            Chip(start, onClick = { onSay(start) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_START))
            Chip(end, onClick = { onSay(end) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_END))
        }
        val learn = stringResource(R.string.ag_chip_learn_prefill)
        Chip(stringResource(R.string.ag_chip_learn), onClick = { onPrefill(learn) }, enabled = !busy)
        if (!has(Feature.MEALS)) {
            val change = stringResource(R.string.ag_chip_change_video_say)
            Chip(stringResource(R.string.ag_chip_change_video), onClick = { onSay(change) }, enabled = !busy)
        }
        Chip(stringResource(R.string.ag_discuss), onClick = { onSay(Discussion.OPEN_WORD) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.DISCUSS))
        if (has(Feature.MEALS)) {
            // T15c / T15b: a meal photo from the gallery, a menu or a receipt for the image model to read (a list he confirms).
            Chip(stringResource(R.string.ag_pick_meal), onClick = { onPick(PhotoTarget.Meal) }, enabled = !busy, modifier = Modifier.testTag(MealTags.PICK))
            Chip(stringResource(R.string.ag_photo_menu), onClick = { onPhoto(PhotoTarget.Menu) }, enabled = !busy, modifier = Modifier.testTag(MealTags.MENU))
            Chip(stringResource(R.string.ag_photo_receipt), onClick = { onPhoto(PhotoTarget.Receipt) }, enabled = !busy, modifier = Modifier.testTag(MealTags.RECEIPT))
        }
    }
}
