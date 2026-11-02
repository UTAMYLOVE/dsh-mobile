package app.dshmobile.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspacePathsTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var outside: File

    @Before fun setUp() {
        base = Files.createTempDirectory("wp").toFile()
        root = File(base, "workspace").apply { mkdirs() }
        outside = File(base, "outside").apply { mkdirs() }
        File(root, "notes").mkdirs()
        File(root, "notes/a.txt").writeText("a")
        File(root, "top.md").writeText("t")
        File(outside, "secret.txt").writeText("secret")
    }

    @After fun tearDown() {
        base.deleteRecursively()
    }

    private fun link(at: File, target: File) {
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    @Test fun allowsPlainRelativePaths() {
        val f = WorkspacePaths.resolveInside(root, "notes/a.txt")
        assertNotNull(f)
        assertEquals("a", f!!.readText())
        assertNotNull(WorkspacePaths.resolveInside(root, "top.md"))
        assertNotNull(WorkspacePaths.resolveInside(root, "./notes/../top.md"))
    }

    @Test fun refusesParentEscape() {
        assertNull(WorkspacePaths.resolveInside(root, "../outside/secret.txt"))
        assertNull(WorkspacePaths.resolveInside(root, "notes/../../outside/secret.txt"))
        assertNull(WorkspacePaths.resolveInside(root, ".."))
    }

    @Test fun refusesAbsolutePaths() {
        assertNull(WorkspacePaths.resolveInside(root, "/etc/passwd"))
        assertNull(WorkspacePaths.resolveInside(root, File(root, "top.md").absolutePath))
    }

    @Test fun refusesEmptyAndNul() {
        assertNull(WorkspacePaths.resolveInside(root, ""))
        assertNull(WorkspacePaths.resolveInside(root, "top.md\u0000.png"))
    }

    @Test fun refusesMissingFilesAndDirectories() {
        assertNull(WorkspacePaths.resolveInside(root, "nope.txt"))
        assertNull(WorkspacePaths.resolveInside(root, "notes"))
    }

    @Test fun refusesSymlinkedFileOutsideRoot() {
        link(File(root, "evil.txt"), File(outside, "secret.txt"))
        assertNull(WorkspacePaths.resolveInside(root, "evil.txt"))
    }

    @Test fun refusesSymlinkedIntermediateDirectoryOutsideRoot() {
        link(File(root, "evildir"), outside)
        assertNull(WorkspacePaths.resolveInside(root, "evildir/secret.txt"))
    }

    @Test fun allowsSymlinkThatStaysInsideRoot() {
        link(File(root, "alias.txt"), File(root, "notes/a.txt"))
        assertNotNull(WorkspacePaths.resolveInside(root, "alias.txt"))
    }

    @Test fun refusesSiblingDirectoryWithSharedPrefix() {
        val sibling = File(base, "workspace-evil").apply { mkdirs() }
        File(sibling, "x.txt").writeText("x")
        assertNull(WorkspacePaths.resolveInside(root, "../workspace-evil/x.txt"))
    }
}
