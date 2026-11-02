package app.dshmobile.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestNetworkFilesTest {
    private val base: File = Files.createTempDirectory("guestnet").toFile()
    private val rootfs = File(base, "rootfs").also { it.mkdirs() }

    @After fun cleanup() { base.deleteRecursively() }

    private fun resolv() = File(rootfs, "etc/resolv.conf").readText()
    private fun hosts() = File(rootfs, "etc/hosts").readText()

    @Test fun writesHostsAndOneNameserverPerDnsServer() {
        GuestNetworkFiles.write(rootfs, listOf("9.9.9.9", "2606:4700:4700::1111"))
        assertEquals("127.0.0.1 localhost\n::1 localhost ip6-localhost\n", hosts())
        assertEquals("nameserver 9.9.9.9\nnameserver 2606:4700:4700::1111\n", resolv())
    }

    @Test fun dropsInvalidEntries() {
        GuestNetworkFiles.write(rootfs, listOf("evil.example.com", "8.8.4.4", "999.1.1.1", "1.2.3", "fe80::1%wlan0", "9.9.9.9; rm -rf /", "", "1.1.1.1\nnameserver 6.6.6.6"))
        assertEquals("nameserver 8.8.4.4\n", resolv())
    }

    @Test fun emptyOrAllInvalidFallsBackToPublicResolvers() {
        GuestNetworkFiles.write(rootfs, emptyList())
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", resolv())
        GuestNetworkFiles.write(rootfs, listOf("not-an-ip"))
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", resolv())
    }

    @Test fun overwritesExistingFilesAndDeduplicates() {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 10.0.0.1\n")
        GuestNetworkFiles.write(rootfs, listOf("8.8.4.4", "8.8.4.4"))
        assertEquals("nameserver 8.8.4.4\n", resolv())
    }

    @Test fun symlinkedHostsIsReplacedNotFollowed() {
        val outside = File(base, "outside-hosts").also { it.writeText("untouched") }
        val outsideResolv = File(base, "outside-resolv").also { it.writeText("untouched") }
        File(rootfs, "etc").mkdirs()
        Files.createSymbolicLink(File(rootfs, "etc/hosts").toPath(), outside.toPath())
        Files.createSymbolicLink(File(rootfs, "etc/resolv.conf").toPath(), outsideResolv.toPath())

        GuestNetworkFiles.write(rootfs, listOf("9.9.9.9"))

        assertEquals("untouched", outside.readText())
        assertEquals("untouched", outsideResolv.readText())
        assertFalse(Files.isSymbolicLink(File(rootfs, "etc/hosts").toPath()))
        assertFalse(Files.isSymbolicLink(File(rootfs, "etc/resolv.conf").toPath()))
        assertTrue(hosts().startsWith("127.0.0.1 localhost"))
        assertEquals("nameserver 9.9.9.9\n", resolv())
    }

    @Test fun symlinkedEtcDirectoryIsReplacedNotFollowed() {
        val outsideEtc = File(base, "outside-etc").also { it.mkdirs() }
        Files.createSymbolicLink(File(rootfs, "etc").toPath(), outsideEtc.toPath())

        GuestNetworkFiles.write(rootfs, listOf("9.9.9.9"))

        assertEquals(0, outsideEtc.list()!!.size)
        assertTrue(File(rootfs, "etc/hosts").isFile)
    }
}
