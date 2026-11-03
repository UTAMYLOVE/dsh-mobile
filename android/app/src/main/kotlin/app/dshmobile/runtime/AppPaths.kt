package app.dshmobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Where everything lives on the host, in one place. The rootfs (`filesDir/rootfs`) is replaceable: a new app version with a new
 * rootfs reinstalls it. The user's data is therefore kept outside it, in `filesDir/userdata`, and bound into the guest by proot:
 *
 * | host                         | guest             |
 * |------------------------------|-------------------|
 * | `filesDir/userdata/workspace` | `/root/workspace` (memory notes, MEMORY.md, USER.md, the agent's files) |
 * | `filesDir/userdata/dsh-home`  | `/root/.dsh`      (dsh's conversations, settings and profile)          |
 *
 * Host code that reads or writes the workspace or dsh's home uses [workspace] and [dshHome], never a path under the rootfs.
 * The dsh versions (`/opt/dsh`) stay in the rootfs: a rootfs upgrade resets dsh to the version that rootfs ships.
 */
class AppPaths(val filesDir: File) {
    val rootfs = File(filesDir, "rootfs")
    val userData = File(filesDir, "userdata")
    val workspace = File(userData, "workspace")
    val dshHome = File(userData, "dsh-home")

    /** proot binds (host directory to guest path), see [ProotRunner]. */
    val binds: List<Pair<File, String>> get() = listOf(workspace to GUEST_WORKSPACE, dshHome to GUEST_DSH_HOME)

    /** Creates the user directories (the bind sources) if they are missing. */
    fun ensureUserDirs() {
        for ((host, _) in binds) host.mkdirs()
    }

    /**
     * Moves user data from where the first builds kept it (inside the rootfs, at the guest path) to [userData]: for each bind
     * whose old location holds something while the new one does not exist yet, one rename (same file system). An empty mount
     * point is left at the old location. Never overwrites anything in [userData]. Returns the guest paths that were moved.
     */
    fun migrateLegacy(): List<String> {
        val moved = ArrayList<String>()
        for ((host, guest) in binds) {
            val legacy = legacyLocation(guest)
            if (!Files.isDirectory(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            if (legacy.list().isNullOrEmpty()) continue
            if (Files.exists(host.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            host.parentFile?.mkdirs()
            Files.move(legacy.toPath(), host.toPath(), StandardCopyOption.ATOMIC_MOVE)
            if (!legacy.mkdirs()) throw IOException("cannot recreate the mount point $guest")
            moved += guest
        }
        return moved
    }

    /** The host path of [guest] inside the rootfs (where user data lived before it moved to [userData]). */
    private fun legacyLocation(guest: String) = File(rootfs, guest.removePrefix("/"))

    companion object {
        const val GUEST_WORKSPACE = "/root/workspace"
        const val GUEST_DSH_HOME = "/root/.dsh"
    }
}
