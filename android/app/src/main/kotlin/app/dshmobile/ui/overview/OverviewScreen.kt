package app.dshmobile.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.SessionRow
import app.dshmobile.overview.OverviewState
import app.dshmobile.ui.fog.CountUpText
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.HudRing
import app.dshmobile.ui.fog.Meter
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Taipei: ZoneId = ZoneId.of("Asia/Taipei")
private val CellShape = RoundedCornerShape(16.dp)
private const val MAX_DOTS = 16

private fun usd2(v: Double) = String.format(Locale.US, "%.2f", v)

/**
 * The overview page (the mockup's page 0): the day's spend as a HUD ring (DeepSeek and Claude together against the alert, then
 * each on its own and the month), running agents against the limit (tap: the agents tab), the next heartbeat, the heartbeat's
 * memory reminders and the latest conversations (tap: open it). Money is the app's own estimate. Built for 412 dp: the three cost cells
 * share the width and shrink their digits instead of wrapping; the page's bottom padding ([LocalPagePadding]) keeps the last card above the dock.
 * Animated values are read while drawing ([CountUpText], [HudRing], [Meter]), so nothing recomposes per frame.
 */
@Composable
fun OverviewScreen(
    state: OverviewState, onOpenSession: (String) -> Unit, onOpenAgents: () -> Unit, warRoom: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.unverifiedDsh?.let { Banner(it) }
        SpendPanel(state)
        warRoom()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentsPanel(state, onOpenAgents, Modifier.weight(1f).fillMaxHeight())
            HeartbeatPanel(state, Modifier.weight(1f).fillMaxHeight())
        }
        StatsPanel(state)
        RemindersPanel(state)
        RecentPanel(state, onOpenSession)
    }
}

/** Today per model (requests, tokens, cache hit, spend) and dsh's live speed and first-token time (stage 2 §C.3). */
@Composable
private fun StatsPanel(s: OverviewState) {
    if (s.models.isEmpty() && s.live.isEmpty) return
    FogPanel(
        title = stringResource(R.string.overview_stats_title), en = "STATS", index = 2,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(Tags.OVERVIEW_STATS),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val live = s.live
            if (!live.isEmpty) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.overview_stats_speed), live.tokensPerSecond?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "tok/s", Modifier.weight(1f))
                    StatTile(stringResource(R.string.overview_stats_ttft), live.averageTtftMs?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "—", "秒", Modifier.weight(1f))
                    StatTile(stringResource(R.string.overview_stats_cache), live.cacheHit?.let { "${(it * 100).toInt()}" } ?: "—", "%", Modifier.weight(1f))
                    StatTile(stringResource(R.string.overview_stats_steps), live.steps.toString(), "", Modifier.weight(1f))
                }
            }
            s.models.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(modelLabel(m.model) ?: m.model, color = Sakura.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.overview_stats_model_line, m.requests, app.dshmobile.chat.shortTokens(m.inputTokens + m.cacheReadTokens),
                                app.dshmobile.chat.shortTokens(m.outputTokens), ((m.cacheHit ?: 0.0) * 100).toInt(),
                            ),
                            color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 2,
                        )
                    }
                    Text(app.dshmobile.chat.shortUsd(m.usd), style = FogText.Number.copy(fontSize = 16.sp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.background(Sakura.White.copy(alpha = .4f), CellShape).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = FogText.Label.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = FogText.Number.copy(fontSize = 18.sp), maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", style = FogText.Label.copy(fontSize = 10.sp), maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun Banner(version: String) {
    Text(
        stringResource(R.string.overview_unverified, version),
        color = Sakura.Text, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().testTag(Tags.UNVERIFIED_BANNER)
            .background(Sakura.White.copy(alpha = .55f), CellShape).border(1.dp, Sakura.Primary, CellShape).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun SpendPanel(s: OverviewState) {
    val total = s.deepseekUsd + s.claudeUsd
    val alert = s.alertUsd
    val alertText = if (alert % 1.0 == 0.0) alert.toLong().toString() else alert.toString()
    val description = stringResource(R.string.overview_ring_desc, String.format(Locale.US, "%.4f", total))
    FogPanel(
        title = stringResource(R.string.overview_spend_title), en = "TODAY",
        meta = stringResource(R.string.overview_spend_meta, alertText), index = 0,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HudRing(
                progress = if (alert > 0.0) minOf(1.0, total / alert).toFloat() else 0f,
                size = 240.dp,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description; testTag = Tags.OVERVIEW_RING },
            ) {
                Column(Modifier.size(150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.overview_spent_today), style = FogText.Label.copy(letterSpacing = 0.3.sp), maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("US$", style = FogText.Label.copy(fontSize = 14.sp), modifier = Modifier.padding(bottom = 7.dp), maxLines = 1)
                        CountUpText(total, ::usd2, FogText.Number.copy(fontSize = if (total >= 100) 34.sp else 44.sp))
                    }
                    Text(
                        stringResource(R.string.overview_alert_line, alertText), style = FogText.Label.copy(fontSize = 10.sp),
                        textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        Box(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(Tags.OVERVIEW_SPLIT),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SplitCell(stringResource(R.string.overview_deepseek), s.deepseekUsd, Modifier.weight(1f))
            SplitCell(stringResource(R.string.overview_claude), s.claudeUsd, Modifier.weight(1f))
            SplitCell(stringResource(R.string.overview_month), s.monthUsd, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SplitCell(label: String, value: Double, modifier: Modifier) {
    val text = usd2(value)
    // Digits shrink with the length instead of wrapping: a third of 412 dp leaves about 85 dp for the number.
    val size = when {
        text.length <= 5 -> 20.sp
        text.length <= 7 -> 17.sp
        text.length <= 9 -> 14.sp
        else -> 12.sp
    }
    Column(
        modifier.background(Sakura.White.copy(alpha = .4f), CellShape).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = FogText.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CountUpText(value, ::usd2, FogText.Number.copy(fontSize = size))
    }
}

@Composable
private fun AgentsPanel(s: OverviewState, onOpen: () -> Unit, modifier: Modifier) {
    val running = s.agentsRunning
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FogText.Number.fontFamily, fontSize = 30.sp, color = Sakura.Text)) { append(running?.toString() ?: "—") }
        withStyle(SpanStyle(fontSize = 12.sp, color = Sakura.TextSecondary)) { append(" " + stringResource(R.string.overview_agents_rest, s.agentsLimit)) }
    }
    FogPanel(
        title = stringResource(R.string.overview_agents_title), index = 1,
        modifier = modifier.testTag(Tags.OVERVIEW_AGENTS).clickable(role = Role.Button, onClick = onOpen),
    ) {
        Text(text, maxLines = 2)
        Box(Modifier.height(12.dp))
        AgentDots(running ?: 0, s.agentsLimit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentDots(running: Int, limit: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(limit.coerceIn(0, MAX_DOTS)) { i ->
            val on = i < running
            val shape = RoundedCornerShape(2.dp)
            Box(
                Modifier.size(10.dp).then(if (on) Modifier.glow(Sakura.Aqua, 6.dp, shape) else Modifier)
                    .background(if (on) Sakura.Aqua else Sakura.RoseBrown.copy(alpha = .25f), shape),
            )
        }
    }
}

@Composable
private fun HeartbeatPanel(s: OverviewState, modifier: Modifier) {
    val minutes = s.nextHeartbeatMin
    val progress = s.heartbeatProgress
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FogText.Number.fontFamily, fontSize = 30.sp, color = Sakura.Text)) { append(minutes?.toString() ?: "—") }
        withStyle(SpanStyle(fontSize = 12.sp, color = Sakura.TextSecondary)) {
            append(" " + stringResource(if (minutes != null) R.string.overview_heartbeat_rest else R.string.overview_heartbeat_none))
        }
    }
    FogPanel(
        title = stringResource(R.string.overview_heartbeat_title), ice = true, index = 2,
        modifier = modifier.semantics(mergeDescendants = true) {}.testTag(Tags.OVERVIEW_HEARTBEAT),
    ) {
        Text(text, maxLines = 2)
        Box(Modifier.height(12.dp))
        Meter(
            progress ?: 0f, ice = true,
            modifier = if (progress != null) Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) } else Modifier,
        )
    }
}

@Composable
private fun RemindersPanel(s: OverviewState) {
    FogPanel(
        title = stringResource(R.string.overview_remind_title), en = "REMIND", ice = true, index = 3,
        meta = stringResource(R.string.overview_remind_meta, s.reminders.size),
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(Tags.OVERVIEW_REMINDERS),
    ) {
        if (s.reminders.isEmpty()) {
            Text(stringResource(R.string.overview_remind_empty), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.reminders.forEach { r ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.title, color = Sakura.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.body.isNotBlank()) Text(r.body, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RecentPanel(s: OverviewState, onOpen: (String) -> Unit) {
    FogPanel(title = stringResource(R.string.overview_recent_title), index = 4) {
        if (s.recent.isEmpty()) {
            Text(stringResource(R.string.overview_recent_empty), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        s.recent.forEach { row -> RecentRow(row, s.nowMs, onOpen) }
    }
}

@Composable
private fun RecentRow(row: SessionRow, nowMs: Long, onOpen: (String) -> Unit) {
    val yesterday = stringResource(R.string.overview_yesterday)
    val title = row.title.ifBlank { stringResource(R.string.overview_recent_untitled) }
    val whenText = whenLabel(row.updatedAt, nowMs, yesterday)
    val sub = listOfNotNull(whenText, modelLabel(row.nextModel ?: row.model)).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(row.id) }.testTag(Tags.OVERVIEW_RECENT + row.id),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val dot = RoundedCornerShape(50)
        Box(Modifier.size(8.dp).glow(Sakura.Primary, 6.dp, dot).background(Sakura.Primary, dot))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Sakura.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Sakura.TextSecondary, fontSize = 18.sp)
    }
}

/** HH:mm for today (Taipei), 「昨天」 for yesterday, M/d before that; null when the time is unknown. */
private fun whenLabel(updatedAt: Long, nowMs: Long, yesterday: String): String? {
    if (updatedAt <= 0L) return null
    val day = Instant.ofEpochMilli(updatedAt).atZone(Taipei)
    val today = Instant.ofEpochMilli(nowMs).atZone(Taipei).toLocalDate()
    return when (day.toLocalDate()) {
        today -> DateTimeFormatter.ofPattern("HH:mm").format(day)
        today.minusDays(1) -> yesterday
        else -> DateTimeFormatter.ofPattern("M/d").format(day)
    }
}

private fun modelLabel(model: String?): String? = when {
    model.isNullOrBlank() -> null
    model.contains("claude", ignoreCase = true) -> "Claude"
    model.contains("deepseek", ignoreCase = true) -> "DeepSeek"
    else -> model
}
