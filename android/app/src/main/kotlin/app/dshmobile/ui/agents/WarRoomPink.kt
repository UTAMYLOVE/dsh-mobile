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
    // 0.5.0: four parts, each number in one place (see WarOps.kt).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryStrip(input, costOf)
        AgentBoard(agents, input.samples, expanded, onExpand, costOf, stopping, onStop, onOpen, limits)
        HeroPanel(input, costOf)
        Timeline(decisions, input.log)
    }
}

// ---- 1. LIVE FEED ----------------------------------------------------------------------------------------------------


// ---- 2. Header with the mascot ---------------------------------------------------------------------------------------


// ---- 3. Hero spend and the four tiles --------------------------------------------------------------------------------



// ---- 4. THE CORE data -------------------------------------------------------------------------------------------------

internal fun coreStripsOf(samples: List<WarSample>): List<CoreStrip> {
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
internal fun PinkAgentCard(a: AgentStatus, open: Boolean, cost: Double?, stopping: Boolean, onToggle: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit) {
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
internal fun SpeedChartBody(samples: List<WarSample>, agents: List<AgentStatus>) {
    val lines = remember(samples, agents) { WarDerive.speedLines(samples, agents) }
    val change = remember(samples) { WarDerive.speedChange(samples) }
    val measurer = rememberTextMeasurer()
    Column(Modifier.padding(horizontal = 6.dp).testTag(Tags.WAR_CHART), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        if ((b / 12) % 2 == 1) {
                            // Kept inside the plot: the last band ran past the right edge as a grey block (owner, 2026-11-05).
                            val x0 = left + (right - left) * b / (n - 1).coerceAtLeast(1)
                            val w = ((right - left) * 12f / (n - 1).coerceAtLeast(1)).coerceAtMost(right - x0)
                            if (w > 0f) drawRect(War.aqua.copy(alpha = .22f), Offset(x0, top), Size(w, bottom - top))
                        }
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
                    // The value tags at the lines' ends, pushed apart so two close lines never print on top of each other.
                    val tagY = HashMap<Int, Float>()
                    lines.mapIndexedNotNull { i, l ->
                        val j = l.values.indexOfLast { it != null }
                        if (j < 0) null else i to (bottom - (l.values[j]!! / max) * (bottom - top) - 5.5f).coerceIn(0f, bottom - 11f)
                    }.sortedBy { it.second }.forEach { (i, y) ->
                        val prev = tagY.values.maxOrNull()
                        tagY[i] = if (prev != null && y < prev + 12f) prev + 12f else y
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
                            val y = tagY[i] ?: (e.y - 5.5f).coerceIn(0f, bottom)
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



// ---- 9. Decision stack -----------------------------------------------------------------------------------------------


// ---- 10. Tool detail and tail -f -------------------------------------------------------------------------------------



private val Sakura = app.dshmobile.ui.theme.Sakura

// ---- 11. Six-way action tree ----------------------------------------------------------------------------------------


// ---- 12. Start vs now and the full log --------------------------------------------------------------------------------


