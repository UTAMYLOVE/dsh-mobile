package app.dshmobile.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * He reads only Traditional Chinese: no Latin letter in any string resource, except the level letters C, B, A standing alone
 * and format placeholders. The Korean name 해나 is not Latin. Also scans the UI code for literals that mix Chinese and Latin.
 */
class NoLatinStringsTest {
    private val placeholder = Regex("%(\\d+\\$)?[-0-9.]*[sdf%]")
    private val escape = Regex("\\\\.")
    private val level = Regex("(?<![A-Za-z])[CBA](?![A-Za-z])")
    private val latin = Regex("[A-Za-zＡ-Ｚａ-ｚ]")
    private val entry = Regex("<string\\s+name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun visibleLatin(s: String): Boolean {
        val t = level.replace(placeholder.replace(escape.replace(s, ""), ""), "")
        return latin.containsMatchIn(t)
    }

    private fun stringFiles() = File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.sortedBy { it.name }

    @Test fun everyStringResourceHasNoLatin() {
        val files = stringFiles()
        assertTrue(files.isNotEmpty())
        val rows = files.flatMap { f -> entry.findAll(f.readText()).map { f.name to it }.toList() }
        assertTrue("expected a good number of strings, got ${rows.size}", rows.size > 150)
        val bad = rows.filter { (_, m) -> visibleLatin(m.groupValues[2]) }.map { (f, m) -> "$f:${m.groupValues[1]}" }
        assertTrue("strings with Latin letters: $bad", bad.isEmpty())
    }

    @Test fun theScanKnowsWhatItAllows() {
        assertFalse(visibleLatin("第 %1\$d 階段"))
        assertFalse(visibleLatin("等級 C"))
        assertFalse(visibleLatin("%1\$s 的 B 級課"))
        assertFalse(visibleLatin("音準 %1\$d%%"))
        assertFalse(visibleLatin("해나"))
        assertTrue(visibleLatin("下載 APK"))
        assertTrue(visibleLatin("ok"))
        assertTrue(visibleLatin("CBA"))
    }

    @Test fun codeLiteralsThatMixChineseAndLatinAreAbsent() {
        val cjk = Regex("[\\u3400-\\u9FFF]")
        val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val templ = Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_]+")
        val files = File("src/main/kotlin/app/dshmobile/ui").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 15)
        val bad = files.flatMap { f ->
            f.readLines().withIndex().filterNot { (_, l) -> l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { (i, l) ->
                    literal.findAll(l).map { templ.replace(it.groupValues[1], "") }
                        .filter { cjk.containsMatchIn(it) && visibleLatin(it) }.map { "${f.name}:${i + 1}: $it" }.toList()
                }
        }
        assertEquals("Chinese literals with Latin letters: $bad", 0, bad.size)
    }
}
