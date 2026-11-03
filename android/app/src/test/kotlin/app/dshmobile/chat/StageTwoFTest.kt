package app.dshmobile.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTwoFTest {
    @Test fun limitAndSandboxErrorsSayWhatToDo() {
        assertTrue(friendlyToolError("Team member limit 8 reached").contains("設定 → 代理人"))
        assertTrue(friendlyToolError("需要 workspace-write sandbox，這台機器沒有可用的 sandbox").contains("workflow"))
        assertEquals("file not found", friendlyToolError("file not found"))
    }

    @Test fun workspaceFilesAreNewestFirstAndSkipAppDataAndLinksOut() {
        val root = Files.createTempDirectory("ws").toFile()
        val outside = Files.createTempDirectory("out").toFile().resolve("secret.txt").apply { writeText("x") }
        File(root, "out/report.csv").apply { parentFile.mkdirs(); writeText("a,b"); setLastModified(2_000_000) }
        File(root, "notes.md").apply { writeText("hi"); setLastModified(1_000_000) }
        File(root, ".dshm/library.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(root, "node_modules/x.js").apply { parentFile.mkdirs(); writeText("") }
        runCatching { Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath()) }
        val got = WorkspaceFiles.list(root)
        assertEquals(listOf("out/report.csv", "notes.md"), got.map { it.path })
        assertEquals(3L, got.first().bytes)
    }
}
