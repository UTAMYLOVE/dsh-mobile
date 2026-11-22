package app.dshmobile.agency.migrate

import app.agency.core.migrate.LegacyCoachJson
import app.agency.core.migrate.LegacyPrefs
import app.dshmobile.coach.CoachJson
import app.dshmobile.coach.CoachProfile
import app.dshmobile.coach.CoachProvider
import app.dshmobile.coach.CoachSettings
import app.dshmobile.coach.SlotTimes
import app.dshmobile.gateway.InMemorySharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The importer's fixtures are what the coach itself wrote: each state file is byte for byte what the coach's encoder writes
 * for the records it holds (v0.8.3's encoder is the 5a94d3c one without `trainings`, `baseline`, `breaches` and `zeroCal`,
 * see `git diff 97eaa54 5a94d3c -- …/coach/CoachJson.kt`), each message line is the encoder's line, and the prefs keys the
 * importer reads are the ones the coach's settings write.
 */
class LegacyFixturesTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/legacy/$name")) { name }.readText()

    private fun reencode(text: String) = CoachJson.encode(CoachJson.decode(Json.parseToJsonElement(text)))

    /** The v0.8.3 encoder's output for the same state. */
    private fun asV083(o: JsonObject): String = JsonObject(
        o.filterKeys { it != "trainings" && it != "baseline" }.mapValues { (k, v) ->
            if (k != "meals") v else JsonArray((v as JsonArray).map { m ->
                JsonObject((m as JsonObject).filterKeys { it != "breaches" }.mapValues { (mk, mv) ->
                    if (mk != "items") mv else JsonArray((mv as JsonArray).map { i -> JsonObject((i as JsonObject).filterKeys { it != "zeroCal" }) })
                })
            })
        },
    ).toString()

    @Test fun theAgencyStateIsWhatThe5a94d3cEncoderWrites() {
        for (name in listOf("agency-v2-state.json", "tmp-only/state.json.tmp")) {
            val text = fixture(name)
            assertEquals(name, text, reencode(text).toString())
        }
    }

    @Test fun theV083StateIsWhatTheV083EncoderWrites() {
        val text = fixture("v083-state.json")
        assertEquals(text, asV083(reencode(text)))
        // …and nothing of the agency build is in it.
        val o = Json.parseToJsonElement(text) as JsonObject
        assertNull(o["trainings"])
        assertNull(o["baseline"])
        assertEquals(1, (o["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test fun theUpgradedStateIsWhatThe5a94d3cEncoderWritesOverAV083File() {
        // 5a94d3c installed over v0.8.3 keeps the loaded version 1 and writes every meal's breaches and the trainings.
        val text = fixture("agency-v1-upgraded-state.json")
        assertEquals(text, reencode(text).toString())
        val o = Json.parseToJsonElement(text) as JsonObject
        assertEquals(1, (o["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertTrue((o["meals"] as JsonArray).all { (it as JsonObject)["breaches"] is JsonArray })
        assertEquals(1, (o["trainings"] as JsonArray).size)
        assertTrue(o["baseline"] is JsonObject)
        // The same records as the v0.8.3 file, plus one training and the baseline.
        assertEquals(fixture("v083-state.json"), asV083(o))
        assertTrue(LegacyCoachJson.parseState(text)!!.agencyBuild)
    }

    @Test fun theDamagedStateIsACutAgencyState() {
        val damaged = fixture("damaged-state.json")
        assertTrue(fixture("agency-v2-state.json").startsWith(damaged))
        assertNull(LegacyCoachJson.parseState(damaged))
    }

    @Test fun everyMessageLineIsTheEncodersLineExceptTheCutOne() {
        for (name in listOf("v083-messages.jsonl", "agency-v2-messages.jsonl")) {
            val lines = fixture(name).split("\n")
            assertEquals("", lines.last()) // every record ends with a line break
            val cut = lines.dropLast(1).filter { line ->
                val m = runCatching { CoachJson.decodeMessage(Json.parseToJsonElement(line)) }.getOrNull()
                if (m != null) assertEquals(line, CoachJson.encodeMessage(m))
                m == null
            }
            assertEquals(name, if (name.startsWith("v083")) 1 else 0, cut.size)
        }
        // v0.8.3 had no roles.
        assertTrue(!fixture("v083-messages.jsonl").contains("\"role\""))
    }

    @Test fun theAppAndTheCoreDecodeTheSameFiles() {
        for (name in listOf("v083-state.json", "agency-v2-state.json")) {
            val app = CoachJson.decode(Json.parseToJsonElement(fixture(name)))
            val core = LegacyCoachJson.parseState(fixture(name))!!
            assertEquals(app.meals.map { listOf(it.id, it.day, it.at, it.text, it.items, it.cheat, it.offPlan, it.breaches) },
                core.meals.map { listOf(it.id, it.day, it.at, it.text, it.items, it.cheat, it.offPlan, it.breaches) })
            assertEquals(app.food, core.food)
            assertEquals(app.baseline?.photo, core.baseline?.photo)
        }
    }

    @Test fun theImporterReadsThePrefsKeysTheCoachSettingsWrite() {
        val prefs = InMemorySharedPreferences()
        val s = CoachSettings(prefs)
        s.provider = CoachProvider.COMPAT
        s.deepseekModel = "deepseek-v4-pro"
        s.claudeModel = "claude-opus-5-5"
        s.compatUrl = "https://llm.example.invalid/v1"
        s.compatModel = "local-small"
        s.profile = CoachProfile(heightCm = 171.5, startWeightKg = 82.0, fatG = 60, carbsG = 150, goals = listOf(79.0, 70.0), bedtime = "01:00",
            marginPct = 20, plannedMeal = "牛肉麵", evidenceMinutes = 45, dayStartHour = 4)
        s.companyName = "星光娛樂"
        s.joinDate = java.time.LocalDate.of(2026, 11, 20)
        s.slotTimes = SlotTimes(lunch = 700, dinner = 1000, chicken = 1250, night = 1440)
        assertTrue(s.takeCompatRequest("2026-11-21"))
        val p = LegacyPrefs(prefs.all)
        assertEquals("COMPAT", p.string(LegacyPrefs.PROVIDER))
        assertEquals("deepseek-v4-pro", p.string(LegacyPrefs.MODEL_DEEPSEEK))
        assertEquals("claude-opus-5-5", p.string(LegacyPrefs.MODEL_CLAUDE))
        assertEquals("https://llm.example.invalid/v1", p.string(LegacyPrefs.COMPAT_URL))
        assertEquals("local-small", p.string(LegacyPrefs.COMPAT_MODEL))
        assertEquals(171.5, p.heightCm!!, 0.0)
        assertEquals(82.0, p.startWeightKg!!, 0.0)
        assertEquals(60, p.fatG)
        assertEquals(150, p.carbsG)
        assertEquals(listOf(79.0, 70.0), LegacyPrefs.parseGoals(p.goalsRaw))
        assertEquals("01:00", p.bedtime)
        assertEquals(20, p.marginPct)
        assertEquals("牛肉麵", p.plannedMeal)
        assertEquals(45, p.evidenceMinutes)
        assertEquals(4, p.dayStartHour)
        assertEquals("星光娛樂", p.company)
        assertEquals("2026-11-20", p.joinDate)
        assertEquals(listOf(700, 1000, 1250, 1440), LegacyPrefs.SLOT_DEFAULTS.keys.map { p.slotMinute(it) })
        assertEquals("2026-11-21", p.string(LegacyPrefs.COMPAT_DAY))
        assertEquals(1, p.int(LegacyPrefs.COMPAT_COUNT))
        assertEquals(SlotTimes().let { listOf(it.lunch, it.dinner, it.chicken, it.night) }, LegacyPrefs.SLOT_DEFAULTS.values.toList())
        assertEquals(CoachProfile().goals, LegacyPrefs.OLD_DEFAULT_GOALS)
        assertEquals(CoachSettings.PREFS, LegacyPrefs.NAME)
    }
}
