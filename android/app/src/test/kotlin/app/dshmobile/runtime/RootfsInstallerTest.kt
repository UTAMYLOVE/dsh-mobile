package app.dshmobile.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

class RootfsInstallerTest {
    @get:Rule val tmpDir = TemporaryFolder()
    private lateinit var filesDir: File

    @Before fun setUp() { filesDir = tmpDir.newFolder("files") }

    private fun tarXz(version: String? = "1", build: TarArchiveOutputStream.() -> Unit = {}): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(bos)).use { t ->
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            t.dir("etc/")
            if (version != null) t.file("etc/dshm-rootfs-version", version.toByteArray())
            t.build()
        }
        return bos.toByteArray()
    }

    private fun TarArchiveOutputStream.dir(name: String) {
        putArchiveEntry(TarArchiveEntry(name).also { it.mode = 0b111_101_101 }); closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.file(name: String, data: ByteArray, mode: Int = 0b110_100_100) {
        putArchiveEntry(TarArchiveEntry(name).also { it.size = data.size.toLong(); it.mode = mode })
        write(data); closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.symlink(name: String, target: String) {
        putArchiveEntry(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).also { it.linkName = target })
        closeArchiveEntry()
    }

    private fun installer(bytes: ByteArray, version: String = "1") =
        RootfsInstaller({ name -> assertEquals("rootfs.tar.xz", name); ByteArrayInputStream(bytes) }, filesDir, version)

    private fun symlinksSupported(): Boolean = try {
        val l = File(tmpDir.root, "probe-link").toPath()
        Files.createSymbolicLink(l, File(tmpDir.root, "x").toPath()); Files.delete(l); true
    } catch (_: Exception) { false }

    @Test fun installIsIdempotentAndVersioned() {
        val inst = installer(tarXz())
        assertFalse(inst.isInstalled())
        inst.install {}
        assertTrue(inst.isInstalled())
        inst.install {} // reinstall over existing rootfs
        assertTrue(inst.isInstalled())
        File(filesDir, "rootfs/etc/dshm-rootfs-version").writeText("old")
        assertFalse(inst.isInstalled())
    }

    /** I-5: a new rootfs (a plugin change, a Node or Debian update) must never cost the user's memory or conversations. */
    @Test fun reinstallingTheRootfsKeepsTheUserDataAndMovesLegacyDataOut() {
        val paths = AppPaths(filesDir)
        val v1 = RootfsInstaller({ ByteArrayInputStream(tarXz("1") { dir("root/"); dir("root/workspace/") }) }, filesDir, "1", paths::migrateLegacy)
        v1.install {}
        // An older build kept the user's data inside the rootfs:
        File(paths.rootfs, "root/workspace/memory").mkdirs()
        File(paths.rootfs, "root/workspace/memory/2026-11-01.md").writeText("- legacy note")
        File(paths.rootfs, "root/.dsh").mkdirs()
        File(paths.rootfs, "root/.dsh/conversations.db").writeText("chats")

        val v2 = RootfsInstaller({ ByteArrayInputStream(tarXz("2") { dir("root/"); dir("root/workspace/") }) }, filesDir, "2", paths::migrateLegacy)
        v2.install {}
        assertTrue(v2.isInstalled())
        assertEquals("- legacy note", File(paths.workspace, "memory/2026-11-01.md").readText())
        assertEquals("chats", File(paths.dshHome, "conversations.db").readText())

        // Data written to the new place (through the binds) survives the next reinstall untouched.
        File(paths.workspace, "MEMORY.md").writeText("long term")
        val v3 = RootfsInstaller({ ByteArrayInputStream(tarXz("3") { dir("root/"); dir("root/workspace/") }) }, filesDir, "3", paths::migrateLegacy)
        v3.install {}
        assertEquals("long term", File(paths.workspace, "MEMORY.md").readText())
        assertEquals("- legacy note", File(paths.workspace, "memory/2026-11-01.md").readText())
        assertEquals("chats", File(paths.dshHome, "conversations.db").readText())
        assertEquals(setOf("rootfs", "userdata"), filesDir.list()!!.toSet())
    }

    @Test fun reportsProgressEndingAt100() {
        val seen = mutableListOf<Int>()
        installer(tarXz()).install { seen.add(it) }
        assertEquals(100, seen.last())
        assertTrue(seen.all { it in 0..100 } && seen == seen.sorted())
    }

    @Test fun interruptedInstallLeavesNoHalfRootfs() {
        val good = tarXz { file("big", ByteArray(200_000) { (it * 31).toByte() }) }
        val broken = RootfsInstaller({
            object : InputStream() {
                val src = ByteArrayInputStream(good, 0, good.size / 2)
                override fun read(): Int = src.read().takeIf { it >= 0 } ?: throw IOException("boom")
                override fun read(b: ByteArray, o: Int, l: Int): Int =
                    src.read(b, o, l).takeIf { it >= 0 } ?: throw IOException("boom")
            }
        }, filesDir, "1")
        try { broken.install {}; fail("expected IOException") } catch (_: IOException) {}
        assertFalse(broken.isInstalled())
        assertFalse(File(filesDir, "rootfs").exists())
        assertFalse(File(filesDir, "rootfs.tmp").exists())
    }

    @Test fun staleTmpIsRemovedAtStart() {
        File(filesDir, "rootfs.tmp/junk").apply { parentFile.mkdirs(); writeText("x") }
        val inst = installer(tarXz())
        inst.install {}
        assertTrue(inst.isInstalled())
        assertFalse(File(filesDir, "rootfs.tmp").exists())
        assertFalse(File(filesDir, "rootfs/junk").exists())
    }

    @Test fun wrongVersionInArchiveFailsAndLeavesNothing() {
        val inst = installer(tarXz(version = "2"), version = "1")
        try { inst.install {}; fail("expected IOException") } catch (_: IOException) {}
        assertFalse(File(filesDir, "rootfs").exists())
        assertFalse(File(filesDir, "rootfs.tmp").exists())
    }

    @Test fun rejectsTarSlip() {
        val inst = installer(tarXz { file("../evil.txt", "x".toByteArray()) })
        try { inst.install {}; fail("expected IOException") } catch (_: IOException) {}
        assertFalse(File(filesDir, "evil.txt").exists())
        assertFalse(File(filesDir, "rootfs").exists())
    }

    @Test fun rejectsWritingThroughExtractedSymlink() {
        assumeTrue(symlinksSupported())
        val outside = tmpDir.newFolder("outside")
        val inst = installer(tarXz {
            symlink("escape", outside.path)
            file("escape/pwned.txt", "x".toByteArray())
        })
        try { inst.install {}; fail("expected IOException") } catch (_: IOException) {}
        assertFalse(File(outside, "pwned.txt").exists())
    }

    @Test fun preservesSymlinksHardlinksAndExecBit() {
        assumeTrue(symlinksSupported())
        val inst = installer(tarXz {
            dir("opt/"); dir("opt/dsh/"); dir("opt/dsh/v1/"); dir("usr/"); dir("usr/bin/")
            file("usr/bin/tool", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101)
            symlink("opt/dsh/current", "/opt/dsh/v1")   // absolute target stored as-is
            symlink("bin", "usr/bin")                    // relative
            putArchiveEntry(TarArchiveEntry("usr/bin/tool2", TarArchiveEntry.LF_LINK).also { it.linkName = "usr/bin/tool" })
            closeArchiveEntry()
        })
        inst.install {}
        val root = File(filesDir, "rootfs")
        assertEquals("/opt/dsh/v1", Files.readSymbolicLink(File(root, "opt/dsh/current").toPath()).toString())
        assertEquals("usr/bin", Files.readSymbolicLink(File(root, "bin").toPath()).toString())
        assertEquals("#!/bin/sh\n", File(root, "usr/bin/tool2").readText())
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertTrue(File(root, "usr/bin/tool").canExecute())
            assertFalse(File(root, "etc/dshm-rootfs-version").canExecute())
        }
    }

    @Test fun reinstallDoesNotFollowSymlinksWhenDeletingOldRootfs() {
        assumeTrue(symlinksSupported())
        val victim = tmpDir.newFolder("victim").also { File(it, "keep.txt").writeText("keep") }
        val inst = installer(tarXz { symlink("link", victim.path) })
        inst.install {}
        inst.install {}
        assertTrue(File(victim, "keep.txt").exists())
        assertTrue(Files.isSymbolicLink(File(filesDir, "rootfs/link").toPath()))
        assertTrue(Files.exists(File(filesDir, "rootfs/link").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun leftoverRootfsOldIsRemovedWithoutFollowingSymlinks() {
        assumeTrue(symlinksSupported())
        val outside = tmpDir.newFolder("outside-old").also { File(it, "keep.txt").writeText("keep") }
        val old = File(filesDir, "rootfs.old")
        File(old, "etc").mkdirs()
        File(old, "etc/stale").writeText("x")
        Files.createSymbolicLink(File(old, "link").toPath(), outside.toPath())
        val inst = installer(tarXz())
        inst.install {}
        assertFalse(old.exists())
        assertTrue(File(outside, "keep.txt").exists())
        assertTrue(inst.isInstalled())
    }

    @Test fun upgradeSwapsInNewRootfsAndLeavesNoOldOrTmp() {
        installer(tarXz(version = "1", build = { file("only-in-v1", "a".toByteArray()) }), "1").install {}
        assertTrue(File(filesDir, "rootfs/only-in-v1").exists())
        val v2 = installer(tarXz(version = "2", build = { file("only-in-v2", "b".toByteArray()) }), "2")
        assertFalse(v2.isInstalled())
        v2.install {}
        assertTrue(v2.isInstalled())
        assertEquals("2", File(filesDir, "rootfs/etc/dshm-rootfs-version").readText().trim())
        assertTrue(File(filesDir, "rootfs/only-in-v2").exists())
        assertFalse(File(filesDir, "rootfs/only-in-v1").exists())
        assertFalse(File(filesDir, "rootfs.old").exists())
        assertFalse(File(filesDir, "rootfs.tmp").exists())
        assertEquals(setOf("rootfs"), filesDir.list()!!.toSet())
    }

    @Test fun failedUpgradeKeepsOldRootfsUsable() {
        installer(tarXz(version = "1"), "1").install {}
        val bad = installer(tarXz(version = "3"), "2") // archive marker mismatches expected
        try { bad.install {}; fail("expected IOException") } catch (_: IOException) {}
        assertTrue(installer(tarXz(version = "1"), "1").isInstalled())
        assertFalse(File(filesDir, "rootfs.old").exists())
        assertFalse(File(filesDir, "rootfs.tmp").exists())
    }
}
