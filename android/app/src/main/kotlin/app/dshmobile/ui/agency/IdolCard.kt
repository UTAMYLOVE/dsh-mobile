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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import app.dshmobile.ui.kit.Icons
import app.dshmobile.ui.kit.LineIcon
import app.dshmobile.ui.kit.ProgressBar
import app.dshmobile.ui.kit.Type
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
 * 練習生證 folded to one line (redesign 01/02): the photo with a ring of his colour, a colour dot and his stage name, 「練習生 C
 * ・ 入社第 N 天」 (plus 「・ 貼紙 N 張」 once 簽到 is open), and the round button that opens the idol card ([IdolCardSheet]). No
 * calories, breaches or missing items here: those live where they are open (今天 card, 入社建檔 banner).
 */
@Composable
fun IdolLine(agency: AgencyApp, facts: IdolCardFacts, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val label = stringResource(R.string.ag_card_unfold)
    val folded = stringResource(R.string.ag_card_folded_state)
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(4.dp, shape, ambientColor = Sakura.RoseBrown.copy(alpha = .1f), spotColor = Sakura.RoseBrown.copy(alpha = .1f))
            .background(Sakura.White, shape).background(Sakura.idol(accent), shape).border(1.dp, Sakura.Gold.copy(alpha = .8f), shape)
            .clickable(onClickLabel = label, onClick = onOpen)
            .semantics { stateDescription = folded; onClick(label = label) { onOpen(); true } }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag(IdolTags.CARD),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Photo(agency, facts, 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(accent.fill, CircleShape))
                Text(facts.stageName ?: stringResource(R.string.ag_card_no_name), style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(IdolTags.NAME))
            }
            Text(metaLine(facts), style = Type.Support, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(IdolTags.FOLDED))
        }
        Box(Modifier.size(44.dp).background(Sakura.Background, CircleShape), contentAlignment = Alignment.Center) { LineIcon(Icons.Down) }
    }
}

/** The line under his name: grade and day, and the stickers once 簽到 is open (F1: nothing of a feature not open). */
@Composable
internal fun metaLine(f: IdolCardFacts): String {
    val base = f.joinDay?.let { stringResource(R.string.ag_idol_meta, f.level, it.toInt()) } ?: stringResource(R.string.ag_idol_meta_no_day, f.level)
    return if (app.agency.core.schedule.Feature.SIGN in f.open) base + " " + stringResource(R.string.ag_idol_stickers, f.stickers.size) else base
}

/**
 * 練習生證展開 (redesign 05): the idol profile card over the home screen — a striped band of his colour with 「小雨娛樂 ・ 練習生
 * 第 001 號」, the polaroid with aqua tape, his stage name, 等級 / 入社 / 代表色 / 這週, today's stickers, 五項評分 (after the first
 * 月評), 各科等級 and 下一個目標 (only when he set targets). 小卡牆 and 入社資料 at the bottom; 藝名 and 代表色 inside when due.
 */
@Composable
fun IdolCardSheet(
    agency: AgencyApp, facts: IdolCardFacts, onboarding: OnboardingStatus?, scores: List<Pair<String, Int>>,
    onClose: () -> Unit, onWall: () -> Unit, onFile: () -> Unit, onSaved: () -> Unit = {},
) {
    val settingsRevision by agency.settings.revision.collectOnMain()
    val setupSkipped = remember(settingsRevision) { agency.settings.idolSetupSkipped }
    val setupDue = onboarding?.completed == true && (facts.stageName == null || facts.memberColor == null) && !setupSkipped
    val accent = LocalAccent.current
    val open = facts.open
    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Sakura.Background, Sakura.Page))),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val close = stringResource(R.string.ag_idol_close)
            androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = close }) { LineIcon(Icons.Up) }
            Text(close, style = Type.Support)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag(AgencyTags.CARD_AREA),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier.fillMaxWidth().shadow(6.dp, shape, ambientColor = Sakura.RoseBrown.copy(alpha = .12f), spotColor = Sakura.RoseBrown.copy(alpha = .12f))
                    .background(Sakura.White, shape).border(1.dp, Sakura.Gold, shape).testTag(IdolTags.CARD + "-open"),
            ) {
                // The striped band of his colour (pink → lilac when he has none), with the company and his number.
                Box(
                    Modifier.fillMaxWidth().height(64.dp).background(
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.fill, Sakura.Lilac)), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ).testTag(IdolTags.BAND),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        stringResource(R.string.ag_idol_chip, facts.company, facts.traineeNo), style = Type.Caption.copy(color = Sakura.Text),
                        modifier = Modifier.padding(12.dp).background(Sakura.White.copy(alpha = .85f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)
                            .testTag(IdolTags.NUMBER),
                    )
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.rotate(-2f)) {
                        Box(Modifier.size(width = 120.dp, height = 156.dp).background(Sakura.White).border(1.dp, Sakura.MilkTea).padding(6.dp).padding(bottom = 18.dp)) {
                            Polaroid(agency, facts)
                        }
                        Box(Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).size(width = 48.dp, height = 14.dp).background(Sakura.Aqua.copy(alpha = .7f)))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(facts.stageName ?: stringResource(R.string.ag_card_no_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Sakura.Text)
                        KeyValue(stringResource(R.string.ag_idol_grade), stringResource(R.string.ag_idol_trainee, facts.level))
                        facts.joinDay?.let { KeyValue(stringResource(R.string.ag_idol_join), stringResource(R.string.ag_idol_join_value, it.toInt())) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.ag_idol_colour), style = Type.Support, modifier = Modifier.width(56.dp))
                            if (facts.memberColor != null) ColourDot(facts.memberColor)
                            Text(MemberColour.PRESETS.firstOrNull { it.second.equals(facts.memberColor, true) }?.first ?: if (facts.memberColor == null) stringResource(R.string.ag_idol_colour_none) else "", style = Type.Body)
                        }
                        facts.theme?.let { KeyValue(stringResource(R.string.ag_idol_week), it, Modifier.testTag(IdolTags.THEME)) }
                    }
                }
                facts.special?.let { Text(it, style = Type.Body.copy(color = Sakura.RoseBrown), modifier = Modifier.padding(horizontal = 16.dp).testTag(IdolTags.SPECIAL)) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (app.agency.core.schedule.Feature.SIGN in open) {
                        Dashed()
                        Text(stringResource(R.string.ag_idol_stickers_title), style = Type.Label)
                        StickerStrip(facts)
                    }
                    if (scores.isNotEmpty() && app.agency.core.schedule.Feature.MONTHLY_EVAL in open) {
                        Dashed()
                        Text(stringResource(R.string.ag_idol_scores_title), style = Type.Label)
                        scores.forEach { (name, v) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(name, style = Type.Support, modifier = Modifier.width(72.dp))
                                Box(Modifier.weight(1f)) { ProgressBar(v / 5f) }
                                Text("$v", style = Type.Label)
                            }
                        }
                    }
                    if (facts.levels.isNotEmpty()) {
                        Dashed()
                        Text(stringResource(R.string.ag_idol_levels_title), style = Type.Label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(IdolTags.LEVELS)) {
                            facts.levels.forEach { (sub, l) -> Pill("$sub $l") }
                        }
                    }
                    // 下一個目標 only when he set targets (F2: no target is not 「都到了」).
                    facts.nextGoalKg?.takeIf { facts.goalsSet }?.let { g ->
                        Dashed()
                        Text(stringResource(R.string.ag_idol_goal_title), style = Type.Label)
                        Text(
                            facts.goalGapKg?.let { stringResource(R.string.ag_idol_goal_value, CoachRules.kg(g), CoachRules.kg(it)) } ?: stringResource(R.string.ag_card_goal, CoachRules.kg(g)),
                            style = Type.Body, modifier = Modifier.testTag(IdolTags.GOAL),
                        )
                    }
                }
            }
            if (setupDue) {
                app.dshmobile.ui.kit.Card { IdolSetup(agency, facts, onSaved = onSaved, onSkip = { agency.settings.idolSetupSkipped = true; onSaved() }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.agency.core.schedule.Feature.CORE in open) SoftButton(stringResource(R.string.ag_idol_wall, facts.photocards), onClick = onWall, modifier = Modifier.weight(1f))
                SoftButton(stringResource(R.string.ag_ob_file), onClick = onFile, modifier = Modifier.weight(1f).testTag(app.dshmobile.ui.agency.onboarding.OnboardingTags.FILE))
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = Type.Support, modifier = Modifier.width(56.dp))
        Text(value, style = Type.Body)
    }
}

@Composable
private fun Dashed() {
    val c = Sakura.MilkTea
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}

@Composable
private fun Polaroid(agency: AgencyApp, f: IdolCardFacts) {
    if (f.photoMediaId != null) {
        Thumb(f.photoMediaId, agency.mediaVault(), Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(Sakura.Background), contentAlignment = Alignment.Center) {
            Text((f.stageName ?: f.company).take(1), fontSize = 40.sp, color = Sakura.Text)
        }
    }
}

@Composable
private fun Photo(agency: AgencyApp, f: IdolCardFacts, size: androidx.compose.ui.unit.Dp) {
    val accent = LocalAccent.current
    val desc = stringResource(R.string.ag_card_photo)
    Box(
        Modifier.size(size).border(2.5.dp, accent.fill, CircleShape).padding(3.dp).semantics { contentDescription = desc }.testTag(IdolTags.PHOTO),
        contentAlignment = Alignment.Center,
    ) {
        if (f.photoMediaId != null) {
            Thumb(f.photoMediaId, agency.mediaVault(), Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Box(Modifier.fillMaxSize().background(Sakura.Background, CircleShape), contentAlignment = Alignment.Center) {
                Text((f.stageName ?: f.company).take(1), color = Sakura.Text, fontSize = 18.sp)
            }
        }
    }
}

/** Today's 手帳 stickers: one per done item, tilted and in turn blush, aqua and milk tea; none yet says so. */
@Composable
private fun StickerStrip(f: IdolCardFacts) {
    if (f.stickers.isEmpty()) {
        Text(stringResource(R.string.ag_card_no_stickers), style = Type.Support, modifier = Modifier.testTag(IdolTags.STICKERS))
        return
    }
    val desc = stringResource(R.string.ag_card_stickers_desc, f.stickers.size)
    val tones = listOf(Sakura.Background, Sakura.AquaSoft, Sakura.MilkTeaSoft, Sakura.LilacSoft)
    FlowRow(
        Modifier.fillMaxWidth().semantics { contentDescription = desc }.testTag(IdolTags.STICKERS),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        f.stickers.forEachIndexed { i, s ->
            Text(
                "★ " + s.kind.zh, color = Sakura.Text, fontSize = 13.sp,
                modifier = Modifier.rotate(if (i % 2 == 0) -3f else 2.5f).background(tones[i % tones.size], RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp).testTag(IdolTags.STICKER + i),
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
