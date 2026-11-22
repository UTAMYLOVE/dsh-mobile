package app.dshmobile.agency.migrate

import java.io.File

/**
 * Frees what the old dsh runtime left on the phone (arch T09, §1.14 step 6): its program files only — the Linux image
 * (`filesDir/rootfs`), the native runtime (`filesDir/native`) and their caches (`cache/proot`, `cache/native-tmp`), about
 * 150 MB that nothing reads any more. Never touched: `filesDir/userdata` (his old conversations and novels, kept until he
 * decides; 設定 › 舊資料 can share them as a zip), the coach folder and `coach.migrated-*` (his imported records' originals),
 * and everything else. A symbolic link is removed as a link, never followed. Safe to run at every start.
 */
class LegacyCleanup(private val filesDir: File, private val cacheDir: File) {
    companion object {
        /** His old dsh conversations and novels: never deleted here. */
        const val USER_DATA = "userdata"
    }

    /** The program folders it deletes, relative to their roots. */
    fun targets(): List<File> = listOf(File(filesDir, "rootfs"), File(filesDir, "native"), File(cacheDir, "proot"), File(cacheDir, "native-tmp"))

    /** Deletes what is there; returns the folders it removed (a failure is reported through [log], never thrown). */
    fun run(log: (String) -> Unit = {}): List<String> {
        val removed = mutableListOf<String>()
        for (dir in targets()) {
            if (!dir.exists() && !isLink(dir)) continue
            val ok = try {
                deleteTree(dir)
            } catch (e: Exception) {
                false
            }
            if (ok) removed += dir.name else log("could not delete ${dir.name} fully")
        }
        if (removed.isNotEmpty()) log("freed ${removed.joinToString()}")
        return removed
    }

    private fun isLink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())

    /** Depth first; a link is deleted itself and never entered (the image held links to the phone's own folders). */
    private fun deleteTree(f: File): Boolean {
        if (!isLink(f) && f.isDirectory) {
            // The image's folders can be read-only: a child can only go once its folder is writable.
            f.setWritable(true)
            f.listFiles()?.forEach { deleteTree(it) }
        }
        return f.delete() || !f.exists() && !isLink(f)
    }
}
