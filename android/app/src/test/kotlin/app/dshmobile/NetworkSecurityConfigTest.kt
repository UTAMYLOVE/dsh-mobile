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
 * targetSdk 37 forbids cleartext HTTP by default, but dsh, its health check, the heartbeat and the WebView all speak
 * `http://127.0.0.1:<port>`. Robolectric's NetworkSecurityPolicy shadow does not read the config, so the XML itself is asserted.
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

    @Test fun cleartextIsOffExceptForExactly127_0_0_1() {
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
