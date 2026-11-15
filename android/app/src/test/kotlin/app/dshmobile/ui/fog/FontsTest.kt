package app.dshmobile.ui.fog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.R
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun font(id: Int) = context.resources.openRawResource(id).use { it.readBytes() }

    @Test fun bundledFontsAreThePinnedFiles() {
        assertEquals("f42db2dd16e642258e35782916eceb1dcdbea06fb958d77ad71dc5963587e8fd", sha256(font(R.font.orbitron)))
        assertEquals("98fcd638baa5c81ff0316b7538ce330ee3b23b1302726de3526d5933a8ecf986", sha256(font(R.font.chakra_petch_regular)))
        assertEquals("45264de3204ddbd5fb3e14a2402acd5c630d16650ae5fc221d2c52da46a6734b", sha256(font(R.font.chakra_petch_semibold)))
    }

    @Test fun theOflNoticeShipsWithBothCopyrights() {
        val bytes = context.assets.open("licenses/fonts-OFL.txt").use { it.readBytes() }
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("The Orbitron Project Authors"))
        assertTrue(text.contains("The Chakra Petch Project Authors"))
        assertTrue(text.contains("SIL Open Font License, Version 1.1"))
        // One source line, then the two upstream OFL.txt files back to back (Orbitron 4426 bytes, Chakra Petch 4396 bytes).
        val body = bytes.copyOfRange(bytes.indexOf('\n'.code.toByte()) + 1, bytes.size)
        assertEquals(4426 + 4396, body.size)
        assertEquals("ab609b0e110d622435ff337cdf233288556e011bbf9bd0550be98846c0630819", sha256(body.copyOfRange(0, 4426)))
        assertEquals("13831d02389d917d22fcfa6c79f98f8acbf61d230add0e73a4d4dc5f3bfb9e56", sha256(body.copyOfRange(4426, body.size)))
    }

    /** Every record of a TrueType font's 'name' table as (name ID, text); Windows and Unicode records are UTF-16BE. */
    private fun names(font: ByteArray): List<Pair<Int, String>> {
        val b = ByteBuffer.wrap(font)
        val tables = b.getShort(4).toInt() and 0xFFFF
        var at = -1
        for (i in 0 until tables) {
            val rec = 12 + 16 * i
            if (String(font, rec, 4, Charsets.US_ASCII) == "name") at = b.getInt(rec + 8)
        }
        check(at >= 0) { "no name table" }
        val count = b.getShort(at + 2).toInt() and 0xFFFF
        val strings = at + (b.getShort(at + 4).toInt() and 0xFFFF)
        return (0 until count).map { i ->
            val r = at + 6 + 12 * i
            val platform = b.getShort(r).toInt() and 0xFFFF
            val id = b.getShort(r + 6).toInt() and 0xFFFF
            val len = b.getShort(r + 8).toInt() and 0xFFFF
            val off = b.getShort(r + 10).toInt() and 0xFFFF
            val charset = if (platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE
            id to String(font, strings + off, len, charset)
        }
    }

    @Test fun theIntroLatinFontIsTheUnmodifiedQuicksand() {
        assertEquals("39c9b64223561f56aaff6062a6f04063c4fc86809ad6768722c06614d977e1cc", sha256(font(R.font.intro_latin)))
    }

    @Test fun theHandwritingSubsetIsRenamedAndKeepsItsCopyright() {
        val names = names(font(R.font.intro_hand))
        // OFL: a subset is a Modified Version and 辰宇落雁 / Chenyuluoyan are Reserved Font Names, so the names users see changed.
        for (id in listOf(1, 3, 4, 6, 16, 17)) {
            val shown = names.filter { it.first == id }.map { it.second }
            assertTrue("name ID $id is there", shown.isNotEmpty())
            shown.forEach { n -> assertFalse("name ID $id: $n", n.contains("辰宇落雁") || n.lowercase().contains("chenyuluoyan")) }
        }
        assertEquals(listOf("DSHM Luoyan Hand"), names.filter { it.first == 1 }.map { it.second })
        // ...and the copyright notice and the licence stay.
        assertTrue(names.any { it.first == 0 && it.second.contains("Wang, Li-Yu") && it.second.contains("Liu, Wei-Chen") })
        assertTrue(names.any { it.first == 13 && it.second.contains("SIL Open Font License") })
    }

    @Test fun theIntroFontLicencesShipInFull() {
        fun body(path: String): ByteArray {
            val bytes = context.assets.open(path).use { it.readBytes() }
            return bytes.copyOfRange(bytes.indexOf('\n'.code.toByte()) + 1, bytes.size)
        }
        // One line saying what the file is, then the upstream licence file byte for byte.
        assertEquals("393732512d2658fa5841dfaad325e002ccb6755b9037a818500434c0a5c4a6d1", sha256(body("licenses/intro-hand-OFL.txt")))
        assertEquals("14d28541780d90f6577bb7abcc6f005bf6969614294e75c82abb8758a696ad46", sha256(body("licenses/intro-latin-OFL.txt")))
        val hand = String(body("licenses/intro-hand-OFL.txt"), Charsets.UTF_8)
        assertTrue(hand.contains("Reserved Font Name \"辰宇落雁\" and \"Chenyuluoyan\""))
    }

    @Test fun everyIntroStringIsInTheHandwriting() {
        // Every intro string (strings.xml's intro_*, and each look's strings_intro_<look>.xml, named intro_<look>_*) is drawn in
        // the handwriting: a character missing from the subset would fall back to the system font mid-word. After changing
        // one, rerun tools/fonts/subset_intro_font.py.
        val covered = IntroFonts.coverage(context.resources)
        assertTrue(covered.size > 4900)
        val missing = R.string::class.java.fields.filter { it.name.startsWith("intro_") }.flatMap { f ->
            val text = context.getString(f.getInt(null)).replace(Regex("%(\\d+\\$)?[sdf]"), "")
            graphemes(text).filter { !it.isBlank() && !IntroFonts.covers(covered, it) }.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), missing)
        // The everyday characters of names are there too (a fallback is fine, but rare).
        for (g in listOf("小", "雨", "明", "華", "美", "玲", "彝")) assertTrue(g, IntroFonts.covers(covered, g))
    }
}
