package app.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** What the app tells the guard plugin when it starts dsh: a dropped or renamed variable here would pass every other test. */
class GuardEnvironmentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ws = "/data/user/0/app.dshmobile/files/userdata/workspace"
    private val real = "/data/data/app.dshmobile/files/userdata/workspace"
    private val own = listOf(File("/data/user/0/app.dshmobile/files/rootfs"), File("/data/user/0/app.dshmobile/files/native"))

    @Test fun autoReviewFollowsTheSwitchExceptAtTheFullLevel() {
        assertEquals("1", GuardEnvironment.of(PermissionLevel.SMART, true, true, own, listOf(ws))["DSHM_AUTO_REVIEW"])
        assertEquals("1", GuardEnvironment.of(PermissionLevel.ASK, true, false, own, listOf(ws))["DSHM_AUTO_REVIEW"])
        assertEquals("0", GuardEnvironment.of(PermissionLevel.SMART, false, true, own, listOf(ws))["DSHM_AUTO_REVIEW"])
        // 全部放行: nothing reviews, whatever the switch says.
        assertEquals("0", GuardEnvironment.of(PermissionLevel.FULL, true, true, own, listOf(ws))["DSHM_AUTO_REVIEW"])
        assertEquals("full", GuardEnvironment.of(PermissionLevel.FULL, true, true, own, listOf(ws))["DSHM_GUARD_LEVEL"])
        assertEquals("ask", GuardEnvironment.of(PermissionLevel.ASK, false, false, own, listOf(ws))["DSHM_GUARD_LEVEL"])
    }

    @Test fun nativeProtectsTheAppsOwnFoldersAndNeedsNoAliases() {
        val env = GuardEnvironment.of(PermissionLevel.SMART, false, true, own, listOf(ws, real))
        assertEquals(own.joinToString(":") { it.path }, env["DSHM_GUARD_PROTECTED"])
        assertFalse(env.containsKey("DSHM_GUARD_ALIASES"))
    }

    @Test fun prootReadsBothHostSpellingsOfTheWorkspaceAsTheWorkspace() {
        val env = GuardEnvironment.of(PermissionLevel.SMART, false, false, own, listOf(ws, real))
        assertEquals("/opt/dsh:/opt/dshm", env["DSHM_GUARD_PROTECTED"])
        assertEquals("$ws=/root/workspace;$real=/root/workspace", env["DSHM_GUARD_ALIASES"])
        assertEquals("$ws=/root/workspace", GuardEnvironment.of(PermissionLevel.SMART, false, false, own, listOf(ws, ws))["DSHM_GUARD_ALIASES"])
    }

    @Test fun theWorkspacesHostNamesIncludeTheNameWithLinksResolved() {
        val target = tmp.newFolder("data", "app", "workspace")
        val link = File(tmp.root, "user0")
        Files.createSymbolicLink(link.toPath(), File(tmp.root, "data/app").toPath())
        val viaLink = File(link, "workspace")
        assertEquals(listOf(viaLink.absolutePath, target.canonicalPath), GuardEnvironment.hostNames(viaLink))
        assertEquals(listOf(target.canonicalPath), GuardEnvironment.hostNames(target.canonicalFile))
    }
}
