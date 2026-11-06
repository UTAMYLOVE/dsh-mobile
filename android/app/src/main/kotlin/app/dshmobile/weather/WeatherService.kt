package app.dshmobile.weather

import android.content.SharedPreferences
import java.io.File
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where the HTTP answers come from; the app uses OkHttp, tests a map. Returns null on any failure. */
fun interface WeatherFetch { suspend fun get(url: String): JsonElement? }

class OkHttpWeatherFetch(private val http: OkHttpClient) : WeatherFetch {
    override suspend fun get(url: String): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "DSH-Mobile-weather").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.let(Json::parseToJsonElement)
            }
        }.getOrNull()
    }
}

/**
 * The weather the app keeps (0.5.0): the owner's places, the last report, and `.dshm/weather.md` in the workspace for dsh. The
 * CWA key comes from [cwaKey] (the vault) and is only ever put in the app's own request to opendata.cwa.gov.tw, never written
 * anywhere dsh can read.
 */
class WeatherService(
    private val fetch: WeatherFetch,
    private val prefs: SharedPreferences,
    private val workspace: File,
    private val cwaKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _report = MutableStateFlow<WeatherReport?>(null)
    val report: StateFlow<WeatherReport?> = _report.asStateFlow()
    private val _places = MutableStateFlow(loadPlaces())
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The place the card and dsh's file use: the first saved one. */
    val current: Place? get() = _places.value.firstOrNull()

    suspend fun search(name: String): List<Place> {
        if (name.isBlank()) return emptyList()
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        return fetch.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=zh&countryCode=TW")?.let(WeatherParse::places).orEmpty()
    }

    /** Makes [p] the current place (first) and keeps up to five. */
    fun choose(p: Place) {
        val next = (listOf(p) + _places.value.filter { it.name != p.name || it.lat != p.lat }).take(5)
        _places.value = next
        prefs.edit().putString(KEY, next.joinToString("\n") { "${it.name}\t${it.lat}\t${it.lon}\t${it.admin.orEmpty()}" }).apply()
    }

    fun remove(p: Place) {
        val next = _places.value.filter { it != p }
        _places.value = next
        prefs.edit().putString(KEY, next.joinToString("\n") { "${it.name}\t${it.lat}\t${it.lon}\t${it.admin.orEmpty()}" }).apply()
    }

    /** Fetches and analyses the current place; writes the Markdown for dsh. Null (and [error]) when nothing could be fetched. */
    suspend fun refresh(place: Place? = current): WeatherReport? {
        val p = place ?: return null
        val loc = "latitude=${p.lat}&longitude=${p.lon}&timezone=Asia%2FTaipei"
        val models = fetch.get(
            "https://api.open-meteo.com/v1/forecast?$loc&forecast_days=3&hourly=temperature_2m,precipitation&models=" + WeatherParse.MODELS.joinToString(","),
        )?.let(WeatherParse::models)
        if (models == null) { _error.value = "拿不到預報資料（網路或服務忙），等一下再試"; return null }
        val ens = fetch.get("https://ensemble-api.open-meteo.com/v1/ensemble?$loc&forecast_days=3&hourly=precipitation,temperature_2m&models=ecmwf_ifs025")
            ?.let(WeatherParse::ensemble)
        val stations = cwaKey()?.takeIf { it.isNotBlank() }?.let { key ->
            fetch.get("https://opendata.cwa.gov.tw/api/v1/rest/datastore/O-A0003-001?Authorization=${URLEncoder.encode(key, "UTF-8")}&format=JSON")
                ?.let(WeatherParse::stations)
        }.orEmpty()
        val now = ZonedDateTime.now(ZoneId.of("Asia/Taipei")).withNano(0)
        val iso = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val report = WeatherAnalysis.analyse(p, models, ens, stations, iso)
        _report.value = report
        _error.value = null
        runCatching {
            val f = File(workspace, FILE).apply { parentFile?.mkdirs() }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(report.markdown())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        prefs.edit().putLong(KEY_AT, clock()).apply()
        return report
    }

    /** Refreshes when the last one is older than [maxAgeMs] (the heartbeat calls this). */
    suspend fun refreshIfStale(maxAgeMs: Long = 50 * 60_000L): WeatherReport? =
        if (clock() - prefs.getLong(KEY_AT, 0) >= maxAgeMs) refresh() else _report.value

    private fun loadPlaces(): List<Place> = prefs.getString(KEY, null).orEmpty().lines().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 3) null else Place(f[0], f[1].toDoubleOrNull() ?: return@mapNotNull null, f[2].toDoubleOrNull() ?: return@mapNotNull null, f.getOrNull(3)?.ifEmpty { null })
    }

    companion object {
        /** In the workspace: what dsh reads for weather questions (the dshm-weather skill). */
        const val FILE = ".dshm/weather.md"
        const val PREFS = "dshm_weather"
        private const val KEY = "places"
        private const val KEY_AT = "refreshed_at"
    }
}
