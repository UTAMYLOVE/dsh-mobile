package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageGenTest {
    @Test fun onlyAPlainHttpAddressIsKept() {
        assertEquals("http://192.168.1.20:8188", ImageGen.clean("  http://192.168.1.20:8188 "))
        assertEquals("https://pc.example/api", ImageGen.clean("https://pc.example/api"))
        for (bad in listOf("", "192.168.1.20", "ftp://x", "http://", "http://user:pw@host/", "http://a b", "http://" + "x".repeat(400))) assertNull(bad, ImageGen.clean(bad))
    }

    @Test fun theNoteTellsTheAiWhetherItCanDraw() {
        val ws = Files.createTempDirectory("ws").toFile()
        ImageGen.write(ws, null)
        val file = File(ws, ImageGen.FILE)
        assertTrue(file.readText().contains("還沒接上"))
        ImageGen.write(ws, "http://192.168.1.20:8188")
        assertTrue(file.readText().contains("位址：http://192.168.1.20:8188"))
        // the skill points at the file the app writes
        assertTrue(app.dshmobile.config.AppSkills.all.getValue("dshm-image").contains(ImageGen.FILE))
    }
}
