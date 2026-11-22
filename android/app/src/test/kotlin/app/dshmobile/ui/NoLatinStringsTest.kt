package app.dshmobile.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * He reads only Traditional Chinese (arch §3): the app name, every string of the company's screens (`strings_agency.xml`) and
 * every string literal in their code (`ui/agency`, `ui/settings`, `ui/kit`, `ui/warroom`, `agency/notify`) has no Latin
 * letter, except his idol names, song titles and the level letters C, B, A. A literal with Latin and no Chinese passes only
 * where its line shows it is never on screen ([HIDDEN]: a constant, a test tag, a key, a file or a format); anywhere else, such
 * as `Text("ok")`, it fails. The old dsh screens were deleted in T08.
 */
class NoLatinStringsTest {
    private val whitelist = listOf("BABYMONSTER", "DESERVE", "On My Way")
    private val latin = Regex("[A-Za-zＡ-Ｚａ-ｚ]")
    private val cjk = Regex("[\\u3400-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]")
    private val placeholder = Regex("%(\\d\\$)?[-0-9.]*[sdf]")

    private fun visibleLatin(s: String): Boolean {
        var t = placeholder.replace(s, "")
        whitelist.forEach { t = t.replace(it, "") }
        return latin.containsMatchIn(t)
    }

    @Test fun theAppNameAndTheCompanysStringsHaveNoLatin() {
        val res = File("src/main/res/values")
        val name = Regex("<string name=\"app_name\"[^>]*>(.*?)</string>").find(File(res, "strings.xml").readText())!!.groupValues[1]
        val rows = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>").findAll(File(res, "strings_agency.xml").readText()).toList()
        assertTrue(rows.size > 60)
        val bad = rows.filter { visibleLatin(it.groupValues[2]) }.map { it.groupValues[1] } + listOfNotNull(name.takeIf(::visibleLatin))
        assertTrue("strings with Latin letters: $bad", bad.isEmpty())
    }

    /** A literal with Latin on a line that matches is not shown to him. */
    private val hidden = Regex(
        "const val|testTag\\(|key = |startsWith\\(|String\\.format\\(|File\\(|require\\(|getUriForFile|getMimeTypeFromExtension|" +
            "getSharedPreferences|getString\\(|putString\\(|setType\\(",
    )

    private fun badLiterals(line: String): List<String> {
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("import ")) return emptyList()
        return literal.findAll(line).map { it.groupValues[1] }.map { Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_]+").replace(it, "") }
            .filter { s -> if (cjk.containsMatchIn(s)) visibleLatin(s) else latin.containsMatchIn(s) && !hidden.containsMatchIn(line) }
            .toList()
    }

    private val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

    @Test fun theCompanysCodeShowsNoLatin() {
        val roots = listOf("ui/agency", "ui/settings", "ui/kit", "ui/warroom", "agency/notify")
            .map { File("src/main/kotlin/app/dshmobile/$it") }.filter { it.isDirectory }
        val files = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(files.size >= 10)
        val bad = files.flatMap { f -> f.readLines().withIndex().flatMap { (i, l) -> badLiterals(l).map { "${f.name}:${i + 1}: $it" } } }
        assertTrue("literals he could read with Latin letters: $bad", bad.isEmpty())
    }

    @Test fun aLatinLiteralOnScreenIsCaughtButATagOrAKeyIsNot() {
        assertTrue(badLiterals("        Text(\"ok\", color = Sakura.Text)").isNotEmpty())
        assertTrue(badLiterals("        item(key = \"older\") {").isEmpty())
        assertTrue(badLiterals("    const val HOME = \"ag-home\"").isEmpty())
        assertTrue(badLiterals("        .testTag(\"ag-x\")").isEmpty())
    }

    @Test fun theScanCatchesLatinButNotTheWhitelist() {
        assertTrue(visibleLatin("下載 APK"))
        assertTrue(!visibleLatin("練習生 %1\$s"))
        assertTrue(!visibleLatin("BABYMONSTER 的新歌"))
    }
}
