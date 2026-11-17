package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import app.dshmobile.ui.theme.BackdropScene
import app.dshmobile.ui.theme.ColorMode
import app.dshmobile.ui.theme.NoVeil
import app.dshmobile.ui.theme.PictureStage
import app.dshmobile.ui.theme.Sakura
import app.dshmobile.ui.theme.ShellGeometry
import app.dshmobile.ui.theme.Theme
import app.dshmobile.ui.theme.ThemeBackdrop
import app.dshmobile.ui.theme.ThemeDrawing
import app.dshmobile.ui.theme.ThemeFrames
import app.dshmobile.ui.theme.ThemeId
import app.dshmobile.ui.theme.ThemeLevel
import app.dshmobile.ui.theme.ThemeScenes
import app.dshmobile.ui.theme.Themes
import app.dshmobile.ui.theme.themes.contrast
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * ThemedBackground (plan §2.4.5, slots §3.8): what a theme's backdrop draws with and without the user's picture, the generic sky
 * floor and the status band floor (D9, eng E30), the picture stages, the debug plain switch, recording for the glass only when the
 * theme blurs, the Animating flag (eng E9), the scene it hands the backdrop, and a CLASSIC ↔ theme switch that never drops the
 * picture or the floats (eng E21). Pictures are generated here (ThemeScenes.testPicture, solid colours); 1 px = 1 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemedBackgroundTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null
    private val dir: File = Files.createTempDirectory("themed-bg").toFile()

    /** The status bar's height these tests give the shell (px). */
    private val inset = 24f
    private val geometry = ShellGeometry(
        insetTop = mutableFloatStateOf(inset), insetBottom = mutableFloatStateOf(0f), barBottom = mutableFloatStateOf(inset + 62f),
        stackTop = mutableFloatStateOf(560f), stackBottom = mutableFloatStateOf(628f), navInset = mutableFloatStateOf(0f),
        stageTop = mutableFloatStateOf(inset + 62f),
    )

    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogDebug.enabled = false
        FogSwitches.plainBackground = false
        dir.deleteRecursively()
    }

    // ───── helpers ─────

    private val sigil: Theme get() = Themes.of(ThemeId.SIGIL, ColorMode.SIX)

    /** [base] with [backdrop] (and [frames]) instead of its own. */
    private fun themed(backdrop: ThemeBackdrop, base: Theme = sigil, frames: ThemeFrames? = base.frames) = Theme(
        base.id, base.mode, base.colors, base.type, frames, controls = base.controls, backdrop = backdrop, bars = base.bars,
        material = base.material,
    )

    /** A backdrop that records the scenes it is asked for and paints [mark] in its middle quarter (ground) and [moving] (motion). */
    private class Probe(
        val mark: Color? = null,
        val moving: Color? = null,
        override val moves: Boolean = false,
        override val picture: PictureStage = PictureStage.Cover(),
        val noVeil: Boolean = false,
        override val floorsOwnSky: Boolean = false,
    ) : ThemeBackdrop {
        val scenes = ArrayList<BackdropScene>()
        var grounds = 0
        override fun ground(scene: BackdropScene): ThemeDrawing {
            grounds++
            scenes += scene
            return ThemeDrawing { { mark?.let { drawRect(it, Offset(size.width / 4f, size.height / 4f), Size(size.width / 2f, size.height / 2f)) } } }
        }
        override fun veil(scene: BackdropScene): ThemeDrawing? = if (noVeil) NoVeil else null
        override fun motion(scene: BackdropScene): ThemeDrawing? = moving?.let { c -> ThemeDrawing { { drawRect(c, size = Size(10f, 10f)) } } }
    }

    private fun solidPicture(color: Color, w: Int = 64, h: Int = 96): ImageBitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.argb(255, (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())) }.asImageBitmap()

    /** A generated picture of [tone], loaded as the app loads it (raw colours: 六色 0), with its measured tone. */
    private fun tonePicture(tone: ThemeScenes.Tone): ImageBitmap =
        runBlocking { LookPictures.load(ThemeScenes.testPicture(dir, tone), 1L, 0, 55) }!!

    private fun show(
        theme: () -> Theme, picture: () -> ImageBitmap?, look: () -> Look = { Look(picture = 1L, six = 0) },
        effects: () -> Effects = { ThemeScenes.SAVER }, active: () -> Boolean = { true },
    ) {
        rule.setContent {
            view = LocalView.current
            FogTheme(effects(), theme()) {
                CompositionLocalProvider(LocalLook provides look(), LocalPageActive provides active()) {
                    FogBackground(rememberBackdrop(), Modifier.fillMaxSize(), picture(), geometry)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(): Bitmap {
        rule.waitForIdle()
        val v = view!!
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> rule.runOnUiThread { v.draw(Canvas(b)) } }
    }

    private fun Bitmap.at(x: Int, y: Int) = Color(getPixel(x, y))

    private fun assertColour(message: String, expected: Color, actual: Color, tolerance: Int = 3) {
        val gap = maxOf(kotlin.math.abs(expected.red - actual.red), kotlin.math.abs(expected.green - actual.green), kotlin.math.abs(expected.blue - actual.blue))
        assertTrue("$message: expected $expected, was $actual", gap <= tolerance / 255f)
    }

    private val animating = SemanticsMatcher.keyIsDefined(FogSemantics.Animating)
    private fun animatingNow(): Boolean = rule.onAllNodes(animating, useUnmergedTree = true).fetchSemanticsNodes().single().config[FogSemantics.Animating]
    private fun pictureShown(): Boolean = rule.onAllNodes(SemanticsMatcher.keyIsDefined(FogSemantics.Picture), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The non-CLASSIC themes. */
    private val themes: List<Theme> get() = Themes.all().filter { !it.classic }

    // ───── tests ─────

    @Test fun withoutAPictureTheGroundAndTheBackdropsGroundShow() {
        val probe = Probe(mark = Color.Red)
        var theme by mutableStateOf(themed(probe))
        show({ theme }, { null })
        var b = shot()
        assertColour("the ground colour", sigil.colors.ground, b.at(10, 300))
        assertColour("the backdrop's ground drawing", Color.Red, b.at(160, 320))
        assertFalse(pictureShown())
        assertFalse("no picture: no scene picture", probe.scenes.last().hasPicture)
        // 霓虹's placeholder: the stage in the middle, its dark ground under the status bar (read from the shell geometry in draw).
        val neon = Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL)
        theme = neon
        b = shot()
        assertColour("neon stage", neon.colors.stage, b.at(160, 320))
        assertColour("neon ground under the status bar", neon.colors.ground, b.at(160, 10))
    }

    @Test fun aLightPictureNeedsNoSkyFloorAndADarkOneGetsOneThatKeepsSkyTextReadable() {
        val light = tonePicture(ThemeScenes.Tone.LIGHT)
        val dark = tonePicture(ThemeScenes.Tone.DARK)
        val lightTone = LookPictures.toneOf(light)
        val darkTone = LookPictures.toneOf(dark)
        assertNotNull("the tone is measured with the picture", lightTone)
        assertNotNull(darkTone)
        assertTrue("LIGHT is light: ${lightTone!!.p05}", lightTone.p05 > .5f)
        assertTrue("DARK is dark: ${darkTone!!.p05}", darkTone.p05 < .05f)
        for (t in themes) assertEquals("${t.id} ${t.mode}: no floor over a light picture", 0f, skyFloor(t.colors, .8f, lightTone), 0f)

        var theme by mutableStateOf(sigil)
        var picture by mutableStateOf<ImageBitmap?>(dark)
        show({ theme }, { picture })
        for (t in themes) {
            theme = t
            picture = dark
            val floor = skyFloor(t.colors, pictureAlphaOf(PictureStage.Cover(), 80), darkTone)
            assertTrue("${t.id} ${t.mode}: a floor over a dark picture ($floor)", floor > 0f)
            val b = shot()
            // Sky text sits anywhere between the header and the dock: every probe there keeps skyInk at 4.5:1.
            var worst = Float.MAX_VALUE
            for (y in 120..520 step 20) for (x in 8..312 step 16) worst = minOf(worst, contrast(t.colors.skyInk, b.at(x, y)))
            assertTrue("${t.id} ${t.mode}: skyInk over the dark picture with its floor = $worst", worst >= 4.5f)
            picture = light
            val l = shot()
            var lightWorst = Float.MAX_VALUE
            for (y in 120..520 step 20) for (x in 8..312 step 16) lightWorst = minOf(lightWorst, contrast(t.colors.skyInk, l.at(x, y)))
            assertTrue("${t.id} ${t.mode}: skyInk over the light picture = $lightWorst", lightWorst >= 4.5f)
        }
    }

    @Test fun theStatusBandKeepsTheBarIconsAtThreeToOneInEveryTheme() {
        val pictures = listOf(null, tonePicture(ThemeScenes.Tone.LIGHT), tonePicture(ThemeScenes.Tone.DARK))
        var theme by mutableStateOf(sigil)
        var picture by mutableStateOf<ImageBitmap?>(null)
        show({ theme }, { picture })
        for (t in themes) for (p in pictures) {
            theme = t
            picture = p
            val b = shot()
            val bars = t.bars!!
            val icons = if (bars.darkStatus) Color.White else Color(0xFF1F1F1F)
            var worst = Float.MAX_VALUE
            for (y in 0 until inset.toInt()) for (x in 0 until b.width step 4) worst = minOf(worst, contrast(icons, b.at(x, y)))
            val which = if (p == null) "no picture" else if (p === pictures[1]) "LIGHT" else "DARK"
            assertTrue("${t.id} ${t.mode}, $which: bar icons on the band = $worst", worst >= 2.95f)
        }
    }

    @Test @Config(qualifiers = "w360dp-h780dp-mdpi") fun aPrintSitsInItsBoxScaledFromThe360By780Frame() {
        val box = Rect(36f, 78f, 180f, 390f)
        val probe = Probe(picture = PictureStage.Print(box, ColorFilter.colorMatrix(ColorMatrix()), 1f, NoVeil), noVeil = true, floorsOwnSky = true)
        val red = solidPicture(Color.Red)
        show({ themed(probe) }, { red }, { Look(picture = 1L, opacity = 80, six = 0) })
        val b = shot()
        assertTrue(pictureShown())
        val ground = sigil.colors.ground
        assertColour("inside the box (opacity 80 / 80 = full)", Color.Red, b.at(108, 234))
        assertColour("its top-left corner", Color.Red, b.at(37, 79))
        assertColour("its bottom-right corner", Color.Red, b.at(179, 389))
        assertColour("left of it", ground, b.at(34, 234))
        assertColour("right of it", ground, b.at(182, 234))
        assertColour("above it", ground, b.at(108, 76))
        assertColour("below it", ground, b.at(108, 392))
        assertEquals(box, Rect(36f, 78f, 180f, 390f))
        assertEquals(Rect(72f, 78f, 360f, 390f), printBox(box, 720f, 780f))
        // The face lands inside the print (the avatar square is cut from the middle-ish of the picture).
        rule.waitUntil(5_000) { probe.scenes.last().avatar != null }
        val face = probe.scenes.last().face
        assertNotNull(face)
        assertTrue("the face is in the print's box: $face", box.contains(face!!))
    }

    @Test fun inMarkSetsThePictureFlagAndDrawsNoFullScreenPicture() {
        val probe = Probe(picture = PictureStage.InMark, noVeil = true)
        val red = solidPicture(Color.Red)
        show({ themed(probe) }, { red })
        val b = shot()
        assertTrue("the picture is in use (FogSemantics.Picture)", pictureShown())
        for (y in 40..600 step 40) for (x in 10..310 step 50) assertColour("no picture at ($x, $y)", sigil.colors.ground, b.at(x, y))
        // The scene still has the picture, its tone, the face square and the face's tone.
        rule.waitUntil(5_000) { probe.scenes.last().avatar != null && probe.scenes.last().faceTone != null }
        val s = probe.scenes.last()
        assertTrue(s.hasPicture)
        assertNotNull(s.face)
        assertTrue("a red face is dark-ish for black ink: ${s.faceTone}", s.faceTone!!.p05 in .1f..0.3f)
    }

    @Test fun thePlainSwitchDrawsOnlyTheGroundColour() {
        FogSwitches.plainBackground = true
        val probe = Probe(mark = Color.Red, moving = Color.Blue, moves = true)
        show({ themed(probe) }, { solidPicture(Color.Green) }, effects = { ThemeScenes.FULL })
        val b = shot()
        for (y in 0..630 step 30) for (x in 0..310 step 31) assertColour("only the ground at ($x, $y)", sigil.colors.ground, b.at(x, y))
        assertFalse(pictureShown())
        assertFalse("nothing animates on the plain ground", animatingNow())
    }

    @Test fun theBackgroundIsRecordedForTheGlassOnlyWhenTheThemeBlurs() {
        var theme by mutableStateOf(sigil)
        show({ theme }, { null })
        repeat(3) { shot() }
        assertFalse(sigil.blurs)
        assertEquals("a flat theme records nothing", 0, FogDebug.get("background.record"))
        val frames = sigil.frames!!
        val glassy = themed(sigil.backdrop!!, frames = frames.copy(dock = frames.dock.copy(glass = GlassRecipe.Classic)))
        assertTrue(glassy.blurs)
        theme = glassy
        repeat(3) { shot() }
        assertTrue("a blurring theme records its static layer: ${FogDebug.get("background.record")}", FogDebug.get("background.record") >= 3)
    }

    @Test fun animatingIsTodaysExpressionWhetherOrNotTheBackdropMoves() {
        var effects by mutableStateOf(ThemeScenes.FULL)
        var active by mutableStateOf(true)
        var theme by mutableStateOf(sigil)
        show({ theme }, { null }, effects = { effects }, active = { active })
        for (t in listOf(sigil, themed(Probe(moves = false)), themed(Probe(moving = Color.Blue, moves = true)))) {
            theme = t
            effects = ThemeScenes.FULL
            active = true
            rule.waitForIdle()
            assertTrue("FULL animates (${t.backdrop})", animatingNow())
            effects = ThemeScenes.SAVER
            rule.waitForIdle()
            assertFalse("SAVER does not", animatingNow())
            effects = effectsFor(EffectLevel.BALANCED, false)
            active = false
            rule.waitForIdle()
            assertFalse("an inactive page does not", animatingNow())
        }
    }

    @Test fun theSceneCarriesTheShellThePictureAndTheLevelAndIsBuiltOnlyWhenItChanges() {
        val probe = Probe()
        val dark = tonePicture(ThemeScenes.Tone.DARK)
        var look by mutableStateOf(Look(picture = 1L, six = 0, intro = Intro(name = "小雨", number = "07", level = "S")))
        var unrelated by mutableIntStateOf(0)
        var effects by mutableStateOf(ThemeScenes.FULL)
        rule.setContent {
            view = LocalView.current
            FogTheme(effects, themed(probe)) {
                CompositionLocalProvider(LocalLook provides look) {
                    androidx.compose.material3.Text("n $unrelated")
                    FogBackground(rememberBackdrop(), Modifier.fillMaxSize(), dark, geometry)
                }
            }
        }
        rule.waitUntil(5_000) { probe.scenes.lastOrNull()?.avatar != null && probe.scenes.last().faceTone != null }
        val s = probe.scenes.last()
        assertSame(geometry, s.shell)
        assertTrue(s.hasPicture)
        assertEquals(LookPictures.toneOf(dark), s.pictureTone)
        assertEquals(.8f, s.opacity, 1e-6f)
        assertEquals(PictureAlign.CENTER, s.align)
        assertEquals(ThemeLevel.FULL, s.level)
        assertEquals("小雨", s.intro.name)
        assertNotNull(s.measurer)
        assertNotNull("the face is placed by the cover placement", s.face)
        val built = probe.grounds
        unrelated++
        rule.waitForIdle()
        assertEquals("a recomposition that changes nothing builds nothing", built, probe.grounds)
        effects = effects.copy(pageMs = 1)
        rule.waitForIdle()
        assertEquals("an Effects change that keeps the level builds nothing (eng E22)", built, probe.grounds)
        effects = ThemeScenes.SAVER
        rule.waitForIdle()
        assertEquals(ThemeLevel.STILL, probe.scenes.last().level)
        look = look.copy(opacity = 50)
        rule.waitForIdle()
        assertEquals(.5f, probe.scenes.last().opacity, 1e-6f)
        assertNull("no unrelated scene field moved", probe.scenes.zipWithNext().firstOrNull { (a, b) -> a == b })
    }

    @Test fun switchingClassicNeonClassicNeverDropsThePictureAndKeepsTheFloats() {
        val file = ThemeScenes.testPicture(dir, ThemeScenes.Tone.MID)
        val look = Look(picture = 1L, style = BackdropStyle.KAWAII)
        runBlocking { LookPictures.load(file, look.picture, look.six, look.aqua) }
        var theme by mutableStateOf(Themes.Classic)
        rule.setContent {
            view = LocalView.current
            FogTheme(ThemeScenes.SAVER, theme) {
                CompositionLocalProvider(LocalLook provides look, LocalLookPicture provides file) {
                    FogBackground(rememberBackdrop(), Modifier.fillMaxSize(), geometry = geometry)
                }
            }
        }
        rule.waitUntil(5_000) { pictureShown() }
        assertEquals(1, FogDebug.get("background.particles"))
        val neon = Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL)
        rule.mainClock.autoAdvance = false
        for (t in listOf(neon, Themes.Classic, neon, Themes.Classic)) {
            theme = t
            // The very next frame already shows the picture under the new theme.
            rule.mainClock.advanceTimeByFrame()
            val b = Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888).also { rule.runOnUiThread { view!!.draw(Canvas(it)) } }
            assertTrue("the picture in the first frame under ${t.id}", pictureShown())
            val ground = if (t.classic) Sakura.Background else neon.colors.stage
            val px = b.at(160, 320)
            assertTrue("the picture's pixels under ${t.id}: $px", kotlin.math.abs(px.red - ground.red) + kotlin.math.abs(px.blue - ground.blue) > .05f)
        }
        assertEquals("the floats were made once (eng E21)", 1, FogDebug.get("background.particles"))
    }
}
