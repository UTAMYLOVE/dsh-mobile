package app.dshmobile.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.agency.core.agent.Attachment
import app.dshmobile.R
import app.dshmobile.ui.FoodRow
import app.dshmobile.ui.LocalIo
import app.dshmobile.ui.MealUi
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.PracticeUi
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.SheetFrame
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Totals of the ticked foods: code adds them, the screen only shows them. */
fun mealTotals(picks: List<FoodRow>): Pair<Int, Double> = picks.sumOf { it.kcal } to picks.sumOf { it.proteinG }

/** The slots of 「記這一餐」, in the order shown. */
val MealSlotChoices: List<Pair<String, Int>> = listOf(
    "FIRST" to R.string.meal_slot_first,
    "DINNER" to R.string.meal_slot_dinner,
    "NIGHT" to R.string.meal_slot_night,
    "EXTRA" to R.string.meal_slot_extra,
)

private fun num(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

/** 記這一餐: pick the slot, tick foods from his own table, an optional photo, save. No model call. */
@Composable
fun MealLogSheet(
    meals: MealUi,
    media: MediaUi,
    startSlot: String,
    startPhoto: Attachment.Photo?,
    onClose: () -> Unit,
    onDone: () -> Unit,
    onAsk: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val io = LocalIo.current
    val foods = rememberLoaded { meals.foods() }
    var slot by remember { mutableStateOf(startSlot.ifBlank { "FIRST" }) }
    var photo by remember { mutableStateOf(startPhoto) }
    var query by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val ticked = remember { mutableStateListOf<Int>() }
    SheetFrame(stringResource(R.string.meal_sheet_title), onClose) {
        val all = foods.value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MealSlotChoices.forEach { (k, label) ->
                ChipPill(stringResource(label), { slot = k }, Modifier.testTag("meal_slot_$k"), if (slot == k) Pal.PinkStrong else Pal.Butter)
            }
        }
        if (all != null && all.isEmpty()) {
            KText(stringResource(R.string.meal_no_foods), modifier = Modifier.testTag("meal_empty"))
            PillButton(stringResource(R.string.meal_ask), onAsk, Modifier.testTag("meal_ask"), Pal.Lavender)
            return@SheetFrame
        }
        if (all != null) {
            if (all.size > 8) InkField(query, { query = it }, stringResource(R.string.meal_search), tag = "meal_search")
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEachIndexed { i, f ->
                    if (query.isNotBlank() && !f.name.contains(query.trim())) return@forEachIndexed
                    val on = i in ticked
                    Row(
                        Modifier.fillMaxWidth().testTag("food_$i").sticker(if (on) Pal.Mint else Pal.White, RoundedCornerShape(14.dp), 0.dp, 1.5.dp)
                            .tap({ if (on) ticked.remove(i) else ticked.add(i) }).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KText(f.name, Modifier.weight(1f), bold = on)
                        KText(stringResource(R.string.meal_food_line, f.kcal, num(f.proteinG)), size = Type.Tiny, alpha = 0.75f)
                    }
                }
            }
            val picks = ticked.sorted().mapNotNull { all.getOrNull(it) }
            val (kcal, protein) = mealTotals(picks)
            KText(stringResource(R.string.meal_total, kcal, num(protein)), bold = true, modifier = Modifier.testTag("meal_total"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipPill(
                    stringResource(if (photo == null) R.string.meal_photo_add else R.string.meal_photo_done),
                    { scope.launch { media.takePhoto("")?.let { photo = it } } }, Modifier.testTag("meal_photo"), Pal.Lavender,
                )
                PillButton(stringResource(R.string.btn_save), {
                    scope.launch {
                        val line = withContext(io) { meals.log(slot, picks, photo) }
                        if (line != null) onDone() else failed = true
                    }
                }, Modifier.testTag("meal_save"), enabled = picks.isNotEmpty())
            }
            if (failed) {
                KText(stringResource(R.string.meal_failed), color = Pal.Warn, size = Type.Small, modifier = Modifier.testTag("meal_failed"))
                PillButton(stringResource(R.string.meal_ask), onAsk, Modifier.testTag("meal_ask"), Pal.Lavender, Type.Small)
            }
        }
    }
}

/** This week's real-person task: read it and tick it off without the model; or ask 小葵. */
@Composable
fun RealTaskSheet(practice: PracticeUi, onClose: () -> Unit, onDone: () -> Unit, onAsk: () -> Unit) {
    val scope = rememberCoroutineScope()
    val io = LocalIo.current
    val task = rememberLoaded { practice.realTask() }
    var closed by remember { mutableStateOf(false) }
    SheetFrame(stringResource(R.string.real_title), onClose) {
        val t = task.value
        if (t != null) {
            KText(t.text, size = Type.Body, bold = true, modifier = Modifier.testTag("real_text"))
            if (t.done) {
                KText(stringResource(R.string.real_is_done), size = Type.Small, modifier = Modifier.testTag("real_is_done"))
            } else {
                PillButton(stringResource(R.string.real_done_btn), {
                    scope.launch {
                        val line = withContext(io) { practice.realTaskDone() }
                        if (line != null) onDone() else closed = true
                    }
                }, Modifier.testTag("real_done"), Pal.Mint)
            }
        } else if (task.value == null && closed) {
            KText(stringResource(R.string.real_closed))
        }
        Box(Modifier.padding(top = 2.dp)) { PillButton(stringResource(R.string.real_ask), onAsk, Modifier.testTag("real_ask"), Pal.Lavender, Type.Small) }
    }
}
