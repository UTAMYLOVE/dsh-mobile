package app.dshmobile.agency.secure

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Syncing a folder, so a file just renamed into it is on disk before the caller goes on (commits a row that names it). */
internal object Durable {
    /** Best effort: some filesystems refuse to sync a folder, and the rename is then as durable as they make it. */
    fun syncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
