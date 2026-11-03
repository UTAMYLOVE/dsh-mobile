package app.dshmobile.export

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.dshmobile.log.ErrorLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hands files to other apps through the system share sheet only: copies go to `cacheDir/export/`, which the FileProvider
 * (`<applicationId>.export`, see res/xml/file_paths.xml) exposes with a one-shot read grant. No storage permission is involved.
 */
class Exporter(
    private val context: Context,
    private val workspace: File,
    private val errorLog: ErrorLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val exportDir: File get() = File(context.cacheDir, "export")

    /**
     * Shares [file], which must be a regular file inside the workspace or inside `cacheDir/export` (symbolic links count by
     * where they resolve to), else [IllegalArgumentException]. Must be called from a context that can start an activity.
     */
    fun share(file: File, mime: String = guessMime(file)) {
        context.startActivity(buildChooser(file, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares the workspace file at [rel] (relative to the workspace); refuses anything [WorkspacePaths.resolveInside] refuses. */
    fun shareWorkspaceFile(rel: String) {
        val file = WorkspacePaths.resolveInside(workspace, rel)
            ?: throw IllegalArgumentException("not a file inside the workspace")
        share(file)
    }

    /** The chooser [share] starts; split out so tests can inspect it. */
    internal fun buildChooser(file: File, mime: String = guessMime(file)): Intent {
        val source = checkShareable(file)
        val dir = exportDir
        dir.mkdirs()
        val inExport = source.startsWith(dir.toPath().toRealPath())
        // A file that is about to be shared must not be swept away as stale.
        if (inExport) file.setLastModified(clock())
        clearExports()
        val copy = File(dir, file.name)
        if (!inExport) {
            Files.copy(source, copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", if (inExport) source.toFile() else copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** `cacheDir/export/dsh-memory-<yyyyMMdd-HHmm>.zip` with MEMORY.md, USER.md and every `.md` below `memory/`. */
    fun memoryBackupZip(): File {
        val out = newExportFile("dsh-memory")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (name in listOf("MEMORY.md", "USER.md")) addRegularFile(zip, name, File(workspace, name))
            val memory = File(workspace, "memory")
            if (isRealDirectory(memory)) {
                memory.walkTopDown()
                    .onEnter { isRealDirectory(it) }
                    .filter { it.name.endsWith(".md") }
                    .sortedBy { it.path }
                    .forEach { addRegularFile(zip, it.relativeTo(workspace).invariantSeparatorsPath, it) }
            }
        }
        return out
    }

    /**
     * `cacheDir/export/dsh-workspace-<yyyyMMdd-HHmm>.zip` with every regular file of the workspace, under its path relative to the
     * workspace. Symbolic links are never followed nor stored (a link could point out of the workspace, e.g. at the app's own
     * files); devices, fifos and empty directories are left out. Blocking file I/O: call it off the main thread.
     */
    fun workspaceZip(): File {
        val out = newExportFile("dsh-workspace")
        val root = workspace.toPath()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            if (!isRealDirectory(workspace)) return@use
            // walkFileTree without FOLLOW_LINKS reports a link as a (non-regular) file and never descends through it.
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) addRegularFile(zip, root.relativize(file).toFile().invariantSeparatorsPath, file.toFile())
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return out
    }

    /** `cacheDir/export/dsh-error-log-<time>.zip` with the scrubbed `error.log` and (when present) `error.log.1`. */
    fun errorLogZip(): File {
        val out = newExportFile("dsh-error-log")
        val log = errorLog.file()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in listOf(log, File(log.parentFile, "error.log.1"))) {
                if (!f.isFile) continue
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (_: IOException) {
                    continue
                }
                zip.putNextEntry(ZipEntry(f.name))
                zip.write(SecretScrubber.scrub(text).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out
    }

    /** Deletes exports older than one hour. */
    fun clearExports() {
        val cutoff = clock() - STALE_AFTER_MS
        exportDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun newExportFile(prefix: String): File {
        val dir = exportDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(clock()))
        return File(dir, "$prefix-$stamp.zip")
    }

    /** The real path of [file] when it is a regular file inside the workspace or the export directory, else throws. */
    private fun checkShareable(file: File): java.nio.file.Path {
        val real = try {
            file.toPath().toRealPath()
        } catch (e: IOException) {
            throw IllegalArgumentException("cannot read ${file.name}", e)
        }
        if (!Files.isRegularFile(real)) throw IllegalArgumentException("${file.name} is not a regular file")
        val roots = listOf(workspace, exportDir).mapNotNull {
            try {
                it.toPath().toRealPath()
            } catch (_: IOException) {
                null
            }
        }
        if (roots.none { WorkspacePaths.isInside(it, real) }) {
            throw IllegalArgumentException("${file.name} is outside the workspace")
        }
        return real
    }

    private fun isRealDirectory(f: File) =
        Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun addRegularFile(zip: ZipOutputStream, entryName: String, f: File) {
        val path = f.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
        val input = try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return // vanished (or turned into a link) since the check: a backup skips what is gone
        }
        input.use {
            zip.putNextEntry(ZipEntry(entryName))
            it.copyTo(zip)
            zip.closeEntry()
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 3_600_000L

        fun guessMime(file: File): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
                ?: "application/octet-stream"
    }
}
