package app.dshmobile.agency.migrate

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Arch T09: the dsh program files go at start; his old chats, novels and coach folders never do. Every file is invented. */
class LegacyCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files by lazy { tmp.newFolder("files") }
    private val cache by lazy { tmp.newFolder("cache") }

    private fun put(dir: File, rel: String, text: String = rel) = File(dir, rel).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun theProgramFoldersGoAndHisDataStays() {
        put(files, "rootfs/usr/bin/node")
        put(files, "rootfs/opt/dsh/1.2.3/dsh.js")
        File(files, "rootfs/usr/bin").setWritable(false) // the image had read-only folders
        put(files, "native/libdshn_node.so")
        put(cache, "proot/tmp-1")
        put(cache, "native-tmp/x")
        put(files, "userdata/workspace/novels/chapter-1.md", "虛構")
        put(files, "coach.migrated-1795300000000/state.json", "{}")
        put(files, "coach/state.json", "{}")
        put(files, "look/me.jpg")
        put(cache, "camera/pending.jpg")

        val removed = LegacyCleanup(files, cache).run()
        assertEquals(listOf("rootfs", "native", "proot", "native-tmp"), removed)
        listOf(File(files, "rootfs"), File(files, "native"), File(cache, "proot"), File(cache, "native-tmp")).forEach { assertFalse(it.path, it.exists()) }
        assertEquals("虛構", File(files, "userdata/workspace/novels/chapter-1.md").readText())
        listOf("coach.migrated-1795300000000/state.json", "coach/state.json", "look/me.jpg").forEach { assertTrue(it, File(files, it).isFile) }
        assertTrue(File(cache, "camera/pending.jpg").isFile)
        // A second start has nothing to do.
        assertEquals(emptyList<String>(), LegacyCleanup(files, cache).run())
    }

    @Test fun aLinkInTheImageIsRemovedNotFollowed() {
        val outside = put(files, "userdata/home/keep.txt", "his")
        File(files, "rootfs/root").mkdirs()
        Files.createSymbolicLink(File(files, "rootfs/root/home").toPath(), outside.parentFile.toPath())
        LegacyCleanup(files, cache).run()
        assertFalse(File(files, "rootfs").exists())
        assertEquals("his", outside.readText())
    }

    @Test fun theExportZipHasEveryFileAndNoLink() {
        val src = tmp.newFolder("userdata")
        put(src, "a.txt", "一")
        put(src, "deep/b.md", "二")
        val outside = put(tmp.root, "secret.txt", "不可以")
        Files.createSymbolicLink(File(src, "link.txt").toPath(), outside.toPath())
        val r = LegacyExport.zip(src, File(tmp.root, "out/old.zip"))
        assertEquals(2, r.files)
        val entries = ZipFile(r.zip).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
        assertEquals(mapOf("a.txt" to "一", "deep/b.md" to "二"), entries)
        assertEquals(("一" + "二").toByteArray().size.toLong(), LegacyExport.size(src))
        assertEquals(0, LegacyExport.zip(File(tmp.root, "missing"), File(tmp.root, "out/empty.zip")).files)
    }
}
