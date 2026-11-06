package app.dshmobile.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Decision
import app.dshmobile.agents.Mood
import app.dshmobile.agents.WarLogRow
import app.dshmobile.agents.WarSample
import app.dshmobile.chat.shortTokens
import app.dshmobile.ui.shell.Tags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The war room, redesigned (0.5.0; the owner: 「太簡陋、頁面太長、資訊分太開、太重複」; layout after agent-display / AGENT OPS,
 * colors ours): four parts, each number in one place only.
 *   1. the summary strip — the only place totals appear;
 *   2. the agent board — one rich row per agent (name, stage, speed sparkline, tokens, cost; tap for details and stop);
 *   3. the hero — the core orb with the agents flying in it, and one chart that switches 速度 / 用量 / 花費;
 *   4. the timeline — decisions, tools and the log in one list, with filter chips.
 */

private val HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"))

object WarOpsTags {
    const val FILTER = "war-filter-"
    const val HERO_MODE = "war-hero-"
    const val ROW = "war-line-"
}

/** 1. Counts by state, then speed, tokens and today's spend; the clock. */
@Composable
internal fun SummaryStrip(input: WarRoomInput, costOf: (AgentStatus) -> Double?) {
    val a = input.agents
    val running = a.count { it.mood == Mood.RUNNING }
    val waiting = a.count { it.mood == Mood.WAITING }
    val done = a.count { it.mood == Mood.DONE || it.mood == Mood.IDLE }
    val failed = a.count { it.mood == Mood.ERROR }
    val speed = input.samples.lastOrNull()?.tokensPerSecond
    val tokens = a.sumOf { it.usage.totalTokens }
    val spent = a.mapNotNull(costOf).takeIf { it.isNotEmpty() }?.sum()
    WarPanel(modifier = Modifier.testTag(Tags.WAR_TILES)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AGENT OPS · 戰情室", style = War.ttl.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
            Text("● ${HHMMSS.format(Instant.ofEpochMilli(input.nowMs))}", style = War.num(11.sp, War.rose))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Count("執行", running, War.aqua, Modifier.weight(1f))
            Count("等你", waiting, War.pink, Modifier.weight(1f))
            Count("完成", done, War.milk, Modifier.weight(1f))
            Count("錯誤", failed, War.rose, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("速度 tok/s", speed?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f))
            Stat("用量 tok", shortTokens(tokens), Modifier.weight(1f))
            val today = input.todayUsd
            Stat(input.alertUsd?.let { String.format(Locale.US, "今日／上限 $%.0f", it) } ?: "今日花費", today?.let { String.format(Locale.US, "$%.2f", it) } ?: "—", Modifier.weight(1.3f))
            Stat("這次", spent?.let { if (it < .01) String.format(Locale.US, "$%.4f", it) else String.format(Locale.US, "$%.2f", it) } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Count(label: String, n: Int, color: Color, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .7f)).border(1.dp, color, RoundedCornerShape(10.dp))
            .semantics(mergeDescendants = true) { contentDescription = "$label $n" }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = War.zh.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal), maxLines = 1, modifier = Modifier.weight(1f))
        Text("$n", style = War.num(15.sp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, style = War.zh.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = War.num(15.sp), maxLines = 1)
    }
}

/** 2. One rich row per agent; the open one shows its details (and 叫它停). */
@Composable
internal fun AgentBoard(
    agents: List<AgentStatus>, samples: List<WarSample>, expanded: String?, onExpand: (String?) -> Unit,
    costOf: (AgentStatus) -> Double?, stopping: Set<String>, onStop: (AgentStatus) -> Unit, onOpen: (AgentStatus) -> Unit, limits: String,
) {
    WarPanel(modifier = Modifier.testTag(Tags.WAR_FLOW)) {
        WarHeader("◆ 代理人 AGENTS", "${agents.size} 位 · 點一下看細節")
        if (limits.isNotBlank()) Text(limits, style = War.lab)
        if (agents.isEmpty()) Text("現在沒有代理人在工作。", style = War.zh.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = War.ink2))
        agents.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PinkAgentCard(a, a.id == expanded, costOf(a), a.id in stopping, { onExpand(if (a.id == expanded) null else a.id) }, { onStop(a) }, { onOpen(a) })
                val series = samples.mapNotNull { it.perAgent[a.id] }
                if (series.size >= 2 && a.id != expanded) Sparkline(series, Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 10.dp))
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val max = values.max().coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height * (1 - v / max)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, War.aqua, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

enum class HeroMode(val zh: String) { SPEED("速度"), TOKENS("用量"), COST("花費") }

/** 3. The core orb, and one chart that switches between speed (curves), tokens and cost (bars per agent). */
@Composable
internal fun HeroPanel(input: WarRoomInput, costOf: (AgentStatus) -> Double?) {
    var mode by rememberSaveable { mutableStateOf(HeroMode.SPEED) }
    val agents = input.agents
    WarPanel(padding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 8.dp)) {
        Box(Modifier.padding(horizontal = 4.dp)) { WarHeader("◆ THE CORE", "${agents.count { it.mood == Mood.RUNNING }} 位執行中", War.rose) }
        CoreOrb(
            strips = coreStripsOf(input.samples),
            spirits = flyers(agents),
            topNote = "${input.samples.lastOrNull()?.steps ?: 0} 步",
            bottomNote = "點下面切換：速度・用量・花費",
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeroMode.values().forEach { m ->
                val on = m == mode
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(if (on) War.pink else Color.White.copy(alpha = .7f))
                        .border(1.dp, War.pink, RoundedCornerShape(50)).clickable(role = Role.Tab) { mode = m }.testTag(WarOpsTags.HERO_MODE + m.name),
                    contentAlignment = Alignment.Center,
                ) { Text(m.zh, style = War.zh.copy(fontSize = 13.sp, color = if (on) Color.White else War.ink)) }
            }
        }
        when (mode) {
            HeroMode.SPEED -> SpeedChartBody(input.samples, agents)
            HeroMode.TOKENS -> Bars(agents.map { it.label to it.usage.totalTokens.toDouble() }) { shortTokens(it.toLong()) + " tok" }
            HeroMode.COST -> Bars(agents.map { it.label to (costOf(it) ?: 0.0) }) { String.format(Locale.US, "$%.3f", it) }
        }
    }
}

@Composable
private fun Bars(rows: List<Pair<String, Double>>, label: (Double) -> String) {
    val max = rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag(Tags.WAR_CHART), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.sortedByDescending { it.second }.forEach { (name, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = War.zh.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = .8f))) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(War.pink))
                }
                Text(label(v), style = War.num(10.sp), maxLines = 1)
            }
        }
    }
}

/** One line of the timeline: a decision about a tool call, or a log row. */
data class TimelineRow(val atMs: Long?, val kind: Decision.Kind?, val badge: String, val text: String, val why: String)

enum class TimelineFilter(val zh: String) { ALL("全部"), ALLOWED("放行"), BLOCKED("擋下"), WAITING("等你"), FAILED("錯誤") }

/** Decisions (newest tool calls) first, then the log, newest first; one list. */
fun timelineRows(decisions: List<Decision>, log: List<WarLogRow>): List<TimelineRow> =
    decisions.map { TimelineRow(null, it.kind, it.kind.label, it.text, it.why) } +
        log.asReversed().map { r ->
            val k = when (r.badge) { "擋下" -> Decision.Kind.BLOCKED; "錯誤", "失敗" -> Decision.Kind.FAILED; "等你" -> Decision.Kind.WAITING; else -> null }
            TimelineRow(r.atMs, k, r.badge, r.text, r.why)
        }

fun filtered(rows: List<TimelineRow>, f: TimelineFilter): List<TimelineRow> = when (f) {
    TimelineFilter.ALL -> rows
    TimelineFilter.ALLOWED -> rows.filter { it.kind == Decision.Kind.ALLOWED }
    TimelineFilter.BLOCKED -> rows.filter { it.kind == Decision.Kind.BLOCKED }
    TimelineFilter.WAITING -> rows.filter { it.kind == Decision.Kind.WAITING }
    TimelineFilter.FAILED -> rows.filter { it.kind == Decision.Kind.FAILED }
}

/** 4. The timeline with its filter chips. */
@Composable
internal fun Timeline(decisions: List<Decision>, log: List<WarLogRow>) {
    var filter by rememberSaveable { mutableStateOf(TimelineFilter.ALL) }
    val rows = remember(decisions, log) { timelineRows(decisions, log) }
    val shown = filtered(rows, filter).take(40)
    WarPanel(gap = 6.dp, modifier = Modifier.testTag(Tags.WAR_STACK)) {
        WarHeader("◆ 時間軸 TIMELINE", "${rows.size} 筆")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimelineFilter.values().forEach { f ->
                val on = f == filter
                val n = filtered(rows, f).size
                Box(
                    Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(if (on) War.aqua else Color.White.copy(alpha = .7f))
                        .border(1.dp, if (on) War.aqua else War.line, RoundedCornerShape(50)).clickable(role = Role.Tab) { filter = f }
                        .testTag(WarOpsTags.FILTER + f.name).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("${f.zh} $n", style = War.zh.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)) }
            }
        }
        Column(Modifier.testTag(Tags.WAR_LOG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.isEmpty()) Text("這裡還沒有東西。", style = War.zh.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = War.ink2))
            shown.forEachIndexed { i, r ->
                val color = when (r.kind) {
                    Decision.Kind.BLOCKED -> War.rose; Decision.Kind.FAILED -> War.rose; Decision.Kind.WAITING -> War.pink
                    Decision.Kind.ALLOWED -> War.aqua; null -> War.milk
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .6f)).testTag(WarOpsTags.ROW + i)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(r.atMs?.let { HHMMSS.format(Instant.ofEpochMilli(it)) } ?: "現在", style = War.num(10.sp, War.ink2), modifier = Modifier.width(54.dp))
                    Text(
                        r.badge, style = War.zh.copy(fontSize = 10.sp, color = if (color == War.rose) Color.White else War.ink),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = War.zh.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (r.why.isNotBlank()) Text(r.why, style = War.zh.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

