package app.dshmobile.runtime

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes the guest's `/etc/hosts` and `/etc/resolv.conf` (the docker-exported rootfs ships them empty or absent). */
object GuestNetworkFiles {
    private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    private const val HOSTS = "127.0.0.1 localhost\n::1 localhost ip6-localhost\n"
    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
    private val IPV6_CHARS = Regex("[0-9a-fA-F:.]+")

    fun write(rootfsDir: File, dnsServers: List<String>) {
        val etc = File(rootfsDir, "etc")
        // A link here (or at the files below) would make the write land outside the rootfs, so links are replaced.
        if (Files.isSymbolicLink(etc.toPath())) Files.delete(etc.toPath())
        etc.mkdirs()
        val servers = dnsServers.map { it.trim() }.filter(::isIpLiteral).distinct().ifEmpty { FALLBACK_DNS }
        replaceFile(File(etc, "hosts"), HOSTS)
        replaceFile(File(etc, "resolv.conf"), servers.joinToString("") { "nameserver $it\n" })
    }

    /** Writes a sibling temp file and renames it over [target]; rename replaces a symlink itself, never its destination. */
    private fun replaceFile(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.dshm-tmp")
        Files.deleteIfExists(tmp.toPath())
        Files.write(tmp.toPath(), text.toByteArray(Charsets.UTF_8))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun isIpLiteral(s: String): Boolean = when {
        IPV4.matches(s) -> true
        ':' in s && IPV6_CHARS.matches(s) -> try {
            InetAddress.getByName(s) // a literal containing ':' is parsed, never resolved
            true
        } catch (_: Exception) {
            false
        }
        else -> false
    }
}
