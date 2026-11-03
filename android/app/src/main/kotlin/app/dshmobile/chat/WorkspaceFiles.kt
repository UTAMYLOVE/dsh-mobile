package app.dshmobile.chat

import java.io.File

/** One shareable file in the workspace: its path relative to the workspace root, size and change time. */
data class WorkspaceFile(val path: String, val bytes: Long, val modifiedMs: Long)

/**
 * The workspace's files, newest first (stage 2 §F: files made by bash or python can be shared too, not only write/edit outputs).
 * Skips the app's own data, dependency and VCS folders, and stops after [max] files so a huge tree never stalls the panel.
 */
object WorkspaceFiles {
    private val SKIP = setOf(".dshm", ".git", "node_modules", ".cache", "__pycache__", ".venv", "memory")

    fun list(root: File, max: Int = 2_000, show: Int = 40): List<WorkspaceFile> {
        if (!root.isDirectory) return emptyList()
        val base = root.canonicalFile
        val out = ArrayList<WorkspaceFile>()
        val stack = ArrayDeque(listOf(base))
        while (stack.isNotEmpty() && out.size < max) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (f.name in SKIP) continue
                // Never follow a link out of the workspace.
                val real = runCatching { f.canonicalFile }.getOrNull() ?: continue
                if (!real.path.startsWith(base.path + File.separator)) continue
                if (real.isDirectory) stack.add(real)
                else if (real.isFile) out += WorkspaceFile(real.path.removePrefix(base.path + File.separator), real.length(), real.lastModified())
            }
        }
        return out.sortedByDescending { it.modifiedMs }.take(show)
    }
}
