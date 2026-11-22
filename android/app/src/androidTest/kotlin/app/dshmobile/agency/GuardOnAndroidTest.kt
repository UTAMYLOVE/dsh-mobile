package app.dshmobile.agency

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.engine.Tone
import app.agency.core.guard.Evidence
import app.agency.core.guard.GuardMode
import app.agency.core.guard.OutputGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard corpus (`guard/corpus.tsv`, the same facts and names as the JVM's GuardCorpusTest) on the phone's own regex engine:
 * every line must come out as it does on the JVM, where the corpus has no known miss.
 */
@RunWith(AndroidJUnit4::class)
class GuardOnAndroidTest {
    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes().decodeToString() }

    @Test fun theCorpusComesOutAsOnTheJvm() {
        val facts = asset("guard/corpus-facts.json").trim()
        val names = asset("guard/corpus-names.txt").lines().filter { it.isNotBlank() }
        val cases = asset("guard/corpus.tsv").lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
        assertTrue(cases.size >= 172)
        val wrong = cases.mapNotNull { (i, l) ->
            val c = l.split('\t')
            val ev = Evidence(listOf(facts), names, emptyList(), false, c[3], c[4].toBoolean())
            val flagged = OutputGuard.check(c[5], ev, GuardMode.NORMAL, Tone.valueOf(c[2])).violations.isNotEmpty()
            if (flagged != (c[1] == "FLAG")) "${i + 1} ${c[1]} ${c[5]}" else null
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
