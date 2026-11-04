package app.dshmobile.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Owner, 2026-11-05: 「把 superpowers 改成 dsh 用的版本」 — the port in assets/dsh-skills and how the app installs it. */
class SuperpowersSkillsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = File("src/main/assets/dsh-skills")

    @Test fun everySkillIsRenamedAndLoadableByDsh() {
        val skills = assets.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        assertEquals(14, skills.size)
        for (dir in skills) {
            assertTrue(dir.name, dir.name.startsWith("dshm-sp-"))
            val text = File(dir, "SKILL.md").readText()
            assertTrue(dir.name, text.startsWith("---\nname: ${dir.name}\n"))
            val description = Regex("(?m)^description: (.*)$").find(text)!!.groupValues[1]
            assertTrue("${dir.name}: dsh cuts catalog descriptions at 500", description.length <= 500)
        }
        val leftovers = assets.walkTopDown().filter { it.isFile && it.readText().contains("superpowers:") }.map { it.path }.toList()
        assertEquals(emptyList<String>(), leftovers)
        assertTrue(File(assets, "dshm-sp-using-superpowers/references/dsh-tools.md").readText().contains("`todo_write`"))
        assertTrue(File(assets, "LICENSE-superpowers").readText().contains("MIT License"))
    }

    @Test fun bundledSkillsInstallBesideTheOwnersAndFollowTheSwitch() {
        val home = tmp.root
        File(home, "skills/my-own").apply { mkdirs(); File(this, "SKILL.md").writeText("mine") }
        val bundle = mapOf("dshm-sp-x" to mapOf("SKILL.md" to "---\nname: dshm-sp-x\n".toByteArray(), "scripts/run" to "echo".toByteArray()))
        AppSkills.install(home, bundle)
        val x = File(home, "skills/dshm-sp-x")
        assertEquals("echo", File(x, "scripts/run").readText())
        assertTrue(File(x, "scripts/run").canExecute())
        assertTrue(File(home, "skills/dshm-novel/SKILL.md").isFile)

        File(x, "stale.md").writeText("old")
        AppSkills.install(home, bundle)
        assertFalse("a changed folder is rewritten", File(x, "stale.md").exists())

        AppSkills.install(home) // the switch off
        assertFalse(x.exists())
        assertEquals("mine", File(home, "skills/my-own/SKILL.md").readText())
        assertTrue(File(home, "skills/dshm-novel/SKILL.md").isFile)
    }
}
