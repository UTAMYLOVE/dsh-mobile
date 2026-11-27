package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.engine.IdolCardFacts
import app.agency.core.engine.WaterPlan
import app.agency.core.rules.CoachRules
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onboarding.Thumb
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.MemberColour
import app.dshmobile.ui.kit.Pill
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object IdolTags {
    const val CARD = AgencyTags.TRAINEE
    const val FOLDED = "ag-idol-folded"
    const val LEVELS = "ag-idol-levels"
    const val SPECIAL = "ag-idol-special"
    const val THEME = "ag-idol-theme"
    const val NAME = "ag-idol-name"
    const val NUMBER = "ag-idol-no"
    const val BAND = "ag-idol-band"
    const val STICKERS = "ag-idol-stickers"
    const val STICKER = "ag-idol-sticker-"
    const val GOAL = "ag-idol-goal"
    const val LEFT = "ag-idol-left"
    const val BREACHES = "ag-idol-breaches"
    const val SETUP = "ag-idol-setup"
    const val SETUP_NAME = "ag-idol-setup-name"
    const val SETUP_SAVE = "ag-idol-setup-save"
    const val SETUP_SKIP = "ag-idol-setup-skip"
    const val COLOR = "ag-idol-color-"
    const val PHOTO = "ag-idol-photo"
    const val NEXT = "ag-idol-next"
    const val GAPS = "ag-idol-gaps"
    const val UNVERIFIED = "ag-idol-unverified"
    const val WATER = "ag-idol-water"
    const val INCOME = "ag-idol-income"
}

/**
 * 練習生證 as an idol profile card (plan v3 §17, arch T10). Folded it is one line (「小雨娛樂 ・ 入社第 N 天 ・ 今日違規 N ・ 剩
 * R 大卡」, plus 「・ 入社資料缺 N 項」 while items are missing: review A4); it opens by itself once 入社建檔 is walked through
 * and 補交 or 藝名/代表色 is still to do, and a tap folds or opens it (kept across rotation). TalkBack hears 已展開／已收起.
 * 入社資料 (缺 N 項, 補交, the file) stays on its own row under the card, folded or not.
 */
@Composable
fun IdolCardView(agency: AgencyApp, facts: IdolCardFacts, onboarding: OnboardingStatus?, onSaved: () -> Unit = {}) {
    val settingsRevision by agency.settings.revision.collectOnMain()
    val setupSkipped = remember(settingsRevision) { agency.settings.idolSetupSkipped }
    val setupDue = onboarding?.completed == true && (facts.stageName == null || facts.memberColor == null) && !setupSkipped
    // Opens by itself only once 入社建檔 was walked through and something is still to do on the card (補交, 藝名/代表色); while
    // a question is open the 入社建檔 panel below the conversation has the buttons, and the conversation keeps its room.
    val autoOpen = onboarding != null && onboarding.completed && onboarding.open == null && (onboarding.missing.isNotEmpty() || setupDue)
    var chosen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val open = chosen ?: autoOpen
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(22.dp)
    val stateText = stringResource(if (open) R.string.ag_card_open_state else R.string.ag_card_folded_state)
    val toggleLabel = stringResource(if (open) R.string.ag_card_fold else R.string.ag_card_unfold)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.White, shape).border(1.dp, accent.fill, shape)
            .testTag(IdolTags.CARD),
    ) {
        val header = Modifier.fillMaxWidth().clickable(onClickLabel = toggleLabel) { chosen = !open }
            .semantics { stateDescription = stateText; onClick(label = toggleLabel) { chosen = !open; true } }
        if (!open) {
            Text(
                foldedLine(facts), color = Sakura.Text, fontSize = 14.sp,
                modifier = header.padding(horizontal = 16.dp, vertical = 10.dp).testTag(IdolTags.FOLDED),
            )
            return@Column
        }
        Row(header.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Photo(agency, facts)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    facts.stageName ?: stringResource(R.string.ag_card_no_name), color = Sakura.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(IdolTags.NAME),
                )
                Text(stringResource(R.string.ag_card_number, facts.company, facts.traineeNo), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.testTag(IdolTags.NUMBER))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(stringResource(R.string.ag_card_level, facts.level))
                    facts.joinDay?.let { Pill(stringResource(R.string.ag_join_day, it)) }
                }
            }
        }
        // The 代表色 band (the accent, readable text on it guaranteed by MemberColour).
        Box(Modifier.fillMaxWidth().height(6.dp).background(accent.fill).testTag(IdolTags.BAND))
        // The open card never takes the whole screen: its rows scroll inside it, the conversation and the composer stay.
        Column(
            Modifier.heightIn(max = OPEN_MAX).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StickerStrip(facts)
            facts.next?.let { Text(stringResource(R.string.ag_card_next, it.slot.time, it.slot.label), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.NEXT)) }
            if (facts.gaps.isNotEmpty()) {
                Text(stringResource(R.string.ag_card_gaps, facts.gaps.joinToString("、") { it.slot.label }), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.GAPS))
            }
            if (facts.waterTargetHalves > 0) {
                Text(
                    stringResource(R.string.ag_card_water, WaterPlan.bottles(facts.waterHalves), WaterPlan.bottles(facts.waterTargetHalves)),
                    color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.WATER),
                )
            }
            // 外送 (owner 2026-11-26): the week's total against his target.
            facts.incomeWeek?.let { total ->
                val text = facts.incomeTarget?.let { stringResource(R.string.ag_card_income, total, it) } ?: stringResource(R.string.ag_card_income_no_target, total)
                Text(text, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.INCOME))
            }
            // 特別日 and 主題週 (新鮮感).
            facts.special?.let { Text(it, color = Sakura.RoseBrown, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.SPECIAL)) }
            facts.theme?.let { Text(stringResource(R.string.ag_card_theme, it), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.THEME)) }
            // 每科等級 (T19e).
            if (facts.levels.isNotEmpty()) {
                Text(
                    stringResource(R.string.ag_card_levels, facts.levels.joinToString("、") { (s, l) -> "$s $l" }), color = Sakura.Text, fontSize = 14.sp,
                    modifier = Modifier.testTag(IdolTags.LEVELS),
                )
            }
            if (facts.photocards > 0) {
                Text(stringResource(R.string.ag_card_photocards, facts.photocards), color = Sakura.Text, fontSize = 14.sp)
            }
            if (facts.unverified.isNotEmpty()) {
                Text(
                    stringResource(R.string.ag_card_unverified, facts.unverified.joinToString("、")), color = Sakura.Text, fontSize = 14.sp,
                    modifier = Modifier.testTag(IdolTags.UNVERIFIED),
                )
            }
            Text(
                facts.nextGoalKg?.let { stringResource(R.string.ag_card_goal, CoachRules.kg(it)) } ?: stringResource(R.string.ag_card_goal_none),
                color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.GOAL),
            )
            Text(leftLine(facts), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.LEFT))
            Text(stringResource(R.string.ag_card_breaches, facts.breachesToday), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(IdolTags.BREACHES))
        }
        // The setup stays in view (outside the scrolling rows).
        if (setupDue) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                IdolSetup(agency, facts, onSaved = onSaved, onSkip = { agency.settings.idolSetupSkipped = true; onSaved() })
            }
        }
    }
}

/** The most the open card's rows take before they scroll. */
private val OPEN_MAX = 260.dp

/** The folded line (review A4). */
@Composable
internal fun foldedLine(f: IdolCardFacts): String {
    val parts = mutableListOf(f.company)
    f.joinDay?.let { parts += stringResource(R.string.ag_join_day, it) }
    parts += stringResource(R.string.ag_card_breaches, f.breachesToday)
    parts += if (f.kcalLeft >= 0) stringResource(R.string.ag_card_left_short, f.kcalLeft) else stringResource(R.string.ag_card_over_short, -f.kcalLeft)
    if (f.baselineMissing > 0) parts += stringResource(R.string.ag_ob_missing, f.baselineMissing)
    return parts.joinToString(" ・ ")
}

@Composable
private fun leftLine(f: IdolCardFacts): String =
    if (f.kcalLeft >= 0) stringResource(R.string.ag_card_left, f.kcalLeft, f.proteinLeftG) else stringResource(R.string.ag_card_over, -f.kcalLeft, f.proteinLeftG)

@Composable
private fun Photo(agency: AgencyApp, f: IdolCardFacts) {
    val accent = LocalAccent.current
    val desc = stringResource(R.string.ag_card_photo)
    Box(
        Modifier.size(64.dp).background(accent.fill, CircleShape).padding(3.dp).semantics { contentDescription = desc }.testTag(IdolTags.PHOTO),
        contentAlignment = Alignment.Center,
    ) {
        if (f.photoMediaId != null) {
            Thumb(f.photoMediaId, agency.mediaVault(), Modifier.size(58.dp).background(Sakura.White, CircleShape))
        } else {
            Text((f.stageName ?: f.company).take(1), color = accent.onFill, fontSize = 24.sp)
        }
    }
}

/** Today's 手帳 stickers: one per done item; none yet says so. */
@Composable
private fun StickerStrip(f: IdolCardFacts) {
    val accent = LocalAccent.current
    if (f.stickers.isEmpty()) {
        Text(stringResource(R.string.ag_card_no_stickers), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.testTag(IdolTags.STICKERS))
        return
    }
    val desc = stringResource(R.string.ag_card_stickers_desc, f.stickers.size)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = desc }.testTag(IdolTags.STICKERS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        f.stickers.forEachIndexed { i, s ->
            Text(
                "★ " + s.kind.zh, color = accent.onFill, fontSize = 12.sp,
                modifier = Modifier.background(accent.fill, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp).testTag(IdolTags.STICKER + i),
            )
        }
    }
}

/** 藝名 and 代表色 (asked once after 入社建檔; 「先不要」 hides it, 設定 › 練習生資料 still has both). */
@Composable
fun IdolSetup(agency: AgencyApp, facts: IdolCardFacts, onSaved: () -> Unit, onSkip: (() -> Unit)?) {
    val ready = agency.start.value as? StartState.Ready ?: return
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(facts.stageName.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun save(stageName: String?, color: String?) {
        scope.launch {
            onMain {
                problem = withContext(Dispatchers.IO) { runCatching { ready.brain.setIdol(stageName, color) }.getOrElse { "沒有存到，再試一次。" } }
                if (problem == null) onSaved()
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag(IdolTags.SETUP), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ag_card_setup_title), color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(app.agency.core.brain.handlers.IdolProfile.MAX_CHARS) }, singleLine = true,
            label = { Text(stringResource(R.string.ag_card_setup_name)) }, modifier = Modifier.fillMaxWidth().testTag(IdolTags.SETUP_NAME),
        )
        problem?.let { Text(it, color = Sakura.Text, fontSize = 13.sp) }
        Text(stringResource(R.string.ag_card_setup_color), color = Sakura.TextSecondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MemberColour.PRESETS.forEachIndexed { i, (zh, hex) ->
                val a = MemberColour.accent(hex)
                Text(
                    zh, color = a.onFill, fontSize = 13.sp,
                    modifier = Modifier.background(a.fill, RoundedCornerShape(16.dp))
                        .then(if (facts.memberColor.equals(hex, ignoreCase = true)) Modifier.border(2.dp, Sakura.Text, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable { save(null, hex) }.padding(horizontal = 12.dp, vertical = 8.dp).testTag(IdolTags.COLOR + i),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinkButton(stringResource(R.string.ag_save), onClick = { save(name, null) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(IdolTags.SETUP_SAVE))
            if (onSkip != null) SoftButton(stringResource(R.string.ag_card_setup_skip), onClick = onSkip, modifier = Modifier.testTag(IdolTags.SETUP_SKIP))
        }
    }
}

/** A colour chip outside the setup (設定 › 練習生資料 shows the same palette). */
@Composable
fun ColourDot(hex: String?) {
    val a = MemberColour.accent(hex)
    Box(Modifier.size(18.dp).background(a.fill, CircleShape))
}
