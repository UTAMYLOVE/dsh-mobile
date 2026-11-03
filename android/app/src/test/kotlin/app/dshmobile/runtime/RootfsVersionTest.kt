package app.dshmobile.runtime

import app.dshmobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One version source: `rootfs/versions.env`. The app's expected rootfs marker (BuildConfig.ROOTFS_VERSION) and the marker the
 * rootfs build writes (rootfs/build-rootfs.sh via source-hash.sh) must agree, or onboarding could never finish.
 */
class RootfsVersionTest {
    // Unit tests run in the module directory (android/app).
    private val repo = File("../..").canonicalFile
    private val env: Map<String, String> = File(repo, "rootfs/versions.env").readLines()
        .filter { '=' in it && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    @Test fun buildConfigVersionsComeFromVersionsEnv() {
        assertTrue(BuildConfig.ROOTFS_VERSION, BuildConfig.ROOTFS_VERSION.matches(Regex("""\d+-[0-9a-f]{8}""")))
        assertEquals(env.getValue("ROOTFS_VERSION"), BuildConfig.ROOTFS_VERSION.substringBefore('-'))
        assertEquals(env.getValue("DSH_PINNED_VERSION"), BuildConfig.DSH_PINNED_VERSION)
    }

    @Test fun theRootfsBuildComputesTheSameContentHash() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }
        assumeTrue("needs bash and sha256sum (Linux/macOS CI)", bash != null && File("/usr/bin/sha256sum").canExecute())
        val p = ProcessBuilder(bash, File(repo, "rootfs/source-hash.sh").path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("source-hash.sh: $out", 0, p.exitValue())
        assertEquals(out, BuildConfig.ROOTFS_VERSION.substringAfter('-'))
    }
}
