package app.agency.core.migrate

import app.agency.core.model.KcalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coach's own files, as v0.8.3 and the 5a94d3c build wrote them (fixtures under resources/legacy, invented records). */
class LegacyCoachJsonTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/legacy/$name")) { "missing fixture $name" }.readText()

    @Test fun aV083StateReadsWithEveryRecord() {
        val s = LegacyCoachJson.parseState(fixture("v083-state.json"))!!
        assertEquals(1, s.version)
        assertFalse(s.agencyBuild)
        assertTrue(s.seeded)
        assertEquals(4, s.meals.size)
        // v0.8.3 had no breaches: an off-plan meal could only be fried food.
        assertEquals(listOf("FRIED"), s.meals[0].breaches)
        assertEquals(emptyList<String>(), s.meals[1].breaches)
        assertTrue(s.meals[3].cheat)
        assertEquals(emptyList<String>(), s.meals[3].breaches)
        val beef = s.meals[2].items[0]
        assertEquals(KcalSource.ESTIMATE, beef.source)
        assertEquals(850, beef.estimateHigh)
        assertEquals(935, beef.kcal)
        assertEquals(2, s.weights.size)
        assertEquals(91.5, s.weights[0].waistCm!!, 0.0)
        assertNull(s.weights[1].waistCm)
        assertEquals(6, s.food.size)
        assertEquals(629, s.food.first { it.name == "給力盒子 南加州烤雞腿" }.kcal)
        assertEquals(LegacyPending("9a3c7e21-4f6b-4d08-b2a5-1e9f8c3d6a23", 0, "紅燒牛肉麵", 935, s.pending[0].askedAt), s.pending.single())
        assertTrue(s.trainings.isEmpty())
        assertNull(s.baseline)
    }

    @Test fun anAgencyStateReadsItsNewFields() {
        val s = LegacyCoachJson.parseState(fixture("agency-v2-state.json"))!!
        assertEquals(2, s.version)
        assertTrue(s.agencyBuild)
        assertEquals(listOf("FRIED", "OVER700"), s.meals[0].breaches)
        assertTrue(s.meals[0].items[1].zeroCal)
        assertFalse(s.meals[0].items[0].zeroCal)
        assertEquals(listOf("ZEROCAL"), s.meals[2].breaches)
        assertEquals(listOf(3, null), s.trainings.map { it.rounds })
        val b = s.baseline!!
        assertEquals(172.0, b.heightCm!!, 0.0)
        assertEquals("photo-1795224600000.jpg", b.photo)
        assertEquals("recording-1795225200000.m4a", b.recording)
        assertEquals(18, b.pushups)
        assertNull(b.plankSec)
        assertEquals(setOf(LegacyBaselineStep.PLANK), b.skipped)
        assertNull(b.step)
    }

    @Test fun aVersion1FileTheAgencyBuildSavedAgainCountsAsTheAgencyBuild() {
        // The 5a94d3c encoder keeps a loaded file's version (1) but always writes `trainings`.
        val s = LegacyCoachJson.parseState("""{"version":1,"seeded":true,"meals":[],"weights":[],"food":[],"pending":[],"trainings":[]}""")!!
        assertTrue(s.agencyBuild)
    }

    @Test fun aCutStateIsUnreadableAndTheTempFileOfACutSaveIsNot() {
        assertNull(LegacyCoachJson.parseState(fixture("damaged-state.json")))
        val tmp = LegacyCoachJson.parseState(fixture("tmp-only/state.json.tmp"))!!
        assertEquals(listOf("a58b1763-9abc-4176-979a-048f0d5a4b26"), tmp.meals.map { it.id })
    }

    @Test fun badFieldsFallBackAndRecordsWithoutIdsAreDropped() {
        val s = LegacyCoachJson.parseState(
            """{"meals":[{"day":"2026-11-20","text":"x"},{"id":"m","day":"2026-11-20","items":[{"kcal":5},{"name":"蛋","source":"WHAT"}],"breaches":[1,"FRIED"]}],
               "weights":[{"id":"w","day":"2026-11-20"}],"food":[{"name":"x"}],"baseline":{"skipped":["PLANK","NOPE"],"step":"NOPE"}}""",
        )!!
        assertEquals(1, s.version)
        assertFalse(s.seeded)
        val m = s.meals.single()
        assertEquals(listOf("蛋"), m.items.map { it.name })
        assertEquals(KcalSource.ESTIMATE, m.items[0].source)
        assertEquals(listOf("FRIED"), m.breaches)
        assertTrue(s.weights.isEmpty())
        assertTrue(s.food.isEmpty())
        assertEquals(setOf(LegacyBaselineStep.PLANK), s.baseline!!.skipped)
        assertNull(s.baseline!!.step)
        assertEquals(LegacyState(), LegacyCoachJson.parseState("[1,2]"))
    }

    @Test fun everyMessageLineIsReadAndACutLineIsCounted() {
        val (v083, bad) = LegacyCoachJson.parseMessages(fixture("v083-messages.jsonl"))
        assertEquals(11, v083.size)
        assertEquals(1, bad)
        assertTrue(v083.all { it.role == null })
        assertEquals(LegacySpeaker.OWNER, v083[0].who)
        assertNull(v083[0].kind)
        assertEquals("meal", v083[1].kind)
        val (v2, bad2) = LegacyCoachJson.parseMessages(fixture("agency-v2-messages.jsonl"))
        assertEquals(0, bad2)
        assertEquals(9, v2.size)
        assertEquals(listOf(null, null, LegacyRole.AGENT, null, LegacyRole.AGENT, null, LegacyRole.BODY, null, LegacyRole.BODY), v2.map { it.role })
    }

    @Test fun theRoleOfAnOldMessageFollowsItsKind() {
        assertEquals(LegacyRole.BODY, LegacyRole.forKind("meal"))
        assertEquals(LegacyRole.BODY, LegacyRole.forKind("craving"))
        assertEquals(LegacyRole.BODY, LegacyRole.forKind("weight"))
        assertEquals(LegacyRole.BODY, LegacyRole.forKind("food_info"))
        assertEquals(LegacyRole.BODY, LegacyRole.forKind("training"))
        assertEquals(LegacyRole.AGENT, LegacyRole.forKind("chat"))
        assertEquals(LegacyRole.AGENT, LegacyRole.forKind("baseline"))
        assertEquals(LegacyRole.AGENT, LegacyRole.forKind(null))
    }
}
