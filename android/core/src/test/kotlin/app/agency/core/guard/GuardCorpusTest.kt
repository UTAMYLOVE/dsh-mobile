package app.agency.core.guard

import app.agency.core.engine.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FIX-T05 re-review corpus (invented coach lines, `guard/corpus.tsv`), one case per line: CLEAN means the guard must not
 * flag it, FLAG means it must. Targets: at most [MAX_FALSE_POSITIVES] compliant lines flagged, and no violation missed among
 * the R1-R4 and excuse lines. Every other line must match too, except the ones listed in [KNOWN].
 */
class GuardCorpusTest {
    private val facts = "{\"rules\":{\"quota_kcal\":1700,\"protein_g\":140,\"fat_g\":50,\"carbs_g\":170,\"floor_kcal\":1200,\"meal_cap_kcal\":800,\"cheat_meals_per_week\":1,\"zero_cal_per_week\":3},\"profile\":{\"stage_name\":\"小雨\",\"height_cm\":172,\"current_weight_kg\":79.2,\"next_goal_kg\":75.0},\"today\":{\"day\":\"2026-11-20\",\"slots\":[{\"key\":\"lunch\",\"label\":\"午餐\",\"time\":\"12:00\",\"status\":\"done\"},{\"key\":\"dinner\",\"label\":\"晚餐\",\"time\":\"18:30\",\"status\":\"open\"},{\"key\":\"late\",\"label\":\"第三餐\",\"time\":\"21:00\",\"status\":\"open\"}],\"meals\":[{\"id\":\"meal-a1b2\",\"time\":\"12:10\",\"slot\":\"lunch\",\"kcal\":629,\"verified\":true,\"cheat\":false,\"breaches\":[],\"items\":[{\"name\":\"雞腿便當\",\"kcal\":629,\"protein_g\":38.0,\"source\":\"table\"}]}],\"kcal\":{\"eaten\":629,\"left\":1071,\"floor_left\":571},\"protein\":{\"eaten\":38,\"left\":102},\"water_half\":3,\"training\":[],\"signs\":[],\"weights\":[]},\"last_days\":[{\"day\":\"2026-11-19\",\"reported\":true,\"kcal\":1650,\"protein_g\":120,\"breaches\":0,\"on_plan\":true},{\"day\":\"2026-11-18\",\"reported\":true,\"kcal\":1710,\"protein_g\":131,\"breaches\":1,\"on_plan\":false},{\"day\":\"2026-11-17\",\"reported\":true,\"kcal\":1580,\"protein_g\":118,\"breaches\":0,\"on_plan\":true},{\"day\":\"2026-11-16\",\"reported\":true,\"kcal\":1690,\"protein_g\":126,\"breaches\":0,\"on_plan\":true},{\"day\":\"2026-11-15\",\"reported\":true,\"kcal\":1620,\"protein_g\":110,\"breaches\":0,\"on_plan\":true},{\"day\":\"2026-11-14\",\"reported\":true,\"kcal\":1555,\"protein_g\":135,\"breaches\":0,\"on_plan\":true},{\"day\":\"2026-11-13\",\"reported\":true,\"kcal\":1640,\"protein_g\":122,\"breaches\":0,\"on_plan\":true}]}"
    private val names = listOf("雞胸", "雞腿便當", "茶葉蛋", "燙青菜", "地瓜")

    private data class Case(val line: Int, val category: String, val flag: Boolean, val tone: Tone, val owner: String, val breach: Boolean, val text: String)

    private val cases: List<Case> = javaClass.getResource("/guard/corpus.tsv")!!.readText().lines().withIndex()
        .filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
        .map { (i, l) ->
            val c = l.split('\t')
            Case(i + 1, c[0], c[1] == "FLAG", Tone.valueOf(c[2]), c[3], c[4].toBoolean(), c[5])
        }

    private fun flagged(c: Case): Boolean {
        val ev = Evidence(listOf(facts), names, emptyList(), false, c.owner, c.breach)
        return OutputGuard.check(c.text, ev, GuardMode.NORMAL, c.tone).violations.isNotEmpty()
    }

    @Test fun theCorpusMeetsItsTargets() {
        assertTrue(cases.size >= 172)
        val wrong = cases.filter { flagged(it) != it.flag }
        val falsePositives = wrong.filter { !it.flag }
        val falseNegatives = wrong.filter { it.flag }
        val report = wrong.joinToString("\n") { "${it.line} ${if (it.flag) "FN" else "FP"} ${it.category} ${it.tone} ${it.text}" }
        assertTrue(report, falsePositives.size <= MAX_FALSE_POSITIVES)
        assertEquals(report, emptyList<Case>(), falseNegatives.filter { it.category in ZERO_MISS })
        assertEquals(report, KNOWN, wrong.map { it.text }.toSet())
    }

    companion object {
        const val MAX_FALSE_POSITIVES = 3
        val ZERO_MISS = setOf("R", "excuse")

        /** Lines the regex guard still gets wrong (see FIX-T05b): each is a semantic class it cannot reach. */
        val KNOWN: Set<String> = emptySet()
    }
}
