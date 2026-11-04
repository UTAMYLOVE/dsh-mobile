package app.dshmobile.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The experimental native mode's launcher: the official dsh (from the rootfs) on Android's own node, no proot.
 *
 * Programs (node, bash, ripgrep, git) and the file-lock addon ship as libdshn_*.so in [nativeLibDir] (native/build-runtime.py;
 * Android executes nothing from the app's data). [prepare] puts the programs on PATH by name as symlinks in `native/bin`
 * and copies the shim (`node --import`, see assets/dshn/shim.mjs) and the CA bundle next to them.
 */
class NativeRunner(
    private val nativeLibDir: File,
    private val baseDir: File,
    private val home: File,
    private val tmpDir: File,
    private val assets: (String) -> InputStream?,
    private val niceBinary: File? = ProotRunner.niceIfUsable(File(ProotRunner.SYSTEM_NICE)),
) {
    private val binDir = File(baseDir, "bin")
    /** The libraries under their own sonames (symlinks to nativeLibraryDir), for LD_LIBRARY_PATH. */
    private val libLinks = File(baseDir, "lib")
    private val shim = File(baseDir, "shim.mjs")
    private val cert = File(baseDir, "cert.pem")

    /** The runtime's manifest (program name to its libdshn file), or null when this APK has no native runtime. */
    val manifest: Manifest? by lazy { assets(MANIFEST)?.use { Manifest.parse(it.readBytes().toString(Charsets.UTF_8)) } }

    val available: Boolean get() = manifest?.let { m -> m.bins["node"]?.let { File(nativeLibDir, it).isFile } == true } == true

    fun program(name: String): File = File(nativeLibDir, requireNotNull(manifest?.bins?.get(name)) { "no $name in the native runtime" })

    /** bash for dsh's terminal-bash shell. */
    val shellPath: String get() = File(binDir, "bash").path

    /** Creates the bin symlinks and refreshes the shim and certificates; idempotent and cheap enough for every start. */
    fun prepare() {
        val m = requireNotNull(manifest) { "this build has no native runtime" }
        binDir.mkdirs(); libLinks.mkdirs(); home.mkdirs(); tmpDir.mkdirs()
        for ((name, lib) in m.bins) link(File(binDir, name), File(nativeLibDir, lib))
        for ((soname, lib) in m.libs) link(File(libLinks, soname), File(nativeLibDir, lib))
        copyAsset("dshn/shim.mjs", shim)
        copyAsset("dshn/require-builtin.cjs", File(baseDir, "require-builtin.cjs"))
        copyAsset("native/cert.pem", cert)
    }

    /** nativeLibraryDir changes when the app is updated: a link to the old directory is replaced. */
    private fun link(at: File, target: File) {
        val link = at.toPath()
        if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target.toPath()) return
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, target.toPath())
    }

    private fun copyAsset(name: String, dst: File) {
        val bytes = requireNotNull(assets(name)) { "missing asset $name" }.use { it.readBytes() }
        if (Files.exists(dst.toPath(), LinkOption.NOFOLLOW_LINKS) && dst.readBytes().contentEquals(bytes)) return
        val tmp = File(dst.path + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** node with the shim, for [DshLayout.node]. */
    fun node(): List<String> = buildList {
        niceBinary?.let { add(it.path); add("-n"); add(NICENESS.toString()) }
        add(program("node").path)
        add("--import"); add(shim.toURI().toString())
        add("--expose-internals")
    }

    /** A process for [args] (from [node] on) in [workDir], with only the environment dsh and the agent's tools need plus [env]. */
    fun command(args: List<String>, env: Map<String, String>, workDir: File): ProcessBuilder {
        workDir.mkdirs()
        val pb = ProcessBuilder(args).directory(workDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", "${libLinks.path}:${nativeLibDir.path}")
            put("PATH", "${binDir.path}:/system/bin")
            put("HOME", home.path)
            put("TMPDIR", tmpDir.path)
            put("SHELL", shellPath)
            put("LANG", "C.UTF-8")
            put("SSL_CERT_FILE", cert.path)
            put("NODE_EXTRA_CA_CERTS", cert.path)
            put("GIT_EXEC_PATH", binDir.path)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_PAGER", "cat")
            put("PAGER", "cat")
            put("DSHN_FLOCK", program("flock-addon").path)
            put("DSHN_RG", File(binDir, "rg").path)
            putAll(env)
        }
        return pb
    }

    data class Manifest(val bins: Map<String, String>, val libs: Map<String, String>, val packages: Map<String, String>) {
        companion object {
            fun parse(json: String): Manifest {
                val o = Json.parseToJsonElement(json).jsonObject
                fun strings(key: String) = (o[key] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                val bins = strings("bins") + ("flock-addon" to o.getValue("flock").jsonPrimitive.content)
                val packages = strings("packages")
                return Manifest(bins, strings("libs"), packages)
            }
        }
    }

    companion object {
        const val MANIFEST = "native/manifest.json"
        private const val NICENESS = 10
    }
}
