package app.dshmobile.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Streams the bundled `rootfs.tar.xz` into `filesDir/rootfs`. Extraction goes to `rootfs.tmp`
 * first and is renamed on success, so an interrupted install never leaves a half rootfs behind.
 */
class RootfsInstaller(
    private val open: (String) -> InputStream,
    private val filesDir: File,
    private val expectedVersion: String,
) {
    private val rootfs = File(filesDir, "rootfs")
    private val tmp = File(filesDir, "rootfs.tmp")

    fun isInstalled(): Boolean {
        val marker = File(rootfs, VERSION_MARKER)
        return try {
            marker.isFile && marker.readText().trim() == expectedVersion
        } catch (_: IOException) {
            false
        }
    }

    fun install(onProgress: (percent: Int) -> Unit) {
        deleteTree(tmp)
        filesDir.mkdirs()
        try {
            open(ASSET_NAME).use { raw ->
                val total = try { raw.available().toLong() } catch (_: IOException) { 0L }
                val counting = CountingInputStream(raw)
                var last = -1
                fun report() {
                    val pct = if (total > 0) (counting.count * 99 / total).toInt().coerceIn(0, 99) else 0
                    if (pct != last) { last = pct; onProgress(pct) }
                }
                tmp.mkdirs()
                TarArchiveInputStream(XZCompressorInputStream(counting.buffered(BUF))).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        extract(tar, entry as TarArchiveEntry)
                        report()
                        entry = tar.nextEntry
                    }
                }
            }
            val marker = File(tmp, VERSION_MARKER)
            if (!marker.isFile || marker.readText().trim() != expectedVersion) {
                throw IOException("rootfs version marker missing or not $expectedVersion")
            }
            deleteTree(rootfs)
            Files.move(tmp.toPath(), rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
            onProgress(100)
        } catch (t: Throwable) {
            deleteTree(tmp)
            throw t
        }
    }

    /** Resolves an archive path under [tmp], rejecting anything that escapes it (tar-slip). */
    private fun resolve(name: String): Path? {
        val root = tmp.toPath().toAbsolutePath().normalize()
        val p = root.resolve(name.trimStart('/')).normalize()
        if (!p.startsWith(root)) throw IOException("tar entry escapes destination: $name")
        if (p == root) return null
        // Never write through a symlink we extracted earlier (absolute links would escape).
        var parent = p.parent
        while (parent != null && parent != root) {
            if (Files.isSymbolicLink(parent)) throw IOException("tar entry passes through symlink: $name")
            parent = parent.parent
        }
        return p
    }

    private fun extract(tar: TarArchiveInputStream, e: TarArchiveEntry) {
        val dest = resolve(e.name) ?: return
        when {
            e.isDirectory -> {
                if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(dest)
                    Files.createDirectories(dest)
                }
                applyMode(dest, e.mode, dir = true)
            }
            e.isSymbolicLink -> {
                Files.createDirectories(dest.parent)
                Files.deleteIfExists(dest)
                Files.createSymbolicLink(dest, Paths.get(e.linkName))
            }
            e.isLink -> {
                val target = resolve(e.linkName) ?: throw IOException("bad hard link: ${e.name}")
                Files.createDirectories(dest.parent)
                Files.deleteIfExists(dest)
                try {
                    Files.createLink(dest, target)
                } catch (_: IOException) {
                    Files.copy(target, dest)
                } catch (_: UnsupportedOperationException) {
                    Files.copy(target, dest)
                }
            }
            e.isFile -> {
                Files.createDirectories(dest.parent)
                Files.deleteIfExists(dest)
                Files.newOutputStream(dest).use { out -> tar.copyTo(out, BUF) }
                applyMode(dest, e.mode, dir = false)
            }
            else -> Unit // devices / fifos: proot binds /dev, nothing to create
        }
    }

    private fun applyMode(p: Path, mode: Int, dir: Boolean) {
        // Always keep owner rw(x) so the app can still manage the tree; drop setuid/setgid/sticky.
        val m = (mode and 0b111_111_111) or (if (dir) 0b111_000_000 else 0b110_000_000)
        val all = PosixFilePermission.values() // OWNER_READ .. OTHERS_EXECUTE, in 0400..0001 order
        val perms = all.indices.filter { m and (0b100_000_000 shr it) != 0 }.mapTo(HashSet()) { all[it] }
        try {
            Files.setPosixFilePermissions(p, perms)
        } catch (_: UnsupportedOperationException) {
            if (m and 0b001_000_000 != 0) p.toFile().setExecutable(true, true)
        }
    }

    private class CountingInputStream(i: InputStream) : FilterInputStream(i) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        const val ASSET_NAME = "rootfs.tar.xz"
        const val VERSION_MARKER = "etc/dshm-rootfs-version"
        private const val BUF = 64 * 1024

        /** Recursive delete that never follows symlinks (extracted links may point anywhere). */
        internal fun deleteTree(f: File) {
            val p = f.toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, true)
                    dir.toFile().setExecutable(true, true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
