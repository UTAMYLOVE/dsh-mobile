package app.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.ThemeScenes
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every existing setting keeps its place (owner O5, R4): the settings tab (embedded, 進階設定 open, a test picture, 粉嫩二次元)
 * as the ordered list of (test tag, merged text) of its semantics nodes, compared with `classic/settings-order.txt`. Only F6
 * may change it, by inserting exactly its documented block directly before `look-style-0`'s row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsOrderSnapshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val scenes by lazy { ThemeScenes(rule) }

    @After fun tearDown() {
        scenes.close()
    }

    @Test fun everySettingKeepsItsLabelAndPlace() {
        scenes.settingsAll()
        val lines = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val text = (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)).joinToString(" | ")
            if (tag.isNotEmpty() || text.isNotEmpty()) lines += tag + "\t" + text.replace("\n", "\\n")
            n.children.forEach(::walk)
        }
        walk(rule.onNodeWithTag(Tags.SHELL_STAGE).fetchSemanticsNode())
        val actual = lines.joinToString("\n", postfix = "\n")
        val golden = javaClass.classLoader!!.getResourceAsStream(FILE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        val build = File(System.getProperty("user.dir") ?: ".", "build")
        if (golden == null) {
            val out = File(build, "classic-golden/settings-order.txt").apply { parentFile!!.mkdirs(); writeText(actual) }
            fail("no golden $FILE: the order was written to ${out.path}; copy it into src/test/resources/classic")
            return
        }
        if (golden == actual) return
        val out = File(build, "classic-diff/settings-order.txt").apply { parentFile!!.mkdirs(); writeText(actual) }
        val want = golden.lines()
        val got = actual.lines()
        val at = want.indices.firstOrNull { it >= got.size || want[it] != got[it] } ?: want.size
        fail(
            "the settings changed order or labels at line ${at + 1}: expected «${want.getOrNull(at)}», got «${got.getOrNull(at)}» " +
                "(${want.size} lines expected, ${got.size} now); the new list is in ${out.path}",
        )
    }

    private companion object {
        const val FILE = "classic/settings-order.txt"
    }
}
