package app.dshmobile.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.theme.Sakura
import app.dshmobile.weather.Confidence
import app.dshmobile.weather.HourOutlook
import app.dshmobile.weather.Place
import app.dshmobile.weather.WeatherReport
import app.dshmobile.weather.WeatherService
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

object WeatherTags {
    const val CARD = "weather-card"
    const val PLACE = "weather-place"
    const val SEARCH = "weather-search"
    const val RESULT = "weather-result-"
    const val REFRESH = "weather-refresh"
    const val CHART = "weather-chart"
}

/** The overview's weather card: the place, now, the next 24 hours (temperature line + rain-chance bars) and the confidence. */
@Composable
fun WeatherCard(service: WeatherService) {
    val report by service.report.collectAsState()
    val places by service.places.collectAsState()
    val error by service.error.collectAsState()
    val scope = rememberCoroutineScope()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val current = places.firstOrNull()
    LaunchedEffect(current) {
        if (current != null && report?.place != current) { busy = true; service.refresh(); busy = false }
    }
    FogPanel(title = "天氣分析", en = "WEATHER", meta = current?.name, index = 1, modifier = Modifier.fillMaxWidth().testTag(WeatherTags.CARD)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current == null || choosing) {
                PlacePicker(service, places, onPicked = { p -> service.choose(p); choosing = false }, onCancel = if (current != null) ({ choosing = false }) else null)
            } else {
                val r = report
                if (r != null && r.place == current) Report(r) else Text(if (busy) "分析中…" else error ?: "還沒有資料", color = Sakura.TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { choosing = true }, modifier = Modifier.testTag(WeatherTags.PLACE)) { Text("換地點", color = Sakura.Text, fontSize = 13.sp) }
                    TextButton(
                        onClick = { scope.launch { busy = true; service.refresh(); busy = false } }, enabled = !busy,
                        modifier = Modifier.testTag(WeatherTags.REFRESH),
                    ) { Text(if (busy) "更新中…" else "重新分析", color = Sakura.Text, fontSize = 13.sp) }
                    if (error != null && r != null) Text(error.orEmpty(), color = Sakura.RoseBrown, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Report(r: WeatherReport) {
    val now = r.now ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${now.temp.roundToInt()}°", color = Sakura.Text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, fontFamily = FogFonts.ChakraPetch)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            r.low?.let { l -> r.high?.let { h -> Text("今明 ${l.roundToInt()}～${h.roundToInt()}°C", color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold) } }
            Text(r.headline().substringAfter("，"), color = Sakura.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        ConfidenceBadge(r.confidence)
    }
    HourChart(r.next24)
    Text("信心${r.confidence.zh}：" + r.reasons.joinToString("；"), color = Sakura.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    Text(
        "${r.modelCount} 個模式・${r.members} 個系集成員" + (r.station?.let { "・測站 ${it.station}" } ?: "・未設定氣象署金鑰，沒有測站校正"),
        color = Sakura.RoseBrown, fontSize = 11.sp,
    )
}

@Composable
private fun ConfidenceBadge(c: Confidence) {
    val (bg, fg) = when (c) {
        Confidence.HIGH -> Sakura.Aqua to Sakura.Text
        Confidence.MEDIUM -> Sakura.MilkTea to Sakura.Text
        Confidence.LOW -> Sakura.RoseBrown to Color.White
    }
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text("信心 ${c.zh}", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** 24 hours: rain-chance bars (aqua) under a temperature line (pink); hour labels every 6 hours are Text, not canvas text. */
@Composable
private fun HourChart(hours: List<HourOutlook>) {
    if (hours.size < 2) return
    val lo = hours.minOf { it.temp } - 1
    val hi = hours.maxOf { it.temp } + 1
    val desc = "未來 24 小時，最低 ${hours.minOf { it.temp }.roundToInt()} 度，最高 ${hours.maxOf { it.temp }.roundToInt()} 度，最高降雨機率 ${(hours.maxOf { it.rainChance } * 100).roundToInt()}%"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).testTag(WeatherTags.CHART).semantics { contentDescription = desc }) {
            val n = hours.size
            val step = size.width / n
            hours.forEachIndexed { i, h ->
                val bh = (size.height * 0.45f * h.rainChance.toFloat())
                if (bh > 0) drawRect(Sakura.Aqua.copy(alpha = .75f), Offset(i * step + step * .15f, size.height - bh), Size(step * .7f, bh))
            }
            val path = Path()
            hours.forEachIndexed { i, h ->
                val x = i * step + step / 2
                val y = (size.height * .55f) * (1 - ((h.temp - lo) / (hi - lo)).toFloat()) + 4f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Sakura.Primary, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18).forEach { i ->
                hours.getOrNull(i)?.let { h ->
                    Text("${h.time.substringAfter('T').take(2)}時 ${h.temp.roundToInt()}°", color = Sakura.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlacePicker(service: WeatherService, saved: List<Place>, onPicked: (Place) -> Unit, onCancel: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("輸入地名（鄉鎮市區或地標），例如「板橋」「台中西屯」。", color = Sakura.TextSecondary, fontSize = 12.sp)
    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("地點") }, modifier = Modifier.fillMaxWidth().testTag(WeatherTags.SEARCH))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { scope.launch { searching = true; results = service.search(query); searching = false } }, enabled = query.isNotBlank() && !searching) {
            Text(if (searching) "找…" else "搜尋", color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        onCancel?.let { TextButton(onClick = it) { Text("取消", color = Sakura.Text, fontSize = 13.sp) } }
    }
    (results.ifEmpty { saved }).forEachIndexed { i, p ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Sakura.Background.copy(alpha = .6f))
                .border(1.dp, Sakura.Primary.copy(alpha = .3f), RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { onPicked(p) }.testTag(WeatherTags.RESULT + i).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.name, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            p.admin?.let { Text(it, color = Sakura.TextSecondary, fontSize = 12.sp) }
        }
    }
    if (!searching && results.isEmpty() && query.isNotBlank()) Text("（按搜尋找地點）", color = Sakura.TextSecondary, fontSize = 11.sp)
}
