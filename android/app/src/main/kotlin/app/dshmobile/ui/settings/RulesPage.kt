package app.dshmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.CompanyRules
import app.agency.core.rules.CoachRules
import app.agency.core.rules.ProcessedFood
import app.agency.core.rules.RulesBook
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RulesTags {
    const val VIEW = "ag-rules"
    const val EDIT = "ag-rules-edit"
    const val SAVE = "ag-rules-save"
    const val CONFIRM_1 = "ag-rules-confirm-1"
    const val CONFIRM_2 = "ag-rules-confirm-2"
    const val RESULT = "ag-rules-result"
    const val FIELD = "ag-rules-field-"
    const val QUOTA = FIELD + "quota"
    const val PROTEIN = FIELD + "protein"
    const val FAT = FIELD + "fat"
    const val CARBS = FIELD + "carbs"
    const val FLOOR = FIELD + "floor"
    const val CAP = FIELD + "cap"
    const val CHEAT = FIELD + "cheat"
    const val ZERO = FIELD + "zero"
    const val GOALS = FIELD + "goals"
}

/**
 * 設定 › 公司規章 (arch T11): the rules read-only, and 「修改」 for him alone: the new numbers pass [app.agency.core.rules.SafetyBounds]
 * and two confirmations, then a new version with its history and an audit row. No conversation, role or link can do this.
 */
@Composable
fun RulesPage(ready: StartState.Ready) {
    var revision by remember { mutableIntStateOf(0) }
    val rules by produceState<CompanyRules?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.store.rules.current() ?: CompanyRules() }.getOrNull() } }
    }
    val r = rules ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.ag_set_rules)) {
        Note(stringResource(R.string.ag_rules_locked_note))
        if (!editing) {
            RulesView(r)
            result?.let { Text(it, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(RulesTags.RESULT)) }
            SoftButton(stringResource(R.string.ag_rules_edit), onClick = { editing = true; result = null }, modifier = Modifier.testTag(RulesTags.EDIT))
        } else {
            RulesEdit(r, onCancel = { editing = false }) { next ->
                val saved = withContext(Dispatchers.IO) { RulesBook.edit(ready.store, next, System.currentTimeMillis()) { UUID.randomUUID().toString() } }
                result = when (saved) {
                    is RulesBook.Result.Saved -> "已儲存，公司規章第 ${saved.rules.version} 版。"
                    is RulesBook.Result.Refused -> saved.why.joinToString("") { it.zh }
                    RulesBook.Result.Unchanged -> "沒有改到任何數字。"
                }
                if (saved !is RulesBook.Result.Refused) {
                    editing = false
                    revision++
                }
            }
            result?.let { Text(it, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(RulesTags.RESULT)) }
        }
    }
}

@Composable
private fun RulesView(r: CompanyRules) {
    val today = CoachRules.coachDay(java.time.Instant.now(), app.agency.core.time.AgencyClock.TAIPEI, 5)
    val lines = listOf(
        "每天 ${r.quotaKcal} 大卡、蛋白質 ${r.proteinG} 克、脂肪 ${r.fatG} 克、碳水 ${r.carbsG} 克",
        "最低 ${r.floorKcal} 大卡，任何角色都不能叫你吃得更少",
        "單餐上限 ${r.mealCapKcal} 大卡（天花板，不是目標）",
        if (RulesBook.cheatOpen(today)) "休假餐一週 ${r.cheatMealsPerWeek} 餐，事先說" else RulesBook.CHEAT_CLOSED,
        "零卡飲料一週最多 ${r.zeroCalPerWeek} 瓶",
        "目標：" + r.goalsKg.joinToString(" → ") { CoachRules.kg(it) + " 公斤" } + "（畫面只顯示下一個）",
        "不准吃：" + ProcessedFood.banned(r).joinToString("、") { it.zh },
        "吃超過：明天回到 ${r.quotaKcal}，不准少吃、跳餐、多運動來補",
    )
    lines.forEach { Text("・$it", color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(RulesTags.VIEW)) }
}

@Composable
private fun RulesEdit(r: CompanyRules, onCancel: () -> Unit, onSave: suspend (CompanyRules) -> Unit) {
    val scope = rememberCoroutineScope()
    var quota by rememberSaveable { mutableStateOf(r.quotaKcal.toString()) }
    var protein by rememberSaveable { mutableStateOf(r.proteinG.toString()) }
    var fat by rememberSaveable { mutableStateOf(r.fatG.toString()) }
    var carbs by rememberSaveable { mutableStateOf(r.carbsG.toString()) }
    var floor by rememberSaveable { mutableStateOf(r.floorKcal.toString()) }
    var cap by rememberSaveable { mutableStateOf(r.mealCapKcal.toString()) }
    var cheat by rememberSaveable { mutableStateOf(r.cheatMealsPerWeek.toString()) }
    var zero by rememberSaveable { mutableStateOf(r.zeroCalPerWeek.toString()) }
    var goals by rememberSaveable { mutableStateOf(r.goalsKg.joinToString("、") { CoachRules.kg(it) }) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var bad by remember { mutableStateOf(false) }
    Field(stringResource(R.string.ag_rules_quota), quota, RulesTags.QUOTA, number = true) { quota = it }
    Field(stringResource(R.string.ag_rules_protein), protein, RulesTags.PROTEIN, number = true) { protein = it }
    Field(stringResource(R.string.ag_rules_fat), fat, RulesTags.FAT, number = true) { fat = it }
    Field(stringResource(R.string.ag_rules_carbs), carbs, RulesTags.CARBS, number = true) { carbs = it }
    Field(stringResource(R.string.ag_rules_floor), floor, RulesTags.FLOOR, number = true) { floor = it }
    Field(stringResource(R.string.ag_rules_cap), cap, RulesTags.CAP, number = true) { cap = it }
    Field(stringResource(R.string.ag_rules_cheat), cheat, RulesTags.CHEAT, number = true) { cheat = it }
    Field(stringResource(R.string.ag_rules_zero), zero, RulesTags.ZERO, number = true) { zero = it }
    Field(stringResource(R.string.ag_rules_goals), goals, RulesTags.GOALS) { goals = it }
    if (bad) Text(stringResource(R.string.ag_rules_bad_number), color = Sakura.Text, fontSize = 14.sp)
    fun next(): CompanyRules? = runCatching {
        r.copy(
            quotaKcal = quota.trim().toInt(), proteinG = protein.trim().toInt(), fatG = fat.trim().toInt(), carbsG = carbs.trim().toInt(),
            floorKcal = floor.trim().toInt(), mealCapKcal = cap.trim().toInt(), cheatMealsPerWeek = cheat.trim().toInt(),
            zeroCalPerWeek = zero.trim().toInt(),
            goalsKg = goals.split('、', ',', '，', ' ', '→').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() },
        )
    }.getOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PinkButton(stringResource(R.string.ag_save), onClick = { bad = next() == null; if (!bad) step = 1 }, modifier = Modifier.testTag(RulesTags.SAVE))
        SoftButton(stringResource(R.string.ag_rules_cancel), onClick = onCancel)
    }
    if (step == 1) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { Text(stringResource(R.string.ag_rules_confirm_1)) },
            confirmButton = { TextButton(onClick = { step = 2 }, modifier = Modifier.testTag(RulesTags.CONFIRM_1)) { Text(stringResource(R.string.ag_rules_yes)) } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.ag_legacy_confirm_no)) } },
        )
    }
    if (step == 2) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { Text(stringResource(R.string.ag_rules_confirm_2)) },
            confirmButton = {
                TextButton(
                    onClick = { step = 0; next()?.let { n -> scope.launch { onMain { onSave(n) } } } },
                    modifier = Modifier.testTag(RulesTags.CONFIRM_2),
                ) { Text(stringResource(R.string.ag_rules_yes_really)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.ag_legacy_confirm_no)) } },
        )
    }
}
