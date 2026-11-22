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
    /** The facts and the evidence names the corpus is checked against (shared with the Android run, GuardOnAndroidTest). */
    private val facts = javaClass.getResource("/guard/corpus-facts.json")!!.readText().trim()
    private val names = javaClass.getResource("/guard/corpus-names.txt")!!.readText().lines().filter { it.isNotBlank() }

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
