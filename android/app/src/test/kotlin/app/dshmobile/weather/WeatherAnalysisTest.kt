package app.dshmobile.weather

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherAnalysisTest {
    private val taipei = Place("台北", 25.03, 121.56)
    private val times = (0 until 24).map { "2026-11-06T%02d:00".format(it) }

    private fun forecastJson(): String {
        val t = times.joinToString(",") { "\"$it\"" }
        fun series(base: Double) = (0 until 24).joinToString(",") { "%.1f".format(java.util.Locale.US, base + it * 0.1) }
        val rain = (0 until 24).joinToString(",") { if (it in 10..12) "1.5" else "0" }
        return """{"hourly":{"time":[$t],
            "temperature_2m_ecmwf_ifs025":[${series(20.0)}],"precipitation_ecmwf_ifs025":[$rain],
            "temperature_2m_gfs_seamless":[${series(21.0)}],"precipitation_gfs_seamless":[$rain],
            "temperature_2m_icon_seamless":[${series(20.5)}],"precipitation_icon_seamless":[$rain]}}"""
    }

    private fun ensembleJson(): String {
        val t = times.joinToString(",") { "\"$it\"" }
        // 10 members; in hour 11, 8 of them rain
        val members = (1..10).joinToString(",") { m ->
            val r = (0 until 24).joinToString(",") { h -> if (h == 11 && m <= 8) "2.0" else "0" }
            "\"precipitation_member%02d\":[$r]".format(m)
        }
        return """{"hourly":{"time":[$t],$members}}"""
    }

    private val cwa = """{"records":{"Station":[
        {"StationName":"臺北","ObsTime":{"DateTime":"2026-11-06T09:00:00+08:00"},"GeoInfo":{"Coordinates":[{"CoordinateName":"TWD67","StationLatitude":"25.0","StationLongitude":"121.5"},{"CoordinateName":"WGS84","StationLatitude":"25.0377","StationLongitude":"121.5149"}]},"WeatherElement":{"AirTemperature":"23.0","Now":{"Precipitation":"0.0"}}},
        {"StationName":"花蓮","ObsTime":{"DateTime":"2026-11-06T09:00:00+08:00"},"GeoInfo":{"Coordinates":[{"CoordinateName":"WGS84","StationLatitude":"23.97","StationLongitude":"121.6"}]},"WeatherElement":{"AirTemperature":"-99","Now":{"Precipitation":"-99"}}}]}}"""

    @Test fun parsesModelsEnsembleAndStations() {
        val set = WeatherParse.models(Json.parseToJsonElement(forecastJson()))!!
        assertEquals(listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"), set.models.map { it.model })
        assertEquals(10, WeatherParse.ensemble(Json.parseToJsonElement(ensembleJson()))!!.rain.size)
        val st = WeatherParse.stations(Json.parseToJsonElement(cwa))
        assertEquals(25.0377, st[0].lat, 1e-6)
        assertNull("-99 is missing", st[1].temp)
    }

    @Test fun stationCorrectsTheMeanAndTheCorrectionFades() {
        val set = WeatherParse.models(Json.parseToJsonElement(forecastJson()))!!
        val st = WeatherParse.stations(Json.parseToJsonElement(cwa))
        val r = WeatherAnalysis.analyse(taipei, set, null, st, "2026-11-06T09:00")
        assertEquals("臺北", r.station?.station)
        val raw9 = listOf(20.9, 21.9, 21.4).average()
        assertTrue("bias ~ +1.6: ${r.bias}", r.bias!! in 1.4..1.8)
        assertEquals(23.0, r.now!!.temp, 0.05) // the observation hour is the observation
        val later = r.hours.last()
        assertTrue("fades: ${later.temp}", later.temp - (raw9 + 1.4) < r.bias!!)
    }

    @Test fun ensembleGivesTheRainChance() {
        val set = WeatherParse.models(Json.parseToJsonElement(forecastJson()))!!
        val ens = WeatherParse.ensemble(Json.parseToJsonElement(ensembleJson()))!!
        val r = WeatherAnalysis.analyse(taipei, set, ens, emptyList(), "2026-11-06T09:00")
        assertEquals(0.8, r.hours.first { it.time.endsWith("11:00") }.rainChance, 1e-9)
        assertEquals(0.0, r.hours.first { it.time.endsWith("15:00") }.rainChance, 1e-9)
        assertTrue(r.reasons.any { it.contains("沒有可用測站") })
        assertEquals(10, r.members)
    }

    @Test fun confidenceDropsWhenModelsDisagree() {
        val close = List(24) { HourOutlook("t$it", 20.0, 0.4, 0.0, 0.0, 0.0) }
        val apart = List(24) { HourOutlook("t$it", 20.0, 3.0, 0.5, 1.0, 3.0) }
        val obs = Observation("臺北", 25.0, 121.5, "", 20.0, 0.0)
        assertEquals(Confidence.HIGH, WeatherAnalysis.confidence(close, 6, 50, obs, 0.3).first)
        assertEquals(Confidence.LOW, WeatherAnalysis.confidence(apart, 2, 0, null, null).first)
    }

    @Test fun refreshWritesTheFileForDshAndKeepsTheKeyInTheCwaRequestOnly() = runBlocking {
        val ws = Files.createTempDirectory("ws").toFile()
        val asked = mutableListOf<String>()
        val fetch = WeatherFetch { url ->
            asked += url
            Json.parseToJsonElement(
                when {
                    url.contains("geocoding-api") -> """{"results":[{"name":"板橋區","latitude":25.01,"longitude":121.46,"admin1":"新北市"}]}"""
                    url.contains("ensemble-api") -> ensembleJson()
                    url.contains("api.open-meteo.com") -> forecastJson()
                    url.contains("opendata.cwa.gov.tw") -> cwa
                    else -> """{"results":[{"name":"板橋區","latitude":25.01,"longitude":121.46,"admin1":"新北市"}]}"""
                },
            ) as JsonElement
        }
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("w-test", 0)
        val svc = WeatherService(fetch, prefs, ws, cwaKey = { "CWA-SECRET-KEY-123" })
        val found = svc.search("板橋")
        assertEquals("新北市", found.single().admin)
        svc.choose(found.single())
        val r = svc.refresh()!!
        val md = File(ws, WeatherService.FILE).readText()
        assertTrue(md.startsWith("# 天氣分析：板橋區"))
        assertTrue(md.contains("信心："))
        assertFalse("the key never reaches dsh's file", md.contains("SECRET"))
        assertEquals(1, asked.count { it.contains("SECRET") })
        assertTrue(asked.single { it.contains("SECRET") }.startsWith("https://opendata.cwa.gov.tw/"))
        assertEquals(r.place, WeatherService(fetch, prefs, ws, { null }).places.value.first())
    }
}
