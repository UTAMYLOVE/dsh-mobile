package app.dshmobile.agency

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Arch 1.12: nothing goes to a cloud backup or to a new phone, for any domain; the manifest points at the rules. */
class DataExtractionRulesTest {
    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test fun bothSectionsExcludeEveryDomainAndIncludeNothing() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", rules.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = rules.children(section).single()
            assertTrue("$section must not include anything", s.getElementsByTagName("include").length == 0)
            val excluded = s.children("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
            assertEquals(section, DOMAINS.map { it to "." }.toSet(), excluded)
        }
    }

    @Test fun theManifestUsesTheRulesAndNoBackup() {
        val app = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
    }

    private companion object {
        val DOMAINS = listOf(
            "root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref",
        )
    }
}
