package app.dshmobile.agency.migrate

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip of a legacy folder for the share sheet (設定 › 舊資料, arch T09): his old dsh conversations and novels
 * (`filesDir/userdata`), or the coach folder before it is deleted. Every regular file goes in under its path relative to the
 * folder, streamed (a large file never has to fit in memory). A symbolic link is never followed nor stored (the dsh image had
 * links to the phone's own folders); a file that cannot be read is left out and counted. The original folder is not changed.
 */
object LegacyExport {
    data class Result(val zip: File, val files: Int, val skipped: Int)

    /** Writes `out` (its folder is made); throws [IOException] only when the zip itself cannot be written. */
    fun zip(folder: File, out: File): Result {
        out.parentFile?.mkdirs()
        var files = 0
        var skipped = 0
        val root = folder.toPath()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@use
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val input = try {
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: IOException) {
                        skipped++
                        return FileVisitResult.CONTINUE
                    }
                    input.use {
                        zip.putNextEntry(ZipEntry(root.relativize(file).toFile().invariantSeparatorsPath))
                        it.copyTo(zip)
                        zip.closeEntry()
                    }
                    files++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    skipped++
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return Result(out, files, skipped)
    }

    /** Bytes of every regular file under [folder], links not followed (for 「約 N MB」). */
    fun size(folder: File): Long {
        if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }
}
