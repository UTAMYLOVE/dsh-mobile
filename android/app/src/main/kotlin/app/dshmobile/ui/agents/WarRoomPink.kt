package app.dshmobile.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Decision
import app.dshmobile.agents.Mood
import app.dshmobile.agents.Prop
import app.dshmobile.agents.Stage
import app.dshmobile.agents.WarDerive
import app.dshmobile.agents.WarLogRow
import app.dshmobile.agents.WarSample
import app.dshmobile.agents.stageOf
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationState
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.shortDuration
import app.dshmobile.chat.shortTokens
import app.dshmobile.chat.shortUsd
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.shell.Tags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"))
private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Taipei"))

/** Everything the pink war room shows. [lead] is the lead conversation when it is open (tool calls, decisions). */
data class WarRoomInput(
    val agents: List<AgentStatus>,
    val lead: ConversationState?,
    val log: List<WarLogRow>,
    val samples: List<WarSample>,
    val todayUsd: Double?,
    val alertUsd: Double?,
    val limit: Int,
    val team: Int?,
    val nowMs: Long,
)

/**
 * The 代理人 tab as the owner-approved pink A+B+C war room (2026-11-04), top to bottom: LIVE FEED ticker, header with the spirit
 * mascot, hero spend and four tiles, THE CORE, LIVE FLOW with spirits, agent cards, speed curves, temperature and token radar,
 * decision stack, tool detail and tail -f, the six-way action tree, start vs now, and the full log. All of it is real app data.
 */
@Composable
fun WarRoomPink(
    input: WarRoomInput,
    expanded: String?,
    onExpand: (String?) -> Unit,
    costOf: (AgentStatus) -> Double?,
    stopping: Set<String>,
    onStop: (AgentStatus) -> Unit,
    onOpen: (AgentStatus) -> Unit,
    limits: String,
) {
    val agents = input.agents
    val tools = input.lead?.items.orEmpty().filterIsInstance<ChatItem.Tool>()
    val decisions = remember(tools) { WarDerive.decisions(tools) }
    val counts = remember(tools, agents) { WarDerive.counts(tools, agents) }
    val leadAgent = agents.firstOrNull { it.isLead }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ticker(input.log)
        Header(agents, input.nowMs)
        Hero(input)
        WarPanel(padding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 8.dp)) {
            Box(Modifier.padding(horizontal = 4.dp)) { WarHeader("◆ THE CORE · 訊號 → 收斂", "● LIVE", War.rose) }
            Text("6 條光流 = 6 種數據 · 球裡的點 = 主代理人", style = War.lab.copy(fontSize = 7.sp), modifier = Modifier.padding(horizontal = 4.dp))
            CoreOrb(
                strips = coreStrips(input.samples),
                spirits = flyers(agents),
                topNote = "${input.samples.lastOrNull()?.steps ?: 0} 步 · 每步一次回覆",
                bottomNote = "${agents.count { it.mood == Mood.RUNNING }} 位執行中 · ${agents.count { it.mood == Mood.DONE }} 位完成",
            )
        }
        LiveFlow(agents, decisions, leadAgent, input.log.size, onExpand, limits)
        WarPanel {
            WarHeader("◆ 代理人 AGENTS", "${agents.size} 位 · 點卡片看細節")
            agents.forEach { a ->
                PinkAgentCard(a, a.id == expanded, costOf(a), a.id in stopping, { onExpand(if (a.id == expanded) null else a.id) }, { onStop(a) }, { onOpen(a) })
            }
        }
        SpeedChart(input.samples, agents)
        // Side by side cards share one height (owner, 2026-11-05: a short card left a hole beside a tall one).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { TemperatureGauge(agents, Modifier.fillMaxHeight()) }
            Box(Modifier.weight(1.1f).fillMaxHeight()) { TokenRadar(agents, Modifier.fillMaxHeight()) }
        }
        DecisionStack(decisions, tools)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.weight(1.05f).fillMaxHeight()) { ToolDetail(tools.lastOrNull(), leadAgent, leadAgent?.let(costOf), Modifier.fillMaxHeight()) }
            Box(Modifier.weight(1f).fillMaxHeight()) { TailLog(input.log, Modifier.fillMaxHeight()) }
        }
        ActionTree(counts)
        StartVsNow(input, agents)
        FullLog(input.log)
    }
}

// ---- 1. LIVE FEED ----------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ticker(log: List<WarLogRow>) {
    val animate = LocalEffects.current.ambient && LocalPageActive.current
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(Color.White.copy(alpha = .75f), RoundedCornerShape(6.dp))
            .border(1.dp, War.line, RoundedCornerShape(6.dp)).testTag(Tags.WAR_TICKER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(horizontal = 6.dp)) { Chip("● LIVE FEED", War.pink, War.white) }
        val text = log.take(10).joinToString("   ·   ") { "${it.badge} ${it.text}" }.ifEmpty { "等第一個動作…" }
        Text(
            text, style = War.mono.copy(fontSize = 8.5.sp), maxLines = 1,
            modifier = Modifier.weight(1f).then(if (animate) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )
    }
}

// ---- 2. Header with the mascot ---------------------------------------------------------------------------------------

@Composable
private fun Header(agents: List<AgentStatus>, nowMs: Long) {
    val time = rememberWarTime()
    val busy = agents.firstOrNull { it.mood == Mood.RUNNING } ?: agents.firstOrNull { it.mood == Mood.WAITING }
    WarPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(60.dp).graphicsLayer { translationY = sin(time.longValue / 350f) * 3.dp.toPx() }) {
                val c = center
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(War.aqua.copy(alpha = .9f), Color.Transparent), c, r * 1.35f), r * 1.35f, c, alpha = .6f + .4f * sin(time.longValue / 650f))
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFF6F7), Color(0xFFF2D9DD)), c - Offset(r * .3f, r * .35f), r * 1.3f), r, c)
                drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
                val ew = 7.dp.toPx(); val eh = 17.dp.toPx()
                drawRoundRect(War.ink, Offset(c.x - ew - 4.5.dp.toPx(), c.y - eh / 2), Size(ew, eh), CornerRadius(ew / 2))
                drawRoundRect(War.ink, Offset(c.x + 4.5.dp.toPx(), c.y - eh / 2), Size(ew, eh), CornerRadius(ew / 2))
                drawOval(War.pink.copy(alpha = .7f), Offset(c.x - r * .75f, c.y + r * .25f), Size(8.dp.toPx(), 5.dp.toPx()))
                drawOval(War.pink.copy(alpha = .7f), Offset(c.x + r * .75f - 8.dp.toPx(), c.y + r * .25f), Size(8.dp.toPx(), 5.dp.toPx()))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("戰情室", style = War.zh.copy(fontSize = 18.sp))
                    Pill("v0.3.1", War.rose)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val on = busy != null
                    Box(
                        Modifier.size(7.dp).graphicsLayer { alpha = if (on && (time.longValue / 500) % 2 == 1L) .2f else 1f }
                            .background(if (on) War.aqua else War.milk, CircleShape).border(1.5.dp, Color.White, CircleShape),
                    )
                    Text((if (on) "running · " else "idle · ") + CLOCK.format(Instant.ofEpochMilli(nowMs)), style = War.mono.copy(fontWeight = FontWeight.Bold))
                }
                Pill(busy?.let { "${it.label} · ${it.action ?: it.stage.label}" } ?: "沒有代理人在工作", fill = if (busy != null) War.aqua else Color.White)
                Text("每個動作都附理由 · 自動審查看設定", style = War.mono.copy(fontSize = 8.5.sp, color = War.ink2))
            }
        }
    }
}

// ---- 3. Hero spend and the four tiles --------------------------------------------------------------------------------

@Composable
private fun Hero(input: WarRoomInput) {
    val agents = input.agents
    val spend = input.todayUsd ?: 0.0
    val alert = input.alertUsd?.takeIf { it > 0 }
    val share = alert?.let { (spend / it).coerceIn(0.0, 1.0).toFloat() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(Tags.WAR_TILES)) {
        WarPanel(
            Modifier.weight(1.35f), border = War.pink, gap = 2.dp,
            background = Brush.linearGradient(listOf(Color.White.copy(alpha = .85f), War.pink.copy(alpha = .35f))),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("今日花費", style = War.zh.copy(fontSize = 11.sp)); Pill("估算", War.rose)
            }
            Text(
                String.format(Locale.US, "%.2f", spend),
                style = War.num(38.sp, War.rose).copy(shadow = Shadow(Color.White, Offset(0f, 2f), 12f)), maxLines = 1,
            )
            Text("US$" + (alert?.let { " · 警戒 ${String.format(Locale.US, "%.2f", it)} 的 ${((share ?: 0f) * 100).toInt()}%" } ?: ""), style = War.mono.copy(fontSize = 8.sp, color = War.ink2))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .8f))) {
                Box(Modifier.fillMaxWidth(share ?: 0f).height(5.dp).background(Brush.horizontalGradient(listOf(War.aqua, War.pink))))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tile("完成", agents.count { it.mood == Mood.DONE }.toString(), "今天", Modifier.weight(1f))
                Tile("執行中", agents.count { it.mood == Mood.RUNNING }.toString(), "/ ${input.limit}" + (input.team?.let { " · 團隊 $it" } ?: ""), Modifier.weight(1f), War.aqua)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tile("待批", agents.count { it.mood == Mood.WAITING }.toString(), "錯誤 ${agents.count { it.mood == Mood.ERROR }}", Modifier.weight(1f), War.pink, War.rose)
                val speed = input.samples.lastOrNull()?.tokensPerSecond?.takeIf { it > 0 } ?: agents.firstOrNull { it.isLead }?.usage?.tokensPerSecond?.toFloat()
                Tile("速度", speed?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "tok/s", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, note: String, modifier: Modifier, border: Color = War.line, color: Color = War.ink) {
    WarPanel(modifier, border = border, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp), gap = 0.dp) {
        Text(label, style = War.lab.copy(fontSize = 7.sp))
        Text(value, style = War.num(19.sp, color), maxLines = 1)
        Text(note, style = War.mono.copy(fontSize = 7.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- 4. THE CORE data -------------------------------------------------------------------------------------------------

private fun coreStrips(samples: List<WarSample>): List<CoreStrip> {
    val last = samples.lastOrNull()
    fun s(f: (WarSample) -> Float) = samples.takeLast(20).map(f)
    return listOf(
        CoreStrip("速度", "tok/s · 平均", last?.tokensPerSecond?.let { String.format(Locale.US, "%.0f", it) } ?: "—", s { it.tokensPerSecond }, War.aqua),
        CoreStrip("入 TOKEN", "上下文 · 累計", shortTokens(last?.inputTokens ?: 0), s { it.inputTokens.toFloat() }, War.pink),
        CoreStrip("出 TOKEN", "寫出的字", shortTokens(last?.outputTokens ?: 0), s { it.outputTokens.toFloat() }, War.milk),
        CoreStrip("快取命中", "省下的錢", last?.let { "${(it.cacheHit * 100).toInt()}%" } ?: "—", s { it.cacheHit }, War.aqua),
        CoreStrip("排隊", "等著處理的訊息", (last?.queued ?: 0).toString(), s { it.queued.toFloat() }, War.pink),
        CoreStrip("步驟", "今天走了幾步", (last?.steps ?: 0).toString(), s { it.steps.toFloat() }, War.milk),
    )
}

// ---- 5. LIVE FLOW -------------------------------------------------------------------------------------------------

@Composable
private fun LiveFlow(agents: List<AgentStatus>, decisions: List<Decision>, lead: AgentStatus?, logSize: Int, onExpand: (String?) -> Unit, limits: String) {
    WarPanel {
        WarHeader("◆ LIVE FLOW · 規劃 → 搜尋 → 派出 → 撰寫 → 審查 → 完成", null)
        if (limits.isNotEmpty()) Text(limits, style = War.lab.copy(fontSize = 7.sp), maxLines = 2)
        if (agents.isEmpty()) {
            Text("還沒有代理人在工作。開一個對話叫它做事，小精靈就會出來。", style = War.mono.copy(color = War.ink2), modifier = Modifier.testTag(Tags.AGENTS_EMPTY))
        }
        val active = agents.filter { it.mood == Mood.RUNNING || it.mood == Mood.WAITING }.take(4)
        if (active.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                active.forEach { a ->
                    val c = moodColor(a.mood)
                    Column(
                        Modifier.weight(1f).background(Color.White.copy(alpha = .8f), RoundedCornerShape(8.dp)).border(1.5.dp, c, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onExpand(a.id) }.padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = War.zh.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(Modifier.size(6.dp).background(c, CircleShape).border(1.dp, Color.White, CircleShape))
                        }
                        Text(a.action ?: a.stage.label, style = War.mono.copy(fontSize = 7.5.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        SpiritFlow(agents, onTap = { onExpand(it.id) }, height = 190.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniTile("▲ 已擋下", decisions.count { it.kind == Decision.Kind.BLOCKED }.toString(), "自動審查", War.pink, Modifier.weight(1f))
            MiniTile("◆ 首字", lead?.usage?.averageTtftMs?.let { shortDuration(it) } ?: "—", "平均", War.milk, Modifier.weight(1f))
            MiniTile("● 紀錄", "#$logSize", "附理由", War.aqua, Modifier.weight(1f))
            MiniTile("◇ 上下文", lead?.contextFill?.let { "${(it * 100).toInt()}%" } ?: "—", "主代理人", War.milk, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun MiniTile(label: String, value: String, note: String, border: Color, modifier: Modifier) {
    Column(
        modifier.background(Color.White.copy(alpha = .7f), RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = War.lab.copy(fontSize = 7.sp), maxLines = 1)
        Text(value, style = War.num(14.sp), maxLines = 1)
        Text(note, style = War.mono.copy(fontSize = 7.sp, color = War.ink2), maxLines = 1)
    }
}

// ---- 6. Agent cards -------------------------------------------------------------------------------------------------

private fun iconOf(a: AgentStatus) = when {
    a.mood == Mood.WAITING -> WarIcons.wait
    a.mood == Mood.DONE -> WarIcons.done
    a.isLead -> WarIcons.plan
    a.prop == Prop.LENS -> WarIcons.scan
    a.prop == Prop.PAGE -> WarIcons.page
    a.prop == Prop.PEN -> WarIcons.write
    a.prop == Prop.SPLIT -> WarIcons.split
    a.stage == Stage.REVIEW -> WarIcons.check
    else -> WarIcons.plan
}

/** The one accent line of a card (the mockup's 「rule」): what this agent is held to right now. */
internal fun ruleOf(a: AgentStatus): String = when {
    a.error != null -> "出錯了：" + a.error.take(40)
    a.mood == Mood.WAITING -> a.waiting ?: "等你回覆"
    a.mood == Mood.DONE -> "已交回" + if (a.isLead) "你" else "主代理人"
    a.isLead -> "拆任務、派工、收結果"
    a.stage == Stage.SEARCH -> "只讀，不寫檔"
    a.stage == Stage.WRITE -> "寫進工作區"
    a.stage == Stage.DISPATCH -> "再派子代理人分工"
    a.stage == Stage.REVIEW -> "獨立檢查，不同意就退回"
    else -> "思考中"
}

@Composable
private fun PinkAgentCard(a: AgentStatus, open: Boolean, cost: Double?, stopping: Boolean, onToggle: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit) {
    val c = moodColor(a.mood)
    val live = a.mood == Mood.RUNNING
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .then(if (live) Modifier.border(3.dp, War.aqua.copy(alpha = .45f), shape) else Modifier)
            .background(if (live) Brush.verticalGradient(listOf(War.aqua.copy(alpha = .45f), Color.White.copy(alpha = .85f))) else Brush.verticalGradient(listOf(Color.White.copy(alpha = .78f), Color.White.copy(alpha = .7f))), shape)
            .border(1.5.dp, if (live) War.aqua else War.pink.copy(alpha = .45f), shape)
            .clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 10.dp, vertical = 9.dp).testTag(Tags.WAR_CARD + a.id),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icon = iconOf(a)
            Canvas(Modifier.size(30.dp).background(c, RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp))) {
                val k = 16.dp.toPx() / 18f
                translate((size.width - 16.dp.toPx()) / 2, (size.height - 16.dp.toPx()) / 2) {
                    scale(k, k, pivot = Offset.Zero) { drawPath(icon, War.ink, style = Stroke(1.8f, cap = StrokeCap.Round)) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(a.label, style = War.zh.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (a.isLead) "主代理人" else "子代理人 · 第 ${a.depth} 層") + (a.model?.let { " · $it" } ?: "") + (a.effort?.let { " · 思考 $it" } ?: ""),
                    style = War.mono.copy(fontSize = 8.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Pill(badgeOf(a), fill = c)
        }
        Text(ruleOf(a), style = War.zh.copy(fontSize = 10.5.sp, color = if (c == War.aqua) Color(0xFF5E8F96) else War.rose), maxLines = 2, overflow = TextOverflow.Ellipsis)
        a.action?.let { Text("› $it", style = War.mono, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        a.lastLine?.let { Text(it, style = War.mono.copy(color = War.ink2), maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis) }
        val u = a.usage
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                shortTokens(u.totalTokens) + " tok" + (a.activeMs?.let { " · " + shortDuration(it) } ?: "") + (cost?.let { " · " + shortUsd(it) } ?: ""),
                style = War.mono.copy(fontSize = 7.5.sp, color = War.ink2), maxLines = 1,
            )
            val total = (u.cacheReadTokens + u.inputTokens + u.outputTokens).toFloat()
            Row(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .9f))) {
                if (total > 0f) {
                    listOf(u.cacheReadTokens to War.milk, u.inputTokens to War.pink, u.outputTokens to War.aqua).forEach { (n, col) ->
                        if (n > 0) Box(Modifier.weight(n / total).fillMaxHeight().background(col))
                    }
                }
            }
        }
        if (open) {
            Column(Modifier.testTag(Tags.WAR_DETAIL + a.id), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Detail("狀態", listOfNotNull(stateWord(a.mood), a.waiting).joinToString(" · "))
                Detail("步驟", "${u.turns} 輪 · ${u.steps} 步")
                Detail("時間", a.activeMs?.let { shortDuration(it) } ?: "—")
                Detail("Token", "入 ${shortTokens(u.inputTokens + u.cacheReadTokens)}（快取 ${shortTokens(u.cacheReadTokens)}）· 出 ${shortTokens(u.outputTokens)}")
                Detail("速度", u.tokensPerSecond?.let { String.format(Locale.US, "%.0f tok/s", it) } ?: "—")
                Detail("首字", u.averageTtftMs?.let { shortDuration(it) } ?: "—")
                Detail("花費", cost?.let { "≈ " + shortUsd(it) } ?: "—")
                Detail("上下文", a.contextFill?.let { "${(it * 100).toInt()}%" } ?: "—")
                Detail("排隊", if (a.queued > 0) "${a.queued} 則" else "沒有")
                a.error?.let { Detail("錯誤", it) }
                if (a.recent.isNotEmpty()) {
                    Text("最近動作", style = War.lab, modifier = Modifier.padding(top = 4.dp))
                    a.recent.forEach { Text("· $it", style = War.mono.copy(fontSize = 8.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FogButton(if (a.isLead) "打開對話" else "打開來源對話", FogButtonKind.GHOST, onOpen, Modifier.testTag(Tags.WAR_OPEN + a.id))
                    if (!a.isLead && a.mood == Mood.RUNNING) {
                        FogButton(if (stopping) "停止中…" else "叫它停", FogButtonKind.AQUA, onStop, Modifier.testTag(Tags.AGENT_STOP + a.id), enabled = !stopping)
                    }
                }
            }
        }
    }
}

private fun stateWord(m: Mood) = when (m) {
    Mood.RUNNING -> "工作中"
    Mood.WAITING -> "等待中"
    Mood.DONE -> "完成"
    Mood.ERROR -> "出錯"
    Mood.IDLE -> "閒置"
}

@Composable
private fun Detail(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, style = War.lab, modifier = Modifier.width(44.dp))
        Text(v, style = War.mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---- 7. Speed curves ------------------------------------------------------------------------------------------------

private val LINE_COLORS = listOf(War.pink, War.aqua, War.rose)

@Composable
private fun SpeedChart(samples: List<WarSample>, agents: List<AgentStatus>) {
    val lines = remember(samples, agents) { WarDerive.speedLines(samples, agents) }
    val change = remember(samples) { WarDerive.speedChange(samples) }
    val measurer = rememberTextMeasurer()
    WarPanel(gap = 3.dp, modifier = Modifier.testTag(Tags.WAR_CHART)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SPEED · 執行中的代理人", style = War.mono.copy(fontSize = 8.sp, color = War.rose, fontWeight = FontWeight.Bold))
                Text("各代理人速度 tok/s", style = War.zh.copy(fontSize = 13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEachIndexed { i, l -> Text("━ ", style = War.mono.copy(fontSize = 7.5.sp, color = LINE_COLORS[i]), maxLines = 1); Text(l.label.take(6), style = War.mono.copy(fontSize = 7.5.sp), maxLines = 1) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(change?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—", style = War.num(16.sp, War.rose))
                Text("比 50 秒前", style = War.zh.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Medium, color = War.ink2))
                Text("● 近 ${samples.size * 5 / 60} 分鐘", style = War.mono.copy(fontSize = 7.sp))
            }
        }
        if (samples.size < 2 || lines.isEmpty()) {
            Text("收集中…每 5 秒記一點，代理人開始工作後就有曲線。", style = War.mono.copy(color = War.ink2), modifier = Modifier.padding(vertical = 18.dp))
        } else {
            Canvas(Modifier.fillMaxWidth().aspectRatio(360f / 150f).semantics { contentDescription = "速度曲線：" + lines.joinToString { it.label } }) {
                val k = size.width / 360f
                scale(k, k, pivot = Offset.Zero) {
                    val left = 24f; val right = 318f; val top = 10f; val bottom = 130f
                    val n = samples.size
                    val max = (lines.flatMap { it.values.filterNotNull() }.maxOrNull() ?: 1f).coerceAtLeast(10f) * 1.15f
                    var b = 0
                    while (b < n) {
                        if ((b / 12) % 2 == 1) drawRect(War.aqua.copy(alpha = .22f), Offset(left + (right - left) * b / (n - 1).coerceAtLeast(1), 0f), Size((right - left) * 12f / (n - 1).coerceAtLeast(1), bottom))
                        b += 12
                    }
                    for (g in 0..3) {
                        val y = top + (bottom - top) * g / 3
                        drawLine(War.pink.copy(alpha = .45f), Offset(left, y), Offset(350f, y), 1f)
                        val lab = measurer.measure(String.format(Locale.US, "%.0f", max * (3 - g) / 3), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.sp, color = War.ink2), density = DESIGN_UNITS)
                        drawText(lab, topLeft = Offset(0f, y - 5f))
                    }
                    listOf(samples.first(), samples.last()).forEachIndexed { i, s ->
                        val lab = measurer.measure(HM.format(Instant.ofEpochMilli(s.atMs)), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.sp, color = War.ink2), density = DESIGN_UNITS)
                        drawText(lab, topLeft = Offset(if (i == 0) left else right - lab.size.width, bottom + 6f))
                    }
                    lines.asReversed().forEachIndexed { ri, l ->
                        val i = lines.size - 1 - ri
                        val path = Path()
                        var started = false
                        var end: Offset? = null
                        l.values.forEachIndexed { j, v ->
                            if (v == null) return@forEachIndexed
                            val p = Offset(left + (right - left) * j / (n - 1), bottom - (v / max) * (bottom - top))
                            if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
                            end = p
                        }
                        if (i == 0) {
                            val area = Path().apply { addPath(path); end?.let { lineTo(it.x, bottom) }; lineTo(left, bottom); close() }
                            drawPath(area, War.pink.copy(alpha = .18f))
                        }
                        drawPath(path, LINE_COLORS[i], style = Stroke(if (i == 0) 2.4f else 2f))
                        end?.let { e ->
                            if (i == 0) { drawCircle(Color.White, 4f, e); drawCircle(War.pink, 4f, e, style = Stroke(2f)) }
                            val v = l.values.lastOrNull { it != null } ?: 0f
                            val tag = measurer.measure(String.format(Locale.US, "%.0f", v), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = War.ink), density = DESIGN_UNITS)
                            val y = (e.y - 5.5f + i * 2f).coerceIn(0f, bottom)
                            drawRoundRect(LINE_COLORS[i], Offset(322f, y), Size(36f, 11f), CornerRadius(2f))
                            drawText(tag, topLeft = Offset(325f, y + .5f))
                        }
                    }
                }
            }
        }
    }
}

// ---- 8. Temperature gauge and token radar ----------------------------------------------------------------------------

@Composable
private fun TemperatureGauge(agents: List<AgentStatus>, modifier: Modifier = Modifier) {
    val temp = remember(agents) { WarDerive.temperature(agents) }
    WarPanel(gap = 3.dp, modifier = modifier) {
        Text("1. 代理人溫度", style = War.zh.copy(fontSize = 11.sp))
        val word = when {
            temp == null -> "沒人在工作"
            temp.error > .2f -> "有錯誤 · 要看一下"
            temp.waiting > .3f -> "在等你 · 卡住了"
            else -> "順暢 · 熱度升溫"
        }
        Text(word, style = War.zh.copy(fontSize = 12.sp, color = War.rose))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("工作中比例", style = War.zh.copy(fontSize = 8.sp, fontWeight = FontWeight.Medium, color = War.ink2), modifier = Modifier.weight(1f))
            Text(temp?.let { String.format(Locale.US, "%.1f%%", it.running * 100) } ?: "—", style = War.num(17.sp))
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(150f / 62f)) {
            val k = size.width / 150f
            scale(k, k, pivot = Offset.Zero) {
                val tl = Offset(20f, 3f); val sz = Size(110f, 110f)
                drawArc(Color.White, 180f, 180f, false, tl, sz, style = Stroke(10f))
                var start = 180f
                val parts = if (temp == null) emptyList() else listOf(temp.error to War.pink, temp.waiting to War.milk, temp.running to War.aqua)
                parts.forEach { (f, col) -> if (f > 0f) { drawArc(col, start, 180f * f, false, tl, sz, style = Stroke(10f)); start += 180f * f } }
                val ang = Math.toRadians(180.0 + 180.0 * (temp?.let { it.error + it.waiting + it.running } ?: 0f))
                val c = Offset(75f, 58f)
                drawLine(War.ink, c, c + Offset((kotlin.math.cos(ang) * 46).toFloat(), (kotlin.math.sin(ang) * 46).toFloat()), 2.2f, StrokeCap.Round)
                drawCircle(War.ink, 4.5f, c)
            }
        }
        listOf(Triple("執行", temp?.running, War.aqua), Triple("等待", temp?.waiting, War.milk), Triple("錯誤", temp?.error, War.pink)).forEach { (k, v, col) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(k, style = War.zh.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Medium), modifier = Modifier.width(24.dp))
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) {
                    Box(Modifier.fillMaxWidth(v ?: 0f).height(5.dp).background(col))
                }
                Text(v?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—", style = War.mono.copy(fontSize = 7.sp), modifier = Modifier.width(28.dp))
            }
        }
    }
}

@Composable
private fun TokenRadar(agents: List<AgentStatus>, modifier: Modifier = Modifier) {
    val rank = remember(agents) { WarDerive.ranking(agents) }
    val max = rank.firstOrNull()?.second?.toFloat() ?: 1f
    WarPanel(gap = 4.dp, modifier = modifier) {
        Text("2. token 熱度雷達", style = War.zh.copy(fontSize = 11.sp))
        if (rank.isEmpty()) Text("還沒有用量", style = War.mono.copy(color = War.ink2))
        rank.forEachIndexed { i, (a, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(String.format(Locale.US, "%02d", i + 1), style = War.mono.copy(fontSize = 7.sp, color = War.ink2))
                Text(a.label, style = War.zh.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) {
                    Box(Modifier.fillMaxWidth(n / max).height(7.dp).background(Brush.horizontalGradient(listOf(War.pink, War.milk, War.aqua))))
                }
                Text(shortTokens(n), style = War.mono.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ---- 9. Decision stack -----------------------------------------------------------------------------------------------

@Composable
private fun DecisionStack(decisions: List<Decision>, tools: List<ChatItem.Tool>) {
    val all = remember(tools) { WarDerive.decisions(tools, max = Int.MAX_VALUE) }
    WarPanel(gap = 4.dp, modifier = Modifier.testTag(Tags.WAR_STACK)) {
        WarHeader(
            "◆ 決策堆疊 DECISION STACK",
            "放行 ${all.count { it.kind == Decision.Kind.ALLOWED }} · 擋下 ${all.count { it.kind == Decision.Kind.BLOCKED }} · 等你 ${all.count { it.kind == Decision.Kind.WAITING }}",
        )
        if (decisions.isEmpty()) Text("打開一個對話，這裡會列出每次工具呼叫是放行、擋下還是等你。", style = War.mono.copy(color = War.ink2))
        decisions.forEach { d ->
            val col = when (d.kind) { Decision.Kind.ALLOWED -> War.aqua; Decision.Kind.WAITING -> War.pink; Decision.Kind.BLOCKED -> War.pink; Decision.Kind.FAILED -> War.rose }
            val hot = d.kind == Decision.Kind.BLOCKED || d.kind == Decision.Kind.WAITING
            Row(
                Modifier.fillMaxWidth().then(
                    if (hot) Modifier.background(War.pink.copy(alpha = .28f), RoundedCornerShape(6.dp)).border(1.5.dp, War.pink, RoundedCornerShape(6.dp))
                    else Modifier.drawBehind { drawLine(War.pink.copy(alpha = .35f), Offset(0f, size.height), Offset(size.width, size.height), 1f) },
                ).padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(d.kind.label, col)
                Column(Modifier.weight(1f)) {
                    Text(d.text, style = War.mono.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.why, style = War.mono.copy(fontSize = 7.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) {
                    Box(Modifier.fillMaxWidth(d.risk).height(4.dp).background(col))
                }
                Text(String.format(Locale.US, "%.2f", d.risk), style = War.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ---- 10. Tool detail and tail -f -------------------------------------------------------------------------------------

@Composable
private fun ToolDetail(t: ChatItem.Tool?, lead: AgentStatus?, cost: Double?, modifier: Modifier = Modifier) {
    WarPanel(gap = 4.dp, modifier = modifier) {
        WarHeader("工具 · 輸入 vs 結果", null)
        if (t == null) { Text("還沒有工具呼叫", style = War.mono.copy(color = War.ink2)); return@WarPanel }
        val (stage, _, _) = stageOf(t.name, t.summary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t.name, style = War.zh.copy(fontSize = 13.sp), maxLines = 1)
            Pill(stage.label, War.rose)
            Pill(when (t.status) { ToolStatus.RUNNING -> "open"; ToolStatus.WAITING_APPROVAL -> "wait"; ToolStatus.DONE -> "done"; ToolStatus.FAILED -> "fail" }, fill = War.aqua)
        }
        Text(t.summary, style = War.mono.copy(color = War.rose, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val u = lead?.usage
        Detail("代理人", lead?.label ?: "—")
        Detail("模型", listOfNotNull(lead?.model, lead?.effort).joinToString(" · ").ifEmpty { "—" })
        Detail("入/出", u?.let { shortTokens(it.inputTokens + it.cacheReadTokens) + " / " + shortTokens(it.outputTokens) } ?: "—")
        Detail("快取", u?.cacheHit?.let { "${(it * 100).toInt()}%" } ?: "—")
        Detail("花費", cost?.let { shortUsd(it) } ?: "—")
        val why = WarDerive.decisions(listOf(t)).first().why
        Text("reason: $why", style = War.mono.copy(fontSize = 8.sp), maxLines = 2)
    }
}

@Composable
private fun TailLog(log: List<WarLogRow>, modifier: Modifier = Modifier) {
    val time = rememberWarTime()
    WarPanel(border = War.ink, background = Brush.verticalGradient(listOf(War.ink.copy(alpha = .93f), War.ink.copy(alpha = .9f))), gap = 3.dp, modifier = modifier.testTag(Tags.WAR_LOG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$ tail -f 戰情.log", style = War.ttl.copy(color = Sakura.Background), modifier = Modifier.weight(1f))
            Box(Modifier.size(5.dp, 9.dp).graphicsLayer { alpha = if ((time.longValue / 500) % 2 == 1L) 0f else 1f }.background(War.aqua))
        }
        if (log.isEmpty()) Text("等第一個動作…", style = War.mono.copy(color = War.faint))
        log.take(8).asReversed().forEach { r ->
            Column {
                Text(
                    HM.format(Instant.ofEpochMilli(r.atMs)) + " " + r.badge + " " + r.text,
                    style = War.mono.copy(fontSize = 8.5.sp, color = Sakura.Background), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (r.why.isNotEmpty()) Text("  " + r.why, style = War.mono.copy(fontSize = 7.5.sp, color = War.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val Sakura = app.dshmobile.ui.theme.Sakura

// ---- 11. Six-way action tree ----------------------------------------------------------------------------------------

@Composable
private fun ActionTree(c: app.dshmobile.agents.ActionCounts) {
    val time = rememberWarTime()
    WarPanel {
        WarHeader("◆ 六類動作 · 觀察進 → 決策出", "主對話")
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            val xs = List(6) { w * (1 + 2 * it) / 12f }
            val mid = 10.dp.toPx()
            drawLine(War.pink, Offset(w / 2, 0f), Offset(w / 2, mid), 1.2.dp.toPx())
            drawLine(War.pink, Offset(xs.first(), mid), Offset(xs.last(), mid), 1.2.dp.toPx())
            xs.forEach { drawLine(War.pink, Offset(it, mid), Offset(it, size.height), 1.2.dp.toPx()) }
            drawLine(
                War.rose, Offset(xs.first(), mid), Offset(xs.last(), mid), 2.dp.toPx(), StrokeCap.Round,
                PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 8.dp.toPx()), -(time.longValue / 40f) % (9.5.dp.toPx())),
            )
        }
        Row {
            listOf(
                Triple("搜尋", c.search, WarIcons.eye to War.aqua), Triple("讀取", c.read, WarIcons.page to War.pink),
                Triple("派出", c.dispatch, WarIcons.split to War.milk), Triple("撰寫", c.write, WarIcons.write to War.aqua),
                Triple("擋下", c.blocked, WarIcons.shield to War.pink), Triple("審查", c.review, WarIcons.check to War.milk),
            ).forEach { (name, n, ic) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Canvas(Modifier.size(34.dp).background(ic.second, RoundedCornerShape(9.dp)).border(2.dp, Color.White, RoundedCornerShape(9.dp))) {
                        val k = 18.dp.toPx() / 18f
                        translate((size.width - 18.dp.toPx()) / 2, (size.height - 18.dp.toPx()) / 2) {
                            scale(k, k, pivot = Offset.Zero) { drawPath(ic.first, War.ink, style = Stroke(1.6f, cap = StrokeCap.Round)) }
                        }
                    }
                    Text(name, style = War.zh.copy(fontSize = 9.sp))
                    Text(n.toString(), style = War.num(12.sp))
                }
            }
        }
    }
}

// ---- 12. Start vs now and the full log --------------------------------------------------------------------------------

@Composable
private fun StartVsNow(input: WarRoomInput, agents: List<AgentStatus>) {
    val spend = input.todayUsd ?: 0.0
    val share = input.alertUsd?.takeIf { it > 0 }?.let { (spend / it).coerceIn(0.0, 1.0).toFloat() } ?: 0f
    WarPanel {
        WarHeader("結果 · 開始 vs 現在", "今天 00:00 → 現在")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("開始", style = War.lab, modifier = Modifier.width(32.dp))
            Box(Modifier.weight(1f).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
            Text("US$0.00", style = War.num(10.sp, War.ink2))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("現在", style = War.lab, modifier = Modifier.width(32.dp))
            BoxWithConstraints(Modifier.weight(1f).height(12.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth(share).height(3.dp).background(War.aqua, RoundedCornerShape(2.dp)))
                Box(Modifier.offset(x = (maxWidth - 11.dp) * share).size(11.dp).background(War.aqua, CircleShape).border(2.dp, Color.White, CircleShape))
            }
            Text(String.format(Locale.US, "US$%.2f", spend), style = War.num(10.sp))
        }
        Text(
            "${agents.count { it.mood == Mood.DONE }} 完成 / ${agents.count { it.mood == Mood.ERROR }} 錯誤 / ${agents.count { it.mood == Mood.WAITING }} 待批",
            style = War.mono.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun FullLog(log: List<WarLogRow>) {
    WarPanel(padding = androidx.compose.foundation.layout.PaddingValues(0.dp), gap = 0.dp) {
        Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)) { WarHeader("讀紀錄 · 每一行都有理由", "${log.size} 行") }
        if (log.isEmpty()) Text("還沒有紀錄", style = War.mono.copy(color = War.ink2), modifier = Modifier.padding(10.dp))
        log.take(14).forEachIndexed { i, r ->
            val col = when (r.badge) { "錯誤", "擋下", "待批" -> War.pink; "完成" -> War.milk; else -> War.aqua }
            Row(
                Modifier.fillMaxWidth().then(if (i == 0) Modifier.background(Brush.horizontalGradient(listOf(War.aqua.copy(alpha = .55f), Color.Transparent))) else Modifier)
                    .drawBehind {
                        drawLine(War.pink.copy(alpha = .35f), Offset(0f, 0f), Offset(size.width, 0f), 1f)
                        if (i == 0) drawRect(War.aqua, size = Size(4.dp.toPx(), size.height))
                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(HM.format(Instant.ofEpochMilli(r.atMs)), style = War.mono.copy(color = War.ink2), modifier = Modifier.width(34.dp))
                Chip(r.badge, col)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.text, style = War.zh.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.why.isNotEmpty()) Text("reason: " + r.why, style = War.mono.copy(fontSize = 7.5.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
