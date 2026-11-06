package app.dshmobile.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsTest {
    private val ws = Files.createTempDirectory("ws").toFile()

    @Test fun createWritesAgentsMd() {
        val store = ProjectStore(ws) { 5 }
        val p = store.create("台股研究", ProjectStore.templates.first())
        val md = File(ws, "projects/${p.slug}/AGENTS.md").readText()
        assertTrue(md.startsWith("# 專案：台股研究"))
        assertTrue(md.contains("不要給買賣建議"))
        assertTrue(p.assistant)
        store.update(p.slug, instructions = "只看台積電")
        assertEquals("只看台積電", ProjectStore(ws).get(p.slug)!!.instructions)
        assertEquals("f.md", store.addFile(p.slug, "f.md", "x".toByteArray()))
        assertEquals("f-2.md", store.addFile(p.slug, "f.md", "y".toByteArray()))
        assertNull(store.addFile(p.slug, "AGENTS.md", "evil".toByteArray()))
        assertEquals(listOf("f-2.md", "f.md"), store.get(p.slug)!!.files)
        store.removeFile(p.slug, "f.md")
        assertEquals(listOf("f-2.md"), store.get(p.slug)!!.files)
    }

    @Test fun slugIsSafe() {
        assertTrue(ProjectStore.slugOf("台股研究").matches(Regex("p-[0-9a-f]{8}")))
        assertEquals("ai-stocks", ProjectStore.slugOf("AI Stocks!"))
        assertFalse(ProjectStore.slugOf("../../etc").contains(".."))
        val store = ProjectStore(ws)
        val a = store.create("English")
        val b = store.create("English")
        assertEquals("english", a.slug)
        assertEquals("english-2", b.slug)
    }

    @Test fun sessionsInAProjectUseItsFolder() {
        assertEquals("/root/workspace/projects/english", ProjectStore.cwdOf("/root/workspace/", "english"))
        assertEquals("english", ProjectStore.slugOfCwd("/root/workspace/projects/english"))
        assertNull(ProjectStore.slugOfCwd("/root/workspace"))
        assertNull(ProjectStore.slugOfCwd("/root/workspace/projects/../x"))
    }

    @Test fun deletedFolderShowsGone() {
        val store = ProjectStore(ws)
        val p = store.create("Gone")
        File(ws, "projects/${p.slug}").deleteRecursively()
        store.refresh()
        assertNull(store.get(p.slug))
        store.delete("../oops")
        assertTrue(ws.exists())
    }
}
