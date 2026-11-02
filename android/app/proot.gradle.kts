// Downloads Termux's proot + libtalloc + libandroid-shmem (pinned, sha256-verified) and places them in
// src/main/jniLibs/<abi>/ disguised as lib*.so so Android lets us exec them from nativeLibraryDir.
// The binaries are git-ignored; this task runs before every build.
import org.tukaani.xz.XZInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.tukaani:xz:1.10") }
}

data class TermuxDeb(val file: String, val sha256: String)
data class ProotDebs(val proot: TermuxDeb, val talloc: TermuxDeb, val shmem: TermuxDeb)

val termuxBase = "https://packages.termux.dev/apt/termux-main/pool/main"
// abi -> debs. proot's DT_NEEDED is libtalloc.so.2 (patched below), libandroid-shmem.so and libc.so; libandroid-shmem itself
// needs only liblog.so and libc.so (both part of Android). BundledNativeLibsTest checks this after every fetch.
val prootDebs = mapOf(
    "arm64-v8a" to ProotDebs(
        TermuxDeb("p/proot/proot_5.1.107.102_aarch64.deb", "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"),
        TermuxDeb("libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"),
        TermuxDeb("liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"),
    ),
    "x86_64" to ProotDebs(
        TermuxDeb("p/proot/proot_5.1.107.102_x86_64.deb", "2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758"),
        TermuxDeb("libt/libtalloc/libtalloc_2.5.0_x86_64.deb", "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530"),
        TermuxDeb("liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb", "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"),
    ),
)
val prefix = "./data/data/com.termux/files/usr/"
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val debCache = layout.buildDirectory.dir("proot-debs").get().asFile

fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun download(deb: TermuxDeb): ByteArray {
    val cached = File(debCache, deb.file.substringAfterLast('/'))
    if (cached.isFile && sha256(cached.readBytes()) == deb.sha256) return cached.readBytes()
    val bytes = URI("$termuxBase/${deb.file}").toURL().openStream().use { it.readBytes() }
    check(sha256(bytes) == deb.sha256) { "sha256 mismatch for ${deb.file}" }
    debCache.mkdirs()
    cached.writeBytes(bytes)
    return bytes
}

/** Returns the requested regular files (path in data.tar, e.g. "./data/.../bin/proot") from a .deb. */
fun debFiles(deb: ByteArray, wanted: Set<String>): Map<String, ByteArray> {
    check(String(deb, 0, 8, Charsets.US_ASCII) == "!<arch>\n") { "not an ar archive" }
    var p = 8
    while (p + 60 <= deb.size) {
        val name = String(deb, p, 16, Charsets.US_ASCII).trim()
        val size = String(deb, p + 48, 10, Charsets.US_ASCII).trim().toInt()
        p += 60
        if (name.startsWith("data.tar.xz")) {
            val tar = XZInputStream(deb.inputStream(p, size)).use { it.readBytes() }
            val out = HashMap<String, ByteArray>()
            var t = 0
            while (t + 512 <= tar.size && tar[t].toInt() != 0) {
                fun field(off: Int, len: Int) = String(tar, t + off, len, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val entry = field(345, 155).let { if (it.isEmpty()) "" else "$it/" } + field(0, 100)
                val sz = field(124, 12).trim().toLong(8).toInt()
                val type = tar[t + 156].toInt().toChar()
                if ((type == '0' || type == '\u0000') && entry in wanted) out[entry] = tar.copyOfRange(t + 512, t + 512 + sz)
                t += 512 + (sz + 511) / 512 * 512
            }
            return out
        }
        p += size + (size % 2)
    }
    error("data.tar.xz not found in deb")
}

/** proot's NEEDED entry is "libtalloc.so.2", but Android only extracts lib*.so; rename it in-place. */
fun patchTallocNeeded(bin: ByteArray): ByteArray {
    val from = "libtalloc.so.2\u0000".toByteArray(Charsets.US_ASCII)
    val to = "libtalloc.so\u0000\u0000".toByteArray(Charsets.US_ASCII)
    val hits = (0..bin.size - from.size).filter { i -> from.indices.all { bin[i + it] == from[it] } }
    check(hits.size == 1) { "expected exactly one libtalloc.so.2 NEEDED string in proot, found ${hits.size}" }
    val patched = bin.copyOf()
    to.copyInto(patched, hits[0])
    return patched
}

val fetchProot = tasks.register("fetchProot") {
    group = "build setup"
    description = "Downloads pinned Termux proot/libtalloc/libandroid-shmem into src/main/jniLibs"
    inputs.property("pins", prootDebs.toString())
    inputs.property("script", "v2")
    outputs.files(prootDebs.keys.flatMap { abi ->
        listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so").map { File(jniLibsDir, "$abi/$it") }
    })
    doLast {
        for ((abi, debs) in prootDebs) {
            val proot = debFiles(download(debs.proot), setOf(prefix + "bin/proot", prefix + "libexec/proot/loader"))
            val talloc = debFiles(download(debs.talloc), setOf(prefix + "lib/libtalloc.so.2.5.0"))
            val shmem = debFiles(download(debs.shmem), setOf(prefix + "lib/libandroid-shmem.so"))
            val dir = File(jniLibsDir, abi).also { it.mkdirs() }
            File(dir, "libproot.so").writeBytes(patchTallocNeeded(proot.getValue(prefix + "bin/proot")))
            File(dir, "libproot-loader.so").writeBytes(proot.getValue(prefix + "libexec/proot/loader"))
            File(dir, "libtalloc.so").writeBytes(talloc.getValue(prefix + "lib/libtalloc.so.2.5.0"))
            File(dir, "libandroid-shmem.so").writeBytes(shmem.getValue(prefix + "lib/libandroid-shmem.so"))
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchProot) }
