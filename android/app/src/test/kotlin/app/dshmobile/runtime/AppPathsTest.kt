package app.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AppPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun paths() = AppPaths(tmp.newFolder("files"))

    @Test fun userDataIsOutsideTheRootfsAndBoundToTheGuestHome() {
        val p = paths()
        assertEquals(File(p.filesDir, "userdata/workspace"), p.workspace)
        assertEquals(File(p.filesDir, "userdata/dsh-home"), p.dshHome)
        assertEquals(listOf(p.workspace to "/root/workspace", p.dshHome to "/root/.dsh"), p.binds)
        assertFalse(p.workspace.toPath().startsWith(p.rootfs.toPath()))
    }

    @Test fun legacyDataInsideTheRootfsMovesOutWhenTheNewPlaceDoesNotExist() {
        val p = paths()
        File(p.rootfs, "root/workspace/memory").mkdirs()
        File(p.rootfs, "root/workspace/MEMORY.md").writeText("long term")
        File(p.rootfs, "root/workspace/memory/2026-11-01.md").writeText("- note")
        File(p.rootfs, "root/.dsh/profiles/web").mkdirs()
        File(p.rootfs, "root/.dsh/profiles/web/package.json").writeText("{}")

        assertEquals(listOf("/root/workspace", "/root/.dsh"), p.migrateLegacy())

        assertEquals("long term", File(p.workspace, "MEMORY.md").readText())
        assertEquals("- note", File(p.workspace, "memory/2026-11-01.md").readText())
        assertEquals("{}", File(p.dshHome, "profiles/web/package.json").readText())
        // empty mount points stay behind
        assertTrue(File(p.rootfs, "root/workspace").isDirectory && File(p.rootfs, "root/workspace").list()!!.isEmpty())
        assertTrue(File(p.rootfs, "root/.dsh").isDirectory && File(p.rootfs, "root/.dsh").list()!!.isEmpty())
        assertTrue("a second run moves nothing", p.migrateLegacy().isEmpty())
    }

    @Test fun existingUserDataIsNeverOverwritten() {
        val p = paths()
        File(p.rootfs, "root/workspace").mkdirs()
        File(p.rootfs, "root/workspace/MEMORY.md").writeText("old copy")
        p.workspace.mkdirs()
        File(p.workspace, "MEMORY.md").writeText("current")
        assertTrue(p.migrateLegacy().isEmpty())
        assertEquals("current", File(p.workspace, "MEMORY.md").readText())
    }

    @Test fun anEmptyOrMissingLegacyPlaceMovesNothing() {
        val p = paths()
        File(p.rootfs, "root/workspace").mkdirs() // what a fresh rootfs ships
        assertTrue(p.migrateLegacy().isEmpty())
        assertFalse(p.workspace.exists())
        p.ensureUserDirs()
        assertTrue(p.workspace.isDirectory && p.dshHome.isDirectory)
    }

    @Test fun aLegacySymlinkIsNotFollowedOrMoved() {
        val p = paths()
        val outside = tmp.newFolder("outside").also { File(it, "x").writeText("x") }
        File(p.rootfs, "root").mkdirs()
        Files.createSymbolicLink(File(p.rootfs, "root/workspace").toPath(), outside.toPath())
        assertTrue(p.migrateLegacy().isEmpty())
        assertFalse(p.workspace.exists())
    }
}
