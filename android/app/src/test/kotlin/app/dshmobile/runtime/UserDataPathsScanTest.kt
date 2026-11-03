package app.dshmobile.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I-5: the user's workspace and dsh's home live in `filesDir/userdata` (see [AppPaths]). No host code may reach them through
 * the replaceable rootfs any more: a reinstall would make it read stale data or write data that is then thrown away.
 */
class UserDataPathsScanTest {
    @Test fun noSourceReachesUserDataThroughTheRootfs() {
        // `rootfs/root/workspace` or a host-relative `"root/workspace"` (guest paths start with a slash: `"/root/workspace"`).
        val banned = Regex("""rootfs/root/(workspace|\.dsh)|"root/(workspace|\.dsh)""")
        // Unit tests run in the module directory (android/app).
        val roots = listOf("src/main", "src/androidTest").map(::File)
        assertTrue("source roots not found from ${File("").absolutePath}", roots.all { it.isDirectory })
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java", "xml") }.flatMap { f ->
                f.readLines().withIndex().filter { banned.containsMatchIn(it.value) }.map { "${f.path}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        }
        assertTrue("host paths into the rootfs's user data (use AppPaths):\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
