package app.dshmobile.weather

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The app's own weather analysis (0.5.0, the owner: 「要自己分析全新」). Inputs: several global models (Open-Meteo: ECMWF, GFS,
 * ICON, JMA, CMA, KMA), the ECMWF ensemble (≈50 members) and the nearest CWA station's latest observation. Output: corrected
 * hourly temperatures, rain chance and amounts, and a confidence with its reasons. Pure functions; the network is elsewhere.
 */
data class Place(val name: String, val lat: Double, val lon: Double, val admin: String? = null)

/** One model's hourly series (index = hour from [ModelSet.times]); NaN where the model has no value. */
data class ModelSeries(val model: String, val temp: DoubleArray, val rain: DoubleArray) {
    override fun equals(other: Any?) = other is ModelSeries && model == other.model && temp.contentEquals(other.temp) && rain.contentEquals(other.rain)
    override fun hashCode() = model.hashCode()
}

data class ModelSet(val times: List<String>, val models: List<ModelSeries>)

/** The ensemble: members' hourly rain (mm) and temperature. */
data class Ensemble(val times: List<String>, val rain: List<DoubleArray>, val temp: List<DoubleArray>)

/** A CWA station's latest observation. */
data class Observation(val station: String, val lat: Double, val lon: Double, val time: String, val temp: Double?, val rainToday: Double?)

data class HourOutlook(
    val time: String,
    /** Bias-corrected multi-model mean (°C). */
    val temp: Double,
    /** Spread between models (°C, standard deviation). */
    val spread: Double,
    /** Chance of ≥ 0.2 mm in that hour (0..1), from the ensemble, else from the models. */
    val rainChance: Double,
    /** Median and 90th percentile rain (mm). */
    val rainMm: Double,
    val rainHighMm: Double,
)

enum class Confidence(val zh: String) { HIGH("高"), MEDIUM("中"), LOW("低") }

data class WeatherReport(
    val place: Place,
    val generatedAt: String,
    val hours: List<HourOutlook>,
    val confidence: Confidence,
    val reasons: List<String>,
    val station: Observation?,
    /** obs − model mean at the observation hour (°C), applied with decay; null without a usable station. */
    val bias: Double?,
    val modelCount: Int,
    val members: Int,
) {
    val now: HourOutlook? get() = hours.firstOrNull()
    val next24: List<HourOutlook> get() = hours.take(24)
    val high: Double? get() = next24.maxOfOrNull { it.temp }
    val low: Double? get() = next24.minOfOrNull { it.temp }

    /** 「接下來 2 小時：降雨機率 30%」 style headline. */
    fun headline(): String {
        val n = now ?: return "沒有資料"
        val soon = hours.take(3)
        val chance = soon.maxOfOrNull { it.rainChance } ?: 0.0
        val rain = when {
            chance >= .7 -> "接下來 2 小時很可能下雨（${pct(chance)}）"
            chance >= .3 -> "接下來 2 小時可能有雨（${pct(chance)}）"
            else -> "接下來 2 小時大致不會下雨（${pct(chance)}）"
        }
        return "現在約 ${n.temp.roundToInt()}°C，$rain"
    }

    /** The Markdown dsh reads (`.dshm/weather.md`): headline, confidence and why, then the next 24 hours. */
    fun markdown(): String = buildString {
        append("# 天氣分析：").append(place.name).append("\n\n")
        append("更新：").append(generatedAt).append("（台灣時間）\n\n")
        append("- ").append(headline()).append("\n")
        high?.let { h -> low?.let { l -> append("- 未來 24 小時 ${l.roundToInt()}～${h.roundToInt()}°C\n") } }
        append("- 信心：").append(confidence.zh).append("（").append(reasons.joinToString("；")).append("）\n")
        append("- 資料：$modelCount 個模式、$members 個系集成員")
        station?.let { append("，校正用測站 ${it.station}（${it.time}）") }
        append("\n\n| 時間 | 溫度 | 降雨機率 | 雨量 |\n|---|---|---|---|\n")
        next24.forEach { h ->
            append("| ${h.time.substringAfter('T')} | ${f1(h.temp)}°C | ${pct(h.rainChance)} | ")
            append(if (h.rainHighMm < 0.1) "—" else "${f1(h.rainMm)}～${f1(h.rainHighMm)} mm").append(" |\n")
        }
    }
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
internal fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

object WeatherAnalysis {
    const val RAIN_MM = 0.2
    /** A station farther than this is not used for the correction. */
    const val MAX_STATION_KM = 25.0
    /** The observed offset fades with this e-folding time (hours). */
    const val BIAS_DECAY_H = 12.0

    fun analyse(place: Place, set: ModelSet, ensemble: Ensemble?, stations: List<Observation>, nowIso: String): WeatherReport {
        val start = set.times.indexOfFirst { it >= nowIso.take(13) }.coerceAtLeast(0)
        val station = nearest(place, stations)
        // Each model's offset at the observation hour; a model far off now gets less weight later.
        val obsIndex = station?.let { o -> set.times.indexOfFirst { it.take(13) == o.time.take(13) }.takeIf { it >= 0 } } ?: start
        val offsets = set.models.associate { m -> m.model to station?.temp?.let { t -> m.temp.getOrNull(obsIndex)?.takeIf { !it.isNaN() }?.let { t - it } } }
        val weights = set.models.associate { m -> m.model to (offsets[m.model]?.let { 1.0 / (abs(it) + 0.5) } ?: 1.0) }
        val meanAtObs = weighted(set.models.mapNotNull { m -> m.temp.getOrNull(obsIndex)?.takeIf { !it.isNaN() }?.let { it to weights.getValue(m.model) } })
        val bias = station?.temp?.let { t -> meanAtObs?.let { t - it } }
        val hours = (start until set.times.size).map { i ->
            val lead = (i - obsIndex).coerceAtLeast(0).toDouble()
            val temps = set.models.mapNotNull { m -> m.temp.getOrNull(i)?.takeIf { !it.isNaN() }?.let { it to weights.getValue(m.model) } }
            val mean = weighted(temps) ?: Double.NaN
            val corrected = mean + (bias ?: 0.0) * exp(-lead / BIAS_DECAY_H)
            val spread = std(temps.map { it.first })
            val ensIndex = ensemble?.times?.indexOf(set.times[i]) ?: -1
            val members = if (ensIndex >= 0) ensemble!!.rain.mapNotNull { it.getOrNull(ensIndex)?.takeIf { v -> !v.isNaN() } } else emptyList()
            val rains = members.ifEmpty { set.models.mapNotNull { m -> m.rain.getOrNull(i)?.takeIf { !it.isNaN() } } }
            HourOutlook(
                set.times[i], corrected, spread,
                rainChance = if (rains.isEmpty()) 0.0 else rains.count { it >= RAIN_MM }.toDouble() / rains.size,
                rainMm = quantile(rains, .5), rainHighMm = quantile(rains, .9),
            )
        }.filter { !it.temp.isNaN() }
        val (confidence, reasons) = confidence(hours.take(24), set.models.size, ensemble?.rain?.size ?: 0, station, bias)
        return WeatherReport(place, nowIso, hours, confidence, reasons, station, bias, set.models.size, ensemble?.rain?.size ?: 0)
    }

    internal fun confidence(hours: List<HourOutlook>, models: Int, members: Int, station: Observation?, bias: Double?): Pair<Confidence, List<String>> {
        val reasons = ArrayList<String>()
        var score = 0
        val spread = hours.map { it.spread }.average().takeIf { !it.isNaN() } ?: 0.0
        when {
            spread < 1.0 -> { score += 2; reasons += "各模式溫度很接近（差 ${f1(spread)}°C）" }
            spread < 2.0 -> { score += 1; reasons += "各模式溫度略有差異（差 ${f1(spread)}°C）" }
            else -> reasons += "各模式溫度差很多（差 ${f1(spread)}°C）"
        }
        // Rain is confident when the chance is clearly low or clearly high, not around a coin toss.
        val unsure = hours.take(12).count { it.rainChance in 0.3..0.7 }
        when {
            unsure == 0 -> { score += 2; reasons += "下不下雨各成員看法一致" }
            unsure <= 4 -> { score += 1; reasons += "有幾個小時下不下雨說不準" }
            else -> reasons += "未來半天下雨與否分歧大"
        }
        if (members == 0) reasons += "沒有系集資料，降雨機率由各模式估"
        if (models < 3) { score -= 1; reasons += "只拿到 $models 個模式" }
        if (station != null && bias != null) {
            if (abs(bias) > 2.5) { score -= 1; reasons += "測站實測和預報差 ${f1(bias)}°C，已校正" }
            else reasons += "已用測站 ${station.station} 的實測校正"
        } else reasons += "附近沒有可用測站，未校正"
        val c = when { score >= 4 -> Confidence.HIGH; score >= 2 -> Confidence.MEDIUM; else -> Confidence.LOW }
        return c to reasons
    }

    fun nearest(place: Place, stations: List<Observation>): Observation? =
        stations.filter { it.temp != null }.minByOrNull { km(place.lat, place.lon, it.lat, it.lon) }
            ?.takeIf { km(place.lat, place.lon, it.lat, it.lon) <= MAX_STATION_KM }

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun weighted(xs: List<Pair<Double, Double>>): Double? {
        val w = xs.sumOf { it.second }
        return if (xs.isEmpty() || w <= 0) null else xs.sumOf { it.first * it.second } / w
    }

    private fun std(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m).pow(2) } / xs.size)
    }

    internal fun quantile(xs: List<Double>, q: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val pos = q * (s.size - 1)
        val lo = s[pos.toInt()]
        val hi = s[minOf(pos.toInt() + 1, s.lastIndex)]
        return lo + (hi - lo) * (pos - pos.toInt())
    }
}

/** Open-Meteo and CWA JSON into the analysis inputs. Unknown or broken parts are skipped. */
object WeatherParse {
    val MODELS = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless", "cma_grapes_global", "kma_seamless")

    private fun arr(e: JsonElement?): DoubleArray =
        (e as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: Double.NaN }?.toDoubleArray() ?: DoubleArray(0)

    private fun times(h: JsonObject): List<String> = (h["time"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    /** `/v1/forecast?models=a,b&hourly=temperature_2m,precipitation`: keys are suffixed with the model (`temperature_2m_a`). */
    fun models(json: JsonElement): ModelSet? {
        val h = (json as? JsonObject)?.get("hourly") as? JsonObject ?: return null
        val t = times(h)
        val series = MODELS.mapNotNull { m ->
            val temp = arr(h["temperature_2m_$m"])
            if (temp.isEmpty() || temp.all { it.isNaN() }) null else ModelSeries(m, temp, arr(h["precipitation_$m"]))
        }.ifEmpty {
            // a single-model answer (no suffix)
            listOfNotNull(arr(h["temperature_2m"]).takeIf { it.isNotEmpty() }?.let { ModelSeries("best_match", it, arr(h["precipitation"])) })
        }
        return if (t.isEmpty() || series.isEmpty()) null else ModelSet(t, series)
    }

    /** `ensemble-api …/v1/ensemble`: `precipitation_member01` … (and the control `precipitation`). */
    fun ensemble(json: JsonElement): Ensemble? {
        val h = (json as? JsonObject)?.get("hourly") as? JsonObject ?: return null
        val t = times(h)
        val rain = h.keys.filter { it == "precipitation" || it.startsWith("precipitation_member") }.sorted().map { arr(h[it]) }
        val temp = h.keys.filter { it == "temperature_2m" || it.startsWith("temperature_2m_member") }.sorted().map { arr(h[it]) }
        return if (t.isEmpty() || rain.isEmpty()) null else Ensemble(t, rain, temp)
    }

    /** CWA `O-A0003-001` (10-minute station observations): `records.Station[]`. */
    fun stations(json: JsonElement): List<Observation> {
        val list = (((json as? JsonObject)?.get("records") as? JsonObject)?.get("Station") as? JsonArray).orEmpty()
        return list.mapNotNull { s ->
            val o = s as? JsonObject ?: return@mapNotNull null
            fun str(e: JsonElement?, k: String) = ((e as? JsonObject)?.get(k) as? JsonPrimitive)?.content
            val geo = o["GeoInfo"] as? JsonObject
            val coord = (geo?.get("Coordinates") as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.let { cs -> cs.firstOrNull { str(it, "CoordinateName") == "WGS84" } ?: cs.firstOrNull() } ?: return@mapNotNull null
            val we = o["WeatherElement"] as? JsonObject
            fun num(v: String?) = v?.toDoubleOrNull()?.takeIf { it > -90 } // CWA writes -99 for missing
            Observation(
                station = str(o, "StationName") ?: return@mapNotNull null,
                lat = str(coord, "StationLatitude")?.toDoubleOrNull() ?: return@mapNotNull null,
                lon = str(coord, "StationLongitude")?.toDoubleOrNull() ?: return@mapNotNull null,
                time = str(o["ObsTime"], "DateTime").orEmpty(),
                temp = num(str(we, "AirTemperature")),
                rainToday = num(str(we?.get("Now"), "Precipitation")),
            )
        }
    }

    /** Open-Meteo geocoding results in Taiwan. */
    fun places(json: JsonElement): List<Place> =
        (((json as? JsonObject)?.get("results")) as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content
            Place(s("name") ?: return@mapNotNull null, s("latitude")?.toDoubleOrNull() ?: return@mapNotNull null, s("longitude")?.toDoubleOrNull() ?: return@mapNotNull null, s("admin1"))
        }
}
