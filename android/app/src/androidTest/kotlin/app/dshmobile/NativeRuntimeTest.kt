package app.dshmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The experimental Android-native runtime (native/build-runtime.py), run inside the real app process: the same SELinux
 * domain and W^X rules dsh would meet. Each program and library is an unchanged libdshn_*.so in nativeLibraryDir; symlinks
 * under filesDir give them back their names (bin/ for programs on PATH, lib/ for sonames on LD_LIBRARY_PATH). Skipped when the APK has no native
 * runtime (built without -Pdshm.nativeDir).
 */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val libDir = File(app.applicationInfo.nativeLibraryDir)
    private lateinit var bins: JSONObject
    private lateinit var home: File
    private lateinit var binDir: File
    private lateinit var libLinks: File

    @Before fun setUp() {
        val manifest = try { app.assets.open("native/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
        assumeTrue("no native runtime in this APK", manifest != null)
        bins = JSONObject(manifest!!).getJSONObject("bins")
        home = File(app.filesDir, "native-test").apply { deleteRecursively(); mkdirs() }
        binDir = File(home, "bin").apply { mkdirs() }
        libLinks = File(home, "lib").apply { mkdirs() }
        for (name in bins.keys()) Files.createSymbolicLink(File(binDir, name).toPath(), File(libDir, bins.getString(name)).toPath())
        val libs = JSONObject(manifest).getJSONObject("libs")
        for (soname in libs.keys()) Files.createSymbolicLink(File(libLinks, soname).toPath(), File(libDir, libs.getString(soname)).toPath())
    }

    private fun run(vararg cmd: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val pb = ProcessBuilder(*cmd).directory(home).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", "${libLinks.path}:${libDir.path}")
            put("HOME", home.path)
            put("TMPDIR", app.cacheDir.path)
            put("PATH", "${binDir.path}:/system/bin")
            putAll(env)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("timed out: ${cmd.joinToString(" ")}", p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private fun lib(name: String) = File(libDir, bins.getString(name)).path

    @Test fun nodeRunsFromTheNativeLibraryDirectory() {
        val (code, out) = run(lib("node"), "-e", "console.log('node', process.versions.node, process.platform, process.arch)")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("node 24.") && out.contains("android"))
    }

    @Test fun programsRunThroughTheirSymlinksByName() {
        val (code, out) = run(lib("bash"), "-c", "echo bash-ok \$((6*7)); node -p 1+1; rg --version | head -1; git --version")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("bash-ok 42"))
        assertTrue(out, out.lines().any { it.trim() == "2" })
        assertTrue(out, out.contains("ripgrep"))
        assertTrue(out, out.contains("git version"))
    }

    @Test fun ripgrepAndGitWorkOnFiles() {
        File(home, "notes.md").writeText("written-natively\n")
        val (code, out) = run(
            lib("bash"), "-c",
            "rg -n written-natively . && git init -q repo && cd repo && git -c user.name=t -c user.email=t@t commit -q --allow-empty -m first && git log --oneline | wc -l",
            env = mapOf("GIT_EXEC_PATH" to binDir.path, "GIT_CONFIG_NOSYSTEM" to "1"),
        )
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("notes.md:1:written-natively"))
        assertTrue(out, out.trim().endsWith("1"))
    }

    @Test fun theFileLockAddonLoadsIntoNode() {
        val flock = File(libDir, JSONObject(app.assets.open("native/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }).getString("flock")).path
        val lockFile = File(home, "lock").path
        val js = "const m={exports:{}};process.dlopen(m,'$flock');const fs=require('fs');const fd=fs.openSync('$lockFile','w');" +
            "console.log('exports', Object.keys(m.exports).join(','))"
        val (code, out) = run(lib("node"), "-e", js)
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("tryLock"))
    }

    @Test fun nodeSpeaksTlsWithTheBundledCertificates() {
        val cert = File(home, "cert.pem").apply { app.assets.open("native/cert.pem").use { input -> outputStream().use { input.copyTo(it) } } }
        val (code, out) = run(
            lib("node"), "-e", "const tls=require('tls');tls.createSecureContext();console.log('tls-ok', require('crypto').getHashes().includes('sha256'))",
            env = mapOf("SSL_CERT_FILE" to cert.path),
        )
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("tls-ok true"))
    }
}
