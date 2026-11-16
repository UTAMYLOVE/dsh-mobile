package app.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import app.dshmobile.chat.FileKind
import app.dshmobile.ui.chat.badgeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The palettes every theme draws with (R2, R8): today's colours for CLASSIC, the six colours, and the four looks' own. */
class ThemeColorsTest {
    private val bg = Sakura.Background
    private val milk = Sakura.MilkTea
    private val pink = Sakura.Primary
    private val white = Sakura.White
    private val aqua = Sakura.Aqua
    private val rose = Sakura.RoseBrown
    private val ink = Sakura.Text
    private val ink2 = Sakura.TextSecondary

    @Test fun classicIsTodaysSakuraRoleByRole() {
        val c = ThemeColors.Classic
        val expected = mapOf(
            "window" to bg, "ground" to bg, "stage" to bg, "paper" to white,
            "ink" to ink, "ink2" to ink2, "skyInk" to ink2, "quietInk" to rose, "accentText" to ink2,
            "accent" to pink, "accentSoft" to bg, "onAccent" to ink, "cool" to aqua,
            "milk" to milk, "line" to rose, "shadow" to rose,
            "inkFill" to ink, "onInk" to white, "onInk2" to white, "scrim" to ink,
            "running" to aqua, "waiting" to pink, "done" to milk, "failed" to rose, "idle" to Color(0xFFF3E6E2),
            "error" to Color(0xFFB3261E), "onError" to white,
        )
        assertEquals(expected, roles(c))
        assertEquals(Color(0xFFB3261E), ThemeColors.ErrorRed)
        // CLASSIC keeps its legacy text values for pixel identity: INK2 on the sky, ROSE for quiet text.
        assertEquals(ink2, c.skyInk)
        assertEquals(rose, c.quietInk)
    }

    @Test fun theRoleListNamesEveryRole() {
        // roles() below must list every constructor property, so the walks over it miss none.
        val components = ThemeColors::class.java.declaredMethods.count { it.name.startsWith("component") }
        assertEquals(27, components)
        assertEquals(27, roles(ThemeColors.Classic).size)
    }

    @Test fun everyPaletteIsCoreTableWithInkForSmallAccentText() {
        val table = parseTable()
        val palettes = mapOf(
            "CLASSIC" to ThemeColors.Classic, "DRAWN" to Palettes.Drawn,
            "SAKURA_O" to Palettes.SakuraOwn, "SAKURA_S" to Palettes.of(ThemeId.WEB_SAKURA, ColorMode.SIX),
            "NEON_O" to Palettes.NeonOwn, "NEON_S" to Palettes.NeonSix,
            "WAVE_O" to Palettes.WaveOwn, "WAVE_S" to Palettes.of(ThemeId.WEB_WAVE, ColorMode.SIX),
            "ACCESS_O" to Palettes.AccessOwn, "ACCESS_S" to Palettes.AccessSix,
        )
        assertEquals(table.keys, palettes.keys)
        for ((name, p) in palettes) {
            val want = table.getValue(name)
            for ((role, colour) in roles(p)) {
                val expected = want[role] ?: when (role) {
                    "error" -> Color(0xFFB3261E)
                    "onError" -> white
                    else -> error("no table value for $name.$role")
                }
                assertEquals("$name.$role", hex(expected), hex(colour))
            }
        }
    }

    @Test fun smallAccentTextIsInkInTheDrawnAndSixColourPalettes() {
        for (p in listOf(Palettes.Drawn, Palettes.Six, Palettes.NeonSix, Palettes.AccessSix)) {
            assertEquals(ink, p.accentText)
            assertEquals(ink, p.skyInk)
        }
        assertSame(Palettes.Six, Palettes.AccessSix)
        assertEquals(bg, Palettes.Six.onInk2)
        assertEquals(white, Palettes.Drawn.onInk2)
    }

    @Test fun everyRoleOfEveryPaletteIsOpaque() {
        for ((name, p) in allPalettes()) for ((role, colour) in roles(p)) assertEquals("$name.$role", 1f, colour.alpha)
    }

    @Test fun theDrawnAndSixColourPalettesUseOnlyTheSixColours() {
        val allowed = setOf(bg, milk, pink, white, aqua, rose, ink, ink2, Color(0xFFF3E6E2), Color(0xFFB3261E))
        val sixPalettes = mapOf(
            "Drawn" to Palettes.Drawn, "Six" to Palettes.Six, "NeonSix" to Palettes.NeonSix, "AccessSix" to Palettes.AccessSix,
        )
        for ((name, p) in sixPalettes) for ((role, colour) in roles(p)) assertTrue("$name.$role ${hex(colour)}", colour in allowed)
        // Every drawn theme and every 六個粉色 mode wears one of these four.
        for (id in ThemeId.entries) for (mode in ColorMode.entries) {
            if (id == ThemeId.CLASSIC || (id.ownColours && mode == ColorMode.ORIGINAL)) continue
            assertTrue("$id $mode", sixPalettes.values.any { it === Palettes.of(id, mode) })
        }
    }

    @Test fun textReachesItsContrastInEveryThemedPalette() {
        val pairs45 = listOf<Triple<String, (ThemeColors) -> Color, (ThemeColors) -> Color>>(
            Triple("ink/stage", { it.ink }, { it.stage }), Triple("ink/paper", { it.ink }, { it.paper }),
            Triple("ink2/paper", { it.ink2 }, { it.paper }), Triple("ink2/stage", { it.ink2 }, { it.stage }),
            Triple("skyInk/stage", { it.skyInk }, { it.stage }), Triple("quietInk/paper", { it.quietInk }, { it.paper }),
            Triple("accentText/paper", { it.accentText }, { it.paper }), Triple("onInk/inkFill", { it.onInk }, { it.inkFill }),
            Triple("onInk2/inkFill", { it.onInk2 }, { it.inkFill }), Triple("error/stage", { it.error }, { it.stage }),
        )
        for ((name, p) in allPalettes()) {
            if (p === ThemeColors.Classic) continue
            for ((pair, fg, bgOf) in pairs45) {
                val r = contrast(fg(p), bgOf(p))
                assertTrue("$name $pair = $r", r >= 4.5f)
            }
            // The button-label exception: INK on PINK is today's pink-button label (4.04).
            val r = contrast(p.onAccent, p.accent)
            assertTrue("$name onAccent/accent = $r", r >= 4.0f)
        }
    }

    @Test fun onlyTheNeonWindowIsDark() {
        for ((name, p) in allPalettes()) {
            val dark = p === Palettes.NeonOwn || p === Palettes.NeonSix
            assertEquals(name, dark, p.darkWindow)
        }
    }

    @Test fun palettesOfMapsEveryThemeAndMode() {
        for (mode in ColorMode.entries) {
            assertSame(ThemeColors.Classic, Palettes.of(ThemeId.CLASSIC, mode))
            for (id in listOf(ThemeId.SIGIL, ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM, ThemeId.TUNNEL)) {
                assertSame("$id $mode", Palettes.Drawn, Palettes.of(id, mode))
            }
        }
        assertSame(Palettes.SakuraOwn, Palettes.of(ThemeId.WEB_SAKURA, ColorMode.ORIGINAL))
        assertSame(Palettes.Six, Palettes.of(ThemeId.WEB_SAKURA, ColorMode.SIX))
        assertSame(Palettes.NeonOwn, Palettes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL))
        assertSame(Palettes.NeonSix, Palettes.of(ThemeId.WEB_NEON, ColorMode.SIX))
        assertSame(Palettes.WaveOwn, Palettes.of(ThemeId.WEB_WAVE, ColorMode.ORIGINAL))
        assertSame(Palettes.Six, Palettes.of(ThemeId.WEB_WAVE, ColorMode.SIX))
        assertSame(Palettes.AccessOwn, Palettes.of(ThemeId.WEB_ACCESS, ColorMode.ORIGINAL))
        assertSame(Palettes.AccessSix, Palettes.of(ThemeId.WEB_ACCESS, ColorMode.SIX))
    }

    @Test fun theSwatchesAreTheSettingsMocks() {
        val six = listOf(bg, milk, pink, white, aqua, rose)
        assertEquals(six, Palettes.SixSwatches)
        for (id in ThemeId.entries) {
            assertEquals("$id SIX", six, Palettes.swatches(id, ColorMode.SIX))
            if (!id.ownColours) assertEquals("$id ORIGINAL", six, Palettes.swatches(id, ColorMode.ORIGINAL))
        }
        assertEquals(
            listOf(Color(0xFFF7F1F8), Color(0xFFFFD0E0), Color(0xFFF6B8CD), Color(0xFFB9A4E6), Color(0xFF4F4260)),
            Palettes.swatches(ThemeId.WEB_SAKURA, ColorMode.ORIGINAL),
        )
        assertEquals(
            listOf(Color(0xFF0B0B0E), Color(0xFFFF3D84), Color(0xFFFFAECB), Color(0xFFFFFFFF), Color(0xFFC2185B)),
            Palettes.swatches(ThemeId.WEB_NEON, ColorMode.ORIGINAL),
        )
        assertEquals(
            listOf(Color(0xFFF7F8FA), Color(0xFF4D6BFE), Color(0xFF0B0D10)),
            Palettes.swatches(ThemeId.WEB_WAVE, ColorMode.ORIGINAL),
        )
        assertEquals(
            listOf(Color(0xFFE5E3DF), Color(0xFF0A0A0A), Color(0xFFFF7500), Color(0xFFFFFFFF)),
            Palettes.swatches(ThemeId.WEB_ACCESS, ColorMode.ORIGINAL),
        )
    }

    @Test fun classicBadgesAreTodaysTable() {
        for (k in FileKind.entries) assertEquals("$k", badgeColors(k), ThemeColors.Classic.badge(k))
    }

    @Test fun everyOtherPalettesBadgeMapsItsRolesAndItsLabelReads() {
        for ((name, p) in allPalettes()) {
            if (p === ThemeColors.Classic) continue
            for (k in FileKind.entries) {
                val (fill, label) = p.badge(k)
                val role = when (k) {
                    FileKind.MD -> p.line
                    FileKind.DOC -> p.inkFill
                    FileKind.XLS -> p.cool
                    FileKind.PPT, FileKind.PDF -> p.accent
                    FileKind.WEB -> p.ink2
                    FileKind.SVG -> p.stage
                    FileKind.CSV, FileKind.IMG, FileKind.OTHER -> p.milk
                }
                assertEquals("$name $k fill", role, fill)
                // The label is the better of ink and onInk.
                val best = if (contrast(p.ink, fill) >= contrast(p.onInk, fill)) p.ink else p.onInk
                assertEquals("$name $k label", best, label)
                // ≥ 4.0 for bold badge text; the six-colour MD badge is CLASSIC's own ROSE/White pair (3.81), kept as it ships.
                val legacy = fill == rose && label == white
                val floor = if (legacy) 3.8f else 4.0f
                val r = contrast(label, fill)
                assertTrue("$name $k ${hex(label)} on ${hex(fill)} = $r", r >= floor)
            }
        }
        // The 3.8 floor is used by exactly the six-colour MD badge.
        for (p in listOf(Palettes.Drawn, Palettes.Six, Palettes.NeonSix, Palettes.AccessSix)) {
            assertEquals(rose to white, p.badge(FileKind.MD))
        }
        for (p in listOf(Palettes.SakuraOwn, Palettes.NeonOwn, Palettes.WaveOwn, Palettes.AccessOwn)) {
            for (k in FileKind.entries) assertFalse("${p.badge(k)}", p.badge(k) == (rose to white))
        }
    }

    // ---- helpers ----

    private fun allPalettes(): List<Pair<String, ThemeColors>> =
        ThemeId.entries.flatMap { id -> ColorMode.entries.map { m -> "$id $m" to Palettes.of(id, m) } }

    private fun roles(c: ThemeColors): Map<String, Color> = linkedMapOf(
        "window" to c.window, "ground" to c.ground, "stage" to c.stage, "paper" to c.paper,
        "ink" to c.ink, "ink2" to c.ink2, "skyInk" to c.skyInk, "quietInk" to c.quietInk, "accentText" to c.accentText,
        "accent" to c.accent, "accentSoft" to c.accentSoft, "onAccent" to c.onAccent, "cool" to c.cool,
        "milk" to c.milk, "line" to c.line, "shadow" to c.shadow,
        "inkFill" to c.inkFill, "onInk" to c.onInk, "onInk2" to c.onInk2, "scrim" to c.scrim,
        "running" to c.running, "waiting" to c.waiting, "done" to c.done, "failed" to c.failed, "idle" to c.idle,
        "error" to c.error, "onError" to c.onError,
    )

    /** WCAG 2.x contrast of two opaque sRGB colours, computed from the 8-bit channels. */
    private fun contrast(a: Color, b: Color): Float {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        fun lum(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        val x = lum(a)
        val y = lum(b)
        return ((max(x, y) + .05) / (min(x, y) + .05)).toFloat()
    }

    private fun hex(c: Color): String = "#%08X".format(c.value.shr(32).toLong() and 0xFFFFFFFFL)

    /**
     * core.md §2.4's table of all palettes (S = 六個粉色), with D3 applied: accentText is INK (#5E4445) in DRAWN and every S
     * column (the table printed #7D5F60 there). error #B3261E and onError #FFFFFF are the same everywhere.
     */
    private fun parseTable(): Map<String, Map<String, Color>> {
        val header = "CLASSIC DRAWN SAKURA_O SAKURA_S NEON_O NEON_S WAVE_O WAVE_S ACCESS_O ACCESS_S".split(" ")
        val rows = """
            window     FEDFE1 FEDFE1 F7F1F8 FEDFE1 0B0B0E 5E4445 F7F8FA FEDFE1 E5E3DF FEDFE1
            ground     FEDFE1 FEDFE1 F7F1F8 FEDFE1 0B0B0E 5E4445 F7F8FA FEDFE1 E5E3DF FEDFE1
            stage      FEDFE1 FEDFE1 F7F1F8 FEDFE1 F4F4F6 FEDFE1 F7F8FA FEDFE1 E5E3DF FEDFE1
            paper      FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF
            ink        5E4445 5E4445 4F4260 5E4445 101014 5E4445 0B0D10 5E4445 0A0A0A 5E4445
            ink2       7D5F60 7D5F60 5C4E70 7D5F60 616164 7D5F60 5B6672 7D5F60 3A3936 7D5F60
            skyInk     7D5F60 5E4445 4F4260 5E4445 101014 5E4445 0B0D10 5E4445 3A3936 5E4445
            quietInk   9E7A7A 7D5F60 5C4E70 7D5F60 616164 7D5F60 5B6672 7D5F60 3A3936 7D5F60
            accentText 7D5F60 5E4445 6E6084 5E4445 C2185B 5E4445 253EDC 5E4445 0A0A0A 5E4445
            accent     DC9FB4 DC9FB4 F6B8CD DC9FB4 FF3D84 DC9FB4 4D6BFE DC9FB4 FF7500 DC9FB4
            accentSoft FEDFE1 FEDFE1 FFD0E0 FEDFE1 FFAECB FEDFE1 E3E9FF FEDFE1 ECEAE6 FEDFE1
            onAccent   5E4445 5E4445 4F4260 5E4445 101014 5E4445 FFFFFF 5E4445 0A0A0A 5E4445
            cool       A5DEE4 A5DEE4 B9A4E6 A5DEE4 788CAA A5DEE4 4D6BFE A5DEE4 FFFFFF A5DEE4
            milk       D7C4BB D7C4BB DCCFED D7C4BB D6D6D9 D7C4BB D6D7D9 D7C4BB C2C0BD D7C4BB
            line       9E7A7A 9E7A7A 6E6084 9E7A7A 101014 9E7A7A 5B6672 9E7A7A 6D6C6A 9E7A7A
            shadow     9E7A7A 9E7A7A 785AA0 9E7A7A 1E141E 5E4445 8FA1BA 9E7A7A 0A0A0A 9E7A7A
            inkFill    5E4445 5E4445 4F4260 5E4445 101014 5E4445 141922 5E4445 0A0A0A 5E4445
            onInk      FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF FFFFFF F7F8FA FFFFFF FFFFFF FFFFFF
            onInk2     FFFFFF FFFFFF FFD0E0 FEDFE1 FF6CA2 FEDFE1 B4BFCE FEDFE1 CFCCC6 FEDFE1
            scrim      5E4445 5E4445 4F4260 5E4445 0B0B0E 5E4445 0B0D10 5E4445 0A0A0A 5E4445
            running    A5DEE4 A5DEE4 B9A4E6 A5DEE4 FF3D84 A5DEE4 4D6BFE A5DEE4 FF7500 A5DEE4
            waiting    DC9FB4 DC9FB4 F6B8CD DC9FB4 FFAECB DC9FB4 8430E8 DC9FB4 0A0A0A DC9FB4
            done       D7C4BB D7C4BB E9E0F8 D7C4BB D9D9DE D7C4BB C9D3E2 D7C4BB B4B1AB D7C4BB
            failed     9E7A7A 9E7A7A 6E6084 9E7A7A C2185B 9E7A7A 5F6A7C 9E7A7A FF3B30 9E7A7A
            idle       F3E6E2 F3E6E2 F7F1F8 F3E6E2 F4F4F6 F3E6E2 EEF1F7 F3E6E2 ECEAE6 F3E6E2
        """.trimIndent().lines()
        val out = header.associateWith { linkedMapOf<String, Color>() }
        for (row in rows) {
            val cells = row.trim().split(Regex("\\s+"))
            assertEquals(row, header.size + 1, cells.size)
            header.forEachIndexed { i, col -> out.getValue(col)[cells[0]] = Color(0xFF000000 or cells[i + 1].toLong(16)) }
        }
        return out
    }
}
