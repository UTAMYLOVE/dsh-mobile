package app.dshmobile.export

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object WorkspacePaths {
    /**
     * Resolves [rel] below [root] and returns the existing regular file it names, or null when [rel] is empty, contains NUL,
     * is absolute, or (after `..` and every symbolic link on the way, directories included, are resolved) leaves [root].
     * A link that stays inside [root] is fine.
     */
    fun resolveInside(root: File, rel: String): File? {
        if (rel.isEmpty() || rel.indexOf('\u0000') >= 0) return null
        if (rel.startsWith("/") || rel.startsWith("\\") || File(rel).isAbsolute) return null
        return try {
            val realRoot = root.toPath().toRealPath()
            val real = realRoot.resolve(rel).toRealPath()
            if (isInside(realRoot, real) && Files.isRegularFile(real)) real.toFile() else null
        } catch (_: IOException) {
            null
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }
    }

    /** True when [real] (already a real path) equals or lies below [realRoot]; compares path components, not string prefixes. */
    internal fun isInside(realRoot: Path, real: Path): Boolean = real.startsWith(realRoot)
}
