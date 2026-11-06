package app.dshmobile.library

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilesTest {
    @Test fun presentedFilesLandOnTheShelfOnce() {
        val file = File(Files.createTempDirectory("lib").toFile(), "library.json")
        val store = LibraryStore(file) { 42 }
        store.addFile("reports/a.md", "外資報告", "MD", "s1")
        store.addFile("reports/a.md", "別的名字", "MD", "s2")
        store.addFile("drawings/b.svg", "", "SVG", null)
        assertEquals(listOf("drawings/b.svg", "reports/a.md"), store.state.value.files.map { it.path })
        assertEquals("b.svg", store.state.value.files[0].title)
        assertEquals(listOf("drawings/b.svg", "reports/a.md"), LibraryStore(file).state.value.files.map { it.path })
        store.removeFile(store.state.value.files[0].id)
        assertEquals(listOf("reports/a.md"), store.state.value.files.map { it.path })
    }

    @Test fun libraryFilesRoundTripJson() {
        val d = LibraryData(files = listOf(LibraryFile("i1", "deliverables/表.xlsx", "表", "XLS", "s1", 7), LibraryFile("i2", "x.md", "x", "MD", null, 8)))
        assertEquals(d, LibraryJson.decode(Json.parseToJsonElement(LibraryJson.encode(d).toString())))
        assertEquals(LibraryData(), LibraryJson.decode(Json.parseToJsonElement("""{"files":[{"id":"x"}]}""")))
    }
}
