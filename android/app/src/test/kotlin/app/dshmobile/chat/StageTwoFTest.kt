package app.dshmobile.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTwoFTest {
    @Test fun limitAndSandboxErrorsSayWhatToDo() {
        // 代理人 is behind 進階設定 (off by default): the hint says to open it first.
        for (e in listOf("Team member limit 8 reached", "Error: Team member limit 8 reached")) {
            val text = friendlyToolError(e)
            assertTrue(text, text.contains("打開「進階設定」，到「代理人」"))
            assertFalse(text, text.contains("設定 → 代理人"))
        }
        assertTrue(friendlyToolError("需要 workspace-write sandbox，這台機器沒有可用的 sandbox").contains("workflow"))
        assertEquals("找不到檔案。\nfile not found", friendlyToolError("file not found"))
    }

    /** dsh's subagent limit is flow control: the model waits. No settings, no restart (owner: 「你一直叫我改設定」). */
    @Test fun theSubagentLimitSaysWaitAndNothingElse() {
        val dsh = "subagent limit reached (active child limit: 3); wait for an existing child to finish or complete this work with the current agents"
        for (e in listOf(dsh, "Error: $dsh")) {
            val text = friendlyToolError(e)
            assertEquals(SUBAGENTS_BUSY, text)
            assertFalse(text, "設定" in text || "重啟" in text)
        }
        // Only the team-member limit names a setting; other "limit reached" errors pass through as dsh said them.
        assertEquals("Team task limit 20 reached", friendlyToolError("Team task limit 20 reached"))
    }

    /** One plain Chinese line first, dsh's English (without its 「Error: 」) after it; anything else exactly as dsh said it. */
    @Test fun dshsFixedPhrasesGetAChineseLineFirst() {
        val cases = mapOf(
            "Error: Auto review of tool \"bash\" failed; its body was not executed: Unexpected token" to "自動審查本身出錯，這個指令沒有執行。",
            "Error: Auto review rejected tool \"bash\"; its body was not executed" to "自動審查擋下了這個指令，沒有執行。",
            "Error: tool call aborted" to "已中止，沒有執行。",
            "The tool call was interrupted after it was recorded, but no result was durably recorded." to "上次被中斷，不確定有沒有做完。",
            "Error: ENOENT: no such file or directory, open '/x/USER.md'" to "找不到檔案。",
        )
        for ((dsh, line) in cases) {
            val text = friendlyToolError(dsh)
            assertEquals(dsh, line, text.substringBefore('\n'))
            assertEquals(dsh, dsh.removePrefix("Error: "), text.substringAfter('\n'))
            assertFalse(dsh, "重試" in text || "retry" in text.substringBefore('\n'))
        }
        assertEquals("exit code 2", friendlyToolError("Error: exit code 2"))
        assertEquals("permission denied", friendlyToolError("permission denied"))
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
