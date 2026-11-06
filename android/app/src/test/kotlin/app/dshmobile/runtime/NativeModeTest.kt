package app.dshmobile.runtime

import app.dshmobile.chat.ConversationReducer
import app.dshmobile.config.DshPatch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The experimental native mode (owner, 2026-11-04): the same dsh on Android's own node, with host paths instead of /root. */
class NativeModeTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() { DshLayout.active = DshLayout.PROOT }

    @Test fun prootKeepsTheOldCommandLine() {
        assertEquals(
            listOf(
                "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "node", "/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "--profile", "web", "--patch", "/opt/dshm/dshm.cordis.yml", "--no-open", "--host", "127.0.0.1", "--port", "0",
            ),
            DshLayout.PROOT.args,
        )
    }

    @Test fun nativeUsesHostPathsAndTheActiveDshVersion() {
        val paths = AppPaths(tmp.root)
        val lay = DshLayout.native(paths, File(paths.rootfs, "opt/dsh/0.2.1"), listOf("/lib/node", "--import", "file:///s.mjs"), "/n/bin/bash")
        assertEquals(paths.workspace.path, lay.workspace)
        assertEquals(paths.dshHome.path, lay.dshHome)
        assertEquals(listOf("/lib/node", "--import", "file:///s.mjs", File(paths.rootfs, "opt/dsh/0.2.1/node_modules/@deepseek-ai/dsh/lib/bin.js").path), lay.args.take(4))
        assertEquals(File(paths.rootfs, "opt/dshm/dshm.cordis.yml").path, lay.args[lay.args.indexOf("--patch") + 1])
    }

    @Test fun thePatchPointsDshAtTheNativeWorkspaceAndBash() {
        val yaml = DshPatch.render("http://127.0.0.1:1/t", pluginDir = "/r/opt/dshm/plugins", workspace = "/data/ws", shellPath = "/n/bin/bash")
        assertTrue(yaml, yaml.contains("documentsDirectory: \"/data/ws\""))
        assertTrue(yaml, yaml.contains("- id: terminal-bash\n  config:\n    shellPath: \"/n/bin/bash\""))
        assertTrue(yaml, yaml.contains("/r/opt/dshm/plugins"))
        assertFalse(DshPatch.render("http://127.0.0.1:1/t").contains("terminal-bash"))
    }

    @Test fun sharedFilesAreFoundInEitherWorkspace() {
        DshLayout.active = DshLayout.PROOT.copy(native = true, workspace = "/data/user/0/app/files/userdata/workspace")
        fun share(path: String) = ConversationReducer.sharePath("write", buildJsonObject { put("file_path", JsonPrimitive(path)) })
        assertEquals("notes/a.md", share("/data/user/0/app/files/userdata/workspace/notes/a.md"))
        assertEquals("b.md", share("/root/workspace/b.md"))
        assertNull(share("/etc/passwd"))
    }

    /** Owner, 2026-11-05: a proot conversation opened in native mode stood at 「傳送中」 forever (dsh cannot run it there). */
    @Test fun aConversationOfTheOtherModeIsRecognised() {
        val native = DshLayout.PROOT.copy(native = true, workspace = "/data/user/0/app.dshmobile/files/userdata/workspace")
        assertEquals(false, DshLayout.otherModeOf("/root/workspace", native))
        assertEquals(null, DshLayout.otherModeOf("/data/user/0/app.dshmobile/files/userdata/workspace", native))
        assertEquals(null, DshLayout.otherModeOf("/data/user/0/app.dshmobile/files/userdata/workspace/notes", native))
        assertEquals(null, DshLayout.otherModeOf("/data/user/0/app.dshmobile/files/userdata/workspace", DshLayout.PROOT))
        assertEquals(null, DshLayout.otherModeOf("/root/workspace", DshLayout.PROOT))
        assertEquals(null, DshLayout.otherModeOf(null, native))
    }

    @Test fun prepareLinksEveryProgramByNameAndCopiesTheShim() {
        val libDir = tmp.newFolder("lib")
        for (f in listOf("libdshn_bin_node.so", "libdshn_bin_bash.so", "libdshn_flock.so", "libdshn_libz_so_1.so")) File(libDir, f).writeText("elf")
        val manifest = """{"bins":{"node":"libdshn_bin_node.so","bash":"libdshn_bin_bash.so"},"libs":{"libz.so.1":"libdshn_libz_so_1.so"},"flock":"libdshn_flock.so"}"""
        val assets = mapOf("native/manifest.json" to manifest, "dshn/shim.mjs" to "// shim", "dshn/require-builtin.cjs" to "//", "native/ca-bundle.crt" to "CERT")
        val base = File(tmp.root, "native")
        val runner = NativeRunner(libDir, base, File(tmp.root, "home"), File(tmp.root, "tmp"), assets = { assets[it]?.byteInputStream() }, niceBinary = null)
        assertTrue(runner.available)
        runner.prepare()
        runner.prepare() // idempotent
        assertEquals(File(libDir, "libdshn_bin_node.so").toPath(), Files.readSymbolicLink(File(base, "bin/node").toPath()))
        assertEquals("// shim", File(base, "shim.mjs").readText())
        val node = runner.node()
        assertEquals(File(libDir, "libdshn_bin_node.so").path, node[0])
        assertEquals(listOf("--import", File(base, "shim.mjs").toURI().toString(), "--expose-internals"), node.drop(1))
        val env = runner.command(node, mapOf("DSH_HOME" to "/h"), File(tmp.root, "ws")).environment()
        assertEquals(File(libDir, "libdshn_libz_so_1.so").toPath(), Files.readSymbolicLink(File(base, "lib/libz.so.1").toPath()))
        assertEquals("${File(base, "lib").path}:${libDir.path}", env["LD_LIBRARY_PATH"])
        assertEquals(File(libDir, "libdshn_flock.so").path, env["DSHN_FLOCK"])
        assertEquals(File(base, "bin/rg").path, env["DSHN_RG"])
        assertEquals("/h", env["DSH_HOME"])
        assertTrue(env.getValue("PATH").startsWith(File(base, "bin").path + ":"))
    }

    @Test fun anApkWithoutTheRuntimeIsNotAvailable() {
        val runner = NativeRunner(tmp.newFolder("l"), tmp.newFolder("n"), tmp.newFolder("h"), tmp.newFolder("t"), assets = { null }, niceBinary = null)
        assertFalse(runner.available)
    }
}
