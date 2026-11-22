package app.dshmobile

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * No cleartext HTTP (arch 1.11, T09): the release config allows none at all; the debug build (what these tests run) allows only
 * `http://127.0.0.1`, for the e2e suite's fake model. Robolectric's NetworkSecurityPolicy shadow does not read the config, so
 * the XML itself is asserted (the release file is read from the source tree).
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun configId() = context.resources.getIdentifier("network_security_config", "xml", context.packageName)

    @Test fun theManifestPointsAtTheConfig() {
        val id = configId()
        assertTrue("res/xml/network_security_config.xml is missing", id != 0)
        // ApplicationInfo.networkSecurityConfigRes is hidden API; Robolectric fills it from the merged manifest.
        val field = context.applicationInfo.javaClass.getField("networkSecurityConfigRes")
        assertEquals(id, field.getInt(context.applicationInfo))
    }

    @Test fun theReleaseConfigHasNoCleartextAtAll() {
        val release = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        val body = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(release, "")
        assertTrue(body.contains("<base-config cleartextTrafficPermitted=\"false\" />"))
        assertTrue(body, !body.contains("domain") && !body.contains("\"true\""))
    }

    @Test fun theDebugConfigOnlyAddsExactly127_0_0_1() {
        val parser: XmlResourceParser = context.resources.getXml(configId())
        val elements = mutableListOf<String>()
        var baseCleartext: String? = null
        var domainCleartext: String? = null
        val domains = mutableListOf<Triple<String, String?, String>>() // text, includeSubdomains, parent cleartext
        var current = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    elements += parser.name
                    current = parser.name
                    when (parser.name) {
                        "base-config" -> baseCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        "domain-config" -> domainCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        "domain" -> {
                            val include = parser.getAttributeValue(null, "includeSubdomains")
                            parser.next()
                            domains += Triple(parser.text.trim(), include, domainCleartext.orEmpty())
                        }
                    }
                }
            }
        }
        assertEquals("false", baseCleartext)
        assertEquals("true", domainCleartext)
        assertEquals(listOf(Triple("127.0.0.1", "false", "true")), domains)
        assertEquals(listOf("network-security-config", "base-config", "domain-config", "domain"), elements)
    }
}
