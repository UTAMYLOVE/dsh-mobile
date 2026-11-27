package app.dshmobile.ui.warroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.engine.IdolCardFacts
import app.agency.core.engine.RoleActivity
import app.agency.core.engine.SlotStatus
import app.agency.core.persona.Roles
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WarTags {
    const val STICKERS = "ag-war-stickers"
    const val STICKER = "ag-war-sticker-"
    const val FRAME = "ag-war-frame"
    const val ROLE = "ag-war-role-"
    const val RIBBON = "ag-war-ribbon"
    const val LINKS = "ag-war-links"
}

/**
 * 指揮室 (T20): a pink backstage 手帳, not a control panel — washi tape on top, today's sticker sheet (one sticker per item done,
 * an empty frame for each still owed, 違規 written plainly), the role cards of 小葵姐 and the body teacher with what each is
 * doing now, the spend ribbon (today against his cap), then 今天的連動, the memos and the photocard wall. No clock with
 * seconds, no monospace, no radar.
 */
@Composable
fun WarRoomScreen(ready: StartState.Ready, todayUsd: () -> Double, capUsd: () -> Double?) {
    val revision by ready.brain.revision.collectOnMain()
    val busy by ready.brain.busy.collectOnMain()
    val fail by ready.brain.lastFail.collectOnMain()
    val card by produceState<IdolCardFacts?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.card() }.getOrNull() } }
    }
    Column(
        Modifier.fillMaxSize().background(Sakura.Background).verticalScroll(rememberScrollState()).padding(16.dp).testTag(AgencyTags.WAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Washi()
        Text(stringResource(R.string.ag_war_title), color = Sakura.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        card?.let { c ->
            StickerSheet(c)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleCard("葵", stringResource(R.string.ag_war_manager), RoleActivity.manager(c, busy, fail != null), Sakura.Primary, Modifier.weight(1f), Roles.MANAGER.id)
                RoleCard("體", stringResource(R.string.ag_war_body), RoleActivity.body(c, busy, fail != null), Sakura.Aqua, Modifier.weight(1f), app.agency.core.model.RoleKey.BODY.name)
            }
        }
        SpendRibbon(todayUsd(), capUsd())
        Box(Modifier.testTag(WarTags.LINKS)) { TodayLinksCard(ready) }
        StaffCard()
        MemoCard(ready)
        PhotoWallCard(ready)
    }
}

/** A strip of pink washi tape (striped), the 手帳 look. */
@Composable
private fun Washi() {
    Canvas(Modifier.fillMaxWidth().height(14.dp).rotate(-1.5f)) {
        drawRect(Color(0xFFF8C8D4))
        var x = 0f
        while (x < size.width) {
            drawRect(Color(0xFFFFE4EC), topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(10.dp.toPx(), size.height))
            x += 22.dp.toPx()
        }
    }
}

/** Today's sticker sheet: one sticker per done item, an empty frame for each item still owed, breaches written plainly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerSheet(c: IdolCardFacts) {
    val owed = c.slots.count { it.slot.required && it.status != SlotStatus.DONE }
    Column(
        Modifier.fillMaxWidth().background(Sakura.White, RoundedCornerShape(20.dp)).padding(14.dp).testTag(WarTags.STICKERS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ag_war_stickers, c.stickers.size, owed), color = Sakura.Text, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            c.stickers.forEachIndexed { i, s ->
                Text(
                    "✿ ${s.kind.zh}", color = Sakura.Text, fontSize = 13.sp,
                    modifier = Modifier.rotate(if (i % 2 == 0) -3f else 3f).background(Color(0xFFFFD6E2), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag(WarTags.STICKER + i),
                )
            }
            repeat(owed) {
                Box(Modifier.size(width = 52.dp, height = 26.dp).border(1.dp, Sakura.Primary.copy(alpha = .5f), RoundedCornerShape(12.dp)).testTag(WarTags.FRAME))
            }
        }
        if (c.breachesToday > 0) Text(stringResource(R.string.ag_war_breaches, c.breachesToday), color = Sakura.RoseBrown, fontSize = 14.sp)
    }
}

/** A role card: an avatar circle in the member colour, the name, what she is doing now (code's line). */
@Composable
private fun RoleCard(initial: String, name: String, now: String, color: Color, modifier: Modifier, id: String) {
    Column(
        modifier.background(Sakura.White, RoundedCornerShape(20.dp)).padding(12.dp).testTag(WarTags.ROLE + id),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = .35f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(name, color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(now, color = Sakura.TextSecondary, fontSize = 13.sp)
    }
}

/** Today's spend as a ribbon against his cap (dollars, two decimals; no cap: just the amount). */
@Composable
private fun SpendRibbon(usd: Double, cap: Double?) {
    fun money(x: Double) = (Math.round(x * 100) / 100.0).toString()
    val share = cap?.takeIf { it > 0 }?.let { (usd / it).coerceIn(0.0, 1.0).toFloat() }
    Column(Modifier.fillMaxWidth().testTag(WarTags.RIBBON), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            cap?.let { stringResource(R.string.ag_war_spend_cap, money(usd), money(it)) } ?: stringResource(R.string.ag_war_spend, money(usd)),
            color = Sakura.Text, fontSize = 14.sp,
        )
        share?.let { s ->
            Box(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFFFFE4EC), RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxWidth(s).height(12.dp).background(Sakura.Primary, RoundedCornerShape(6.dp)))
            }
        }
    }
}
