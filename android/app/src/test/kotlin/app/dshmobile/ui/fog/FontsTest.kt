package app.dshmobile.ui.fog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.R
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun font(id: Int) = context.resources.openRawResource(id).use { it.readBytes() }

    @Test fun bundledFontsAreThePinnedFiles() {
        assertEquals("f42db2dd16e642258e35782916eceb1dcdbea06fb958d77ad71dc5963587e8fd", sha256(font(R.font.orbitron)))
        assertEquals("98fcd638baa5c81ff0316b7538ce330ee3b23b1302726de3526d5933a8ecf986", sha256(font(R.font.chakra_petch_regular)))
        assertEquals("45264de3204ddbd5fb3e14a2402acd5c630d16650ae5fc221d2c52da46a6734b", sha256(font(R.font.chakra_petch_semibold)))
    }

    @Test fun theOflNoticeShipsWithBothCopyrights() {
        val bytes = context.assets.open("licenses/fonts-OFL.txt").use { it.readBytes() }
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("The Orbitron Project Authors"))
        assertTrue(text.contains("The Chakra Petch Project Authors"))
        assertTrue(text.contains("SIL Open Font License, Version 1.1"))
        // One source line, then the two upstream OFL.txt files back to back (Orbitron 4426 bytes, Chakra Petch 4396 bytes).
        val body = bytes.copyOfRange(bytes.indexOf('\n'.code.toByte()) + 1, bytes.size)
        assertEquals(4426 + 4396, body.size)
        assertEquals("ab609b0e110d622435ff337cdf233288556e011bbf9bd0550be98846c0630819", sha256(body.copyOfRange(0, 4426)))
        assertEquals("13831d02389d917d22fcfa6c79f98f8acbf61d230add0e73a4d4dc5f3bfb9e56", sha256(body.copyOfRange(4426, body.size)))
    }
}
