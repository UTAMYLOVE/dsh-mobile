package app.dshmobile.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/**
 * Python in the native runtime (native/build-runtime.py): its programs and extension modules are libdshn_*.so in the native
 * library directory, the standard library and pip are assets/native/python.tar.xz, which [NativeRunner.prepare] unpacks to
 * native/python once per archive and links the modules into.
 */
class NativePythonTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var libDir: File
    private lateinit var base: File
    private lateinit var home: File
    private val logged = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @Before fun setUp() {
        libDir = tmp.newFolder("nativeLibraryDir")
        for (f in listOf("libdshn_bin_node.so", "libdshn_bin_bash.so", "libdshn_bin_python3.so", "libdshn_flock.so", "libdshn_libpython3_14_so.so", "libdshn_py__ssl.so", "libdshn_py_zlib.so")) {
            File(libDir, f).writeText("elf")
        }
        base = File(tmp.root, "native")
        home = File(tmp.root, "home")
    }

    /** site-packages is made read-only; put write access back so the temporary folder can be removed. */
    @After fun cleanUp() = RootfsInstaller.deleteTree(base)

    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(bos)).use { t ->
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (d in listOf("lib/python3.14/", "lib/python3.14/lib-dynload/", "lib/python3.14/site-packages/")) {
                t.putArchiveEntry(TarArchiveEntry(d).also { it.mode = 0b111_101_101 }); t.closeArchiveEntry()
            }
            for ((name, text) in files) {
                val data = text.toByteArray()
                t.putArchiveEntry(TarArchiveEntry(name).also { it.size = data.size.toLong(); it.mode = 0b110_100_100 })
                t.write(data); t.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(sha: String, modules: String = MODULES) = """{"abi":"x86_64","flock":"libdshn_flock.so",
        "bins":{"node":"libdshn_bin_node.so","bash":"libdshn_bin_bash.so","python3":"libdshn_bin_python3.so","python":"libdshn_bin_python3.so","python3.14":"libdshn_bin_python3.so"},
        "libs":{"libpython3.14.so":"libdshn_libpython3_14_so.so"},"packages":{"python":"3.14.10"},
        "python":{"version":"3.14","archive":"python.tar.xz","sha256":"$sha","programs":["python","python3","python3.14"],"modules":$modules}}"""

    private fun runner(archive: ByteArray, sha: String = sha256(archive), libs: File = libDir, modules: String = MODULES): NativeRunner {
        val assets = mapOf(
            "native/manifest.json" to manifest(sha, modules).toByteArray(), "native/python.tar.xz" to archive,
            "dshn/shim.mjs" to "// shim".toByteArray(), "dshn/require-builtin.cjs" to "//".toByteArray(), "native/ca-bundle.crt" to "CERT".toByteArray(),
        )
        return NativeRunner(libs, base, home, File(tmp.root, "tmp"), assets = { name -> opened += name; assets[name]?.inputStream() }, niceBinary = null, log = { logged += it })
    }

    private val stdlib get() = File(base, "python/lib/python3.14")
    private fun target(f: File) = Files.readSymbolicLink(f.toPath()).toFile()
    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    @Test fun theManifestDescribesPython() {
        val m = NativeRunner.Manifest.parse(manifest("abc"))
        val py = m.python!!
        assertEquals("3.14", py.version)
        assertEquals("python.tar.xz", py.archive)
        assertEquals("abc", py.sha256)
        assertEquals(setOf("python", "python3", "python3.14"), py.programs)
        assertEquals("libdshn_py__ssl.so", py.modules["_ssl.cpython-314-x86_64-linux-android.so"])
        assertEquals(2, py.modules.size)
        assertEquals("libdshn_bin_python3.so", m.bins["python3"])
        assertEquals("libdshn_flock.so", m.bins["flock-addon"])
        assertNull("a runtime built before Python", NativeRunner.Manifest.parse("""{"bins":{"node":"n.so"},"libs":{},"flock":"f.so"}""").python)
    }

    @Test fun prepareUnpacksPythonOnceAndLinksItsModulesAndPrograms() {
        val r = runner(archive("lib/python3.14/os.py" to "# os", "lib/python3.14/site-packages/pip/__init__.py" to "# pip"))
        r.prepare()
        assertEquals("# os", File(stdlib, "os.py").readText())
        assertEquals("# pip", File(stdlib, "site-packages/pip/__init__.py").readText())
        assertEquals(File(libDir, "libdshn_py__ssl.so"), target(File(stdlib, "lib-dynload/_ssl.cpython-314-x86_64-linux-android.so")))
        assertEquals(File(libDir, "libdshn_py_zlib.so"), target(File(stdlib, "lib-dynload/zlib.cpython-314-x86_64-linux-android.so")))
        for (name in listOf("python3", "python", "python3.14")) assertEquals(name, File(libDir, "libdshn_bin_python3.so"), target(File(base, "bin/$name")))
        assertEquals(File(libDir, "libdshn_libpython3_14_so.so"), target(File(base, "lib/libpython3.14.so")))
        // pip then installs to the user site by itself (and still into a venv).
        val perms = Files.getPosixFilePermissions(File(stdlib, "site-packages").toPath())
        assertFalse(perms.toString(), PosixFilePermission.OWNER_WRITE in perms || PosixFilePermission.GROUP_WRITE in perms || PosixFilePermission.OTHERS_WRITE in perms)
        assertTrue("the standard library stays writable for Python's own __pycache__", stdlib.canWrite())
        assertFalse(exists(File(base, "python.tmp")) || exists(File(base, "python.old")))
        assertEquals(listOf<String>(), logged)

        // Every later start: no second unpack (a file Python wrote stays), the links are checked.
        File(stdlib, "__pycache__").mkdirs()
        File(stdlib, "__pycache__/os.cpython-314.pyc").writeText("pyc")
        val unpacks = opened.count { it == "native/python.tar.xz" }
        r.prepare()
        assertEquals(unpacks, opened.count { it == "native/python.tar.xz" })
        assertTrue(File(stdlib, "__pycache__/os.cpython-314.pyc").isFile)
        assertEquals(1, unpacks)
    }

    @Test fun anUpdatedAppRelinksTheModulesWithoutUnpackingAgain() {
        val bytes = archive("lib/python3.14/os.py" to "# os")
        runner(bytes).prepare()
        File(stdlib, "keep.txt").writeText("x")
        val newLibs = tmp.newFolder("nativeLibraryDir2")
        libDir.listFiles()!!.forEach { it.copyTo(File(newLibs, it.name)) }
        // One module fewer: its link goes.
        opened.clear()
        runner(bytes, libs = newLibs, modules = """{"_ssl.cpython-314-x86_64-linux-android.so":"libdshn_py__ssl.so"}""").prepare()
        assertFalse(opened.contains("native/python.tar.xz"))
        assertTrue(File(stdlib, "keep.txt").isFile)
        assertEquals(File(newLibs, "libdshn_py__ssl.so"), target(File(stdlib, "lib-dynload/_ssl.cpython-314-x86_64-linux-android.so")))
        assertFalse(exists(File(stdlib, "lib-dynload/zlib.cpython-314-x86_64-linux-android.so")))
        assertEquals(File(newLibs, "libdshn_bin_python3.so"), target(File(base, "bin/python3")))
    }

    @Test fun aNewArchiveReplacesTheWholePython() {
        runner(archive("lib/python3.14/os.py" to "# old", "lib/python3.14/gone.py" to "#")).prepare()
        File(stdlib, "__pycache__").mkdirs()
        runner(archive("lib/python3.14/os.py" to "# new")).prepare()
        assertEquals("# new", File(stdlib, "os.py").readText())
        assertFalse(File(stdlib, "gone.py").exists())
        assertFalse(File(stdlib, "__pycache__").exists())
        assertTrue(exists(File(stdlib, "lib-dynload/_ssl.cpython-314-x86_64-linux-android.so")))
        assertFalse(exists(File(base, "python.tmp")) || exists(File(base, "python.old")))
    }

    @Test fun aBadArchiveLeavesPythonOffPathAndTheRestWorking() {
        val good = archive("lib/python3.14/os.py" to "# os")
        runner(good).prepare()
        assertTrue(exists(File(base, "bin/python3")))

        // The manifest names another archive than the one in the APK: nothing of it is used.
        val other = archive("lib/python3.14/os.py" to "# other")
        val r = runner(other, sha = sha256(good).replaceRange(0, 4, "0000"))
        r.prepare()
        for (name in listOf("python3", "python", "python3.14")) assertFalse(name, exists(File(base, "bin/$name")))
        assertEquals(File(libDir, "libdshn_bin_node.so"), target(File(base, "bin/node")))
        assertEquals("// shim", File(base, "shim.mjs").readText())
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().startsWith("python is unavailable: IOException: native/python.tar.xz has sha256"))
        assertFalse(exists(File(base, "python.tmp")))
        assertEquals("the old Python is not touched", "# os", File(stdlib, "os.py").readText())

        // A broken archive, the same.
        logged.clear()
        val broken = good.copyOf(good.size / 2)
        runner(broken).prepare()
        assertFalse(exists(File(base, "bin/python3")))
        assertEquals(1, logged.size)
        assertTrue(exists(File(base, "bin/bash")))
        assertFalse(exists(File(base, "python.tmp")))

        // And the right one brings it back.
        runner(good).prepare()
        assertTrue(exists(File(base, "bin/python3")))
    }

    @Test fun commandGivesPythonItsHomeAndPipItsCertificatesAndUserSite() {
        val r = runner(archive("lib/python3.14/os.py" to "# os"))
        r.prepare()
        val env = r.command(r.node(), emptyMap(), File(tmp.root, "ws")).environment()
        assertEquals(File(base, "python").path, env["PYTHONHOME"])
        assertEquals(File(home, ".local").path, env["PYTHONUSERBASE"])
        assertEquals(File(base, "ca-bundle.crt").path, env["PIP_CERT"])
        assertEquals("1", env["PIP_DISABLE_PIP_VERSION_CHECK"])
        assertNull("PIP_USER would break every install in a venv", env["PIP_USER"])

        val noPython = """{"bins":{"node":"libdshn_bin_node.so","bash":"libdshn_bin_bash.so"},"libs":{},"flock":"libdshn_flock.so"}"""
        val assets = mapOf("native/manifest.json" to noPython, "dshn/shim.mjs" to "//", "dshn/require-builtin.cjs" to "//", "native/ca-bundle.crt" to "C")
        val old = NativeRunner(libDir, File(tmp.root, "native-old"), home, File(tmp.root, "tmp"), assets = { assets[it]?.byteInputStream() }, niceBinary = null)
        old.prepare()
        assertNull(old.command(old.node(), emptyMap(), File(tmp.root, "ws")).environment()["PYTHONHOME"])
    }

    private companion object {
        const val MODULES = """{"_ssl.cpython-314-x86_64-linux-android.so":"libdshn_py__ssl.so","zlib.cpython-314-x86_64-linux-android.so":"libdshn_py_zlib.so"}"""
    }
}
