package app.dshmobile.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The experimental native mode's launcher: the official dsh (from the rootfs) on Android's own node, no proot.
 *
 * Programs (node, bash, ripgrep, git, curl, jq, sqlite3, zip, unzip, python3) and the file-lock addon ship as libdshn_*.so in [nativeLibDir] (native/build-runtime.py;
 * Android executes nothing from the app's data). [prepare] puts the programs on PATH by name as symlinks in `native/bin`
 * and copies the shim (`node --import`, see assets/dshn/shim.mjs) and the CA bundle next to them.
 *
 * Python's standard library and pip are data (assets/native/python.tar.xz), unpacked to `native/python` (PYTHONHOME). Its
 * extension modules are libraries in [nativeLibDir] like the others, linked into `lib-dynload` under the file names Python
 * looks for: an app may dlopen through a symlink into its native library directory; only exec of the app's data is refused.
 */
class NativeRunner(
    private val nativeLibDir: File,
    private val baseDir: File,
    private val home: File,
    private val tmpDir: File,
    private val assets: (String) -> InputStream?,
    private val niceBinary: File? = ProotRunner.niceIfUsable(File(ProotRunner.SYSTEM_NICE)),
    /**
     * Folders dsh knows by a proot name (`/root/workspace`, `/root/.dsh`) and where they are on the phone: conversations made
     * under proot keep their folder's proot name, and the shim maps paths below it (assets/dshn/shim.mjs, point 6).
     */
    private val remap: List<Pair<String, File>> = emptyList(),
    /** Told when part of the runtime (Python) could not be set up; dsh still starts without it. */
    private val log: (String) -> Unit = {},
) {
    private val binDir = File(baseDir, "bin")
    /** The libraries under their own sonames (symlinks to nativeLibraryDir), for LD_LIBRARY_PATH. */
    private val libLinks = File(baseDir, "lib")
    private val shim = File(baseDir, "shim.mjs")
    private val cert = File(baseDir, "ca-bundle.crt")
    /** PYTHONHOME: the unpacked python.tar.xz (lib/pythonX.Y/...), replaced as a whole when the archive changes. */
    val pythonHome = File(baseDir, "python")

    /** The runtime's manifest (program name to its libdshn file), or null when this APK has no native runtime. */
    val manifest: Manifest? by lazy { assets(MANIFEST)?.use { Manifest.parse(it.readBytes().toString(Charsets.UTF_8)) } }

    val available: Boolean get() = manifest?.let { m -> m.bins["node"]?.let { File(nativeLibDir, it).isFile } == true } == true

    fun program(name: String): File = File(nativeLibDir, requireNotNull(manifest?.bins?.get(name)) { "no $name in the native runtime" })

    /** bash for dsh's terminal-bash shell. */
    val shellPath: String get() = File(binDir, "bash").path

    /**
     * Creates the bin symlinks and refreshes the shim, the certificates and Python; idempotent and cheap enough for every
     * start (Python is unpacked only when its archive changed). A Python that cannot be set up is logged and left off PATH.
     */
    @Synchronized
    fun prepare() {
        val m = requireNotNull(manifest) { "this build has no native runtime" }
        binDir.mkdirs(); libLinks.mkdirs(); home.mkdirs(); tmpDir.mkdirs(); File(baseDir, "git-templates").mkdirs()
        val pythonPrograms = m.python?.programs.orEmpty()
        for ((name, lib) in m.bins) if (name !in pythonPrograms) link(File(binDir, name), File(nativeLibDir, lib))
        for ((soname, lib) in m.libs) link(File(libLinks, soname), File(nativeLibDir, lib))
        copyAsset("dshn/shim.mjs", shim)
        copyAsset("dshn/require-builtin.cjs", File(baseDir, "require-builtin.cjs"))
        copyAsset(DshLayout.REVIEW_JSON_ASSET, File(baseDir, "review-json.mjs"))
        copyAsset("native/ca-bundle.crt", cert)
        val py = m.python ?: return
        val ready = try {
            preparePython(py)
            true
        } catch (e: Exception) {
            log("python is unavailable: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        for (name in pythonPrograms) {
            val at = File(binDir, name)
            if (ready) link(at, File(nativeLibDir, m.bins.getValue(name))) else Files.deleteIfExists(at.toPath())
        }
    }

    private fun preparePython(py: Manifest.Python) {
        if (!unpackedFrom(py.sha256)) unpackPython(py)
        val stdlib = File(pythonHome, "lib/python${py.version}")
        val dynload = File(stdlib, "lib-dynload").apply { mkdirs() }
        for ((file, lib) in py.modules) link(File(dynload, file), File(nativeLibDir, lib))
        for (f in dynload.listFiles().orEmpty()) if (f.name !in py.modules && Files.isSymbolicLink(f.toPath())) Files.delete(f.toPath())
        // pip installs where it may write: with site-packages read-only a plain `pip install` goes to the user site
        // (PYTHONUSERBASE) by itself, and in a venv still to the venv. PIP_USER would refuse every install in a venv instead
        // ("Can not perform a '--user' install. User site-packages are not visible in this virtualenv").
        val sitePackages = File(stdlib, "site-packages")
        if (sitePackages.canWrite() && !sitePackages.setWritable(false, false)) throw IOException("cannot make $sitePackages read-only")
    }

    /** True when [pythonHome] was unpacked from the archive with this sha256. */
    private fun unpackedFrom(sha256: String): Boolean = try {
        File(pythonHome, PYTHON_MARKER).let { it.isFile && it.readText().trim() == sha256 }
    } catch (_: IOException) {
        false
    }

    /**
     * Unpacks the archive into `python.tmp`, checks it against the manifest's sha256 and swaps it in with renames
     * ([RootfsInstaller.replaceTree]), so an unpack that is cut short or fails never leaves a half Python behind.
     */
    private fun unpackPython(py: Manifest.Python) {
        val tmp = File(baseDir, "python.tmp")
        val old = File(baseDir, "python.old")
        RootfsInstaller.deleteTree(tmp)
        RootfsInstaller.deleteTree(old) // left by a swap that was cut short
        try {
            tmp.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(assets("native/${py.archive}")) { "missing asset native/${py.archive}" }.use { raw ->
                val hashed = DigestInputStream(raw.buffered(BUF), digest)
                val xz = XZCompressorInputStream(hashed)
                RootfsInstaller.extractTar(TarArchiveInputStream(xz), tmp)
                // What follows the tar's last entry (its padding, xz's index and checks) is part of the archive's hash too.
                val rest = ByteArray(BUF)
                while (xz.read(rest) >= 0) Unit
                while (hashed.read(rest) >= 0) Unit
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != py.sha256) throw IOException("native/${py.archive} has sha256 $got, the manifest says ${py.sha256}")
            File(tmp, PYTHON_MARKER).writeText(py.sha256)
            RootfsInstaller.replaceTree(tmp, pythonHome, old)
        } catch (e: Exception) {
            try { RootfsInstaller.deleteTree(tmp) } catch (cleanup: IOException) { e.addSuppressed(cleanup) }
            throw e
        }
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
            // Termux's curl and git look for certificates under Termux's own prefix, which the phone does not have.
            put("CURL_CA_BUNDLE", cert.path)
            put("GIT_SSL_CAINFO", cert.path)
            put("GIT_EXEC_PATH", binDir.path)
            // Termux's git looks for its templates under Termux's prefix and warns on every init; an empty folder is enough.
            put("GIT_TEMPLATE_DIR", File(baseDir, "git-templates").path)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_PAGER", "cat")
            put("PAGER", "cat")
            put("DSHN_FLOCK", program("flock-addon").path)
            put("DSHN_RG", File(binDir, "rg").path)
            if (manifest?.python != null) {
                // Termux's python looks for its standard library under Termux's prefix.
                put("PYTHONHOME", pythonHome.path)
                // Where pip installs outside a venv (site-packages is read-only, see preparePython) and Python finds it again.
                put("PYTHONUSERBASE", File(home, ".local").path)
                put("PIP_CERT", cert.path)
                put("PIP_DISABLE_PIP_VERSION_CHECK", "1")
            }
            if (remap.isNotEmpty()) put("DSHN_REMAP", remap.joinToString(";") { (guest, host) -> "$guest=${host.path}" })
            putAll(env)
        }
        return pb
    }

    data class Manifest(
        val bins: Map<String, String>,
        val libs: Map<String, String>,
        val packages: Map<String, String>,
        /** Null for a runtime built without Python. */
        val python: Python? = null,
    ) {
        /**
         * [version] is X.Y (lib/pythonX.Y); [archive] the asset under native/ with its [sha256]; [programs] the names in bins
         * that are Python (on PATH only once it is set up); [modules] each extension module's file name in lib-dynload to its
         * library.
         */
        data class Python(val version: String, val archive: String, val sha256: String, val programs: Set<String>, val modules: Map<String, String>)

        companion object {
            fun parse(json: String): Manifest {
                val o = Json.parseToJsonElement(json).jsonObject
                fun JsonObject.strings(key: String) = (this[key] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                val bins = o.strings("bins") + ("flock-addon" to o.getValue("flock").jsonPrimitive.content)
                val python = (o["python"] as? JsonObject)?.let { p ->
                    Python(
                        version = p.getValue("version").jsonPrimitive.content,
                        archive = p.getValue("archive").jsonPrimitive.content,
                        sha256 = p.getValue("sha256").jsonPrimitive.content,
                        programs = (p["programs"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                        modules = p.strings("modules"),
                    )
                }
                return Manifest(bins, o.strings("libs"), o.strings("packages"), python)
            }
        }
    }

    companion object {
        const val MANIFEST = "native/manifest.json"
        /** In [pythonHome]: the sha256 of the archive it was unpacked from. */
        const val PYTHON_MARKER = ".dshm-archive-sha256"
        private const val NICENESS = 10
        private const val BUF = 64 * 1024
    }
}
