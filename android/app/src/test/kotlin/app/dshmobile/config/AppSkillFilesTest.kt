package app.dshmobile.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Skills ship files next to their SKILL.md (the bookkeeping skill's life.mjs, which does every sum so the model never does
 * arithmetic). They come from the app's assets, are rewritten only when they change, and anything else in a `dshm-*` folder goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSkillFilesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun home(): File = Files.createTempDirectory("dsh").toFile()
    private fun assets(vararg files: Pair<String, String>): (String) -> InputStream? {
        val map = files.toMap()
        return { path -> map[path]?.let { ByteArrayInputStream(it.toByteArray()) } }
    }
    private val script get() = context.assets.open(AppSkills.assetPath("dshm-bookkeeping", "life.mjs")).use { it.readBytes() }

    @Test fun theBookkeepingSkillGetsItsScriptFromTheAppsAssets() {
        val h = home()
        AppSkills.install(h) { context.assets.open(it) }
        assertArrayEquals(script, File(h, "skills/dshm-bookkeeping/life.mjs").readBytes())
        assertTrue(File(h, "skills/dshm-bookkeeping/SKILL.md").readText().startsWith("---\nname: dshm-bookkeeping\n"))
        // The writer the app builds passes its asset loader through.
        val h2 = home()
        AgentConfigWriter(h2) { context.assets.open(it) }.installSkills()
        assertArrayEquals(script, File(h2, "skills/dshm-bookkeeping/life.mjs").readBytes())
    }

    @Test fun everyShippedFileIsInTheAssetsUnderAPlainName() {
        assertTrue(AppSkills.files.isNotEmpty())
        assertTrue(AppSkills.all.keys.containsAll(AppSkills.files.keys))
        for ((skill, names) in AppSkills.files) for (name in names) {
            assertTrue(name, Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(name) && name != "SKILL.md" && !name.endsWith(".tmp"))
            assertTrue(name, context.assets.open(AppSkills.assetPath(skill, name)).use { it.readBytes() }.isNotEmpty())
        }
    }

    @Test fun aChangedFileIsRewrittenAndFilesNoLongerShippedAreRemoved() {
        val h = home()
        val dir = File(h, "skills/dshm-bookkeeping").apply { mkdirs() }
        File(dir, "life.mjs").writeText("old")
        File(dir, "helper.mjs").writeText("shipped by an earlier version")
        File(dir, "life.mjs.tmp").writeText("left over")
        File(dir, "references/notes.md").apply { parentFile!!.mkdirs(); writeText("old") }
        File(h, "skills/dshm-novel/draft.md").apply { parentFile!!.mkdirs(); writeText("old") }
        File(h, "skills/mine/tool.mjs").apply { parentFile!!.mkdirs(); writeText("the owner's") }
        AppSkills.install(h, assets("skills/dshm-bookkeeping/life.mjs" to "new"))
        assertEquals("new", File(dir, "life.mjs").readText())
        assertEquals(setOf("SKILL.md", "life.mjs"), dir.list()!!.toSet())
        assertEquals(setOf("SKILL.md"), File(h, "skills/dshm-novel").list()!!.toSet())
        assertEquals("the owner's", File(h, "skills/mine/tool.mjs").readText())
    }

    @Test fun anUnchangedFileIsLeftAlone() {
        val h = home()
        AppSkills.install(h, assets("skills/dshm-bookkeeping/life.mjs" to "v1"))
        val file = File(h, "skills/dshm-bookkeeping/life.mjs")
        val skill = File(h, "skills/dshm-bookkeeping/SKILL.md")
        assertTrue(file.setLastModified(1_000_000_000_000L) && skill.setLastModified(1_000_000_000_000L))
        AppSkills.install(h, assets("skills/dshm-bookkeeping/life.mjs" to "v1"))
        assertEquals(1_000_000_000_000L, file.lastModified())
        assertEquals(1_000_000_000_000L, skill.lastModified())
    }

    @Test fun anUnreadableAssetKeepsTheLastCopyAndTheSkillsStillInstall() {
        val h = home()
        AppSkills.install(h, assets("skills/dshm-bookkeeping/life.mjs" to "v1"))
        AppSkills.install(h) { throw IOException("gone") }
        assertEquals("v1", File(h, "skills/dshm-bookkeeping/life.mjs").readText())
        for (name in AppSkills.all.keys) assertTrue(name, File(h, "skills/$name/SKILL.md").isFile)
        val fresh = home()
        AppSkills.install(fresh) { null }
        assertTrue(File(fresh, "skills/dshm-bookkeeping/SKILL.md").isFile)
        assertFalse(File(fresh, "skills/dshm-bookkeeping/life.mjs").exists())
    }

    @Test fun theSkillRunsTheScriptThroughDshHomeAndNamesOnlyCommandsTheScriptHas() {
        val skill = AppSkills.all.getValue("dshm-bookkeeping")
        // `$DSH_HOME` stays a variable (a spelled-out path outside the workspace makes the guard ask every time).
        assertTrue(skill.contains("node \"\$DSH_HOME/skills/dshm-bookkeeping/life.mjs\" add '[{"))
        assertFalse(skill.contains("/data/") || skill.contains("/root/.dsh"))
        val source = String(script, Charsets.UTF_8)
        val commands = Regex("""COMMAND_NAMES = \[([^\]]*)]""").find(source)!!.groupValues[1].split(",").map { it.trim().trim('"') }.toSet()
        for (cmd in listOf("add", "undo", "edit", "recent", "today", "month", "find", "buckets", "settings", "set", "food", "migrate", "uber")) {
            assertTrue(cmd, cmd in commands)
            assertTrue(cmd, Regex("""(^|[\s（(：])$cmd\b""").containsMatchIn(skill))
        }
        for (flag in listOf("--force", "--yes", "--file", "--income", "--month", "--json")) assertTrue(flag, skill.contains(flag) && source.contains(flag.removePrefix("--")))
        // Published for other people: the skill speaks of 使用者, never of one owner, and no number in it is somebody's own.
        assertFalse(skill.contains("主人"))
        assertTrue(skill.contains("生活/設定.json"))
    }
}
