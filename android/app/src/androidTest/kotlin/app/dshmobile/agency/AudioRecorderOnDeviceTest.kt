package app.dshmobile.agency

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dshmobile.agency.media.AudioRecorder
import app.dshmobile.agency.media.MediaRecorderEngine
import app.dshmobile.agency.media.MediaVault
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's own microphone through [AudioRecorder] (arch T07): a short recording makes an MPEG-4 file that the streaming
 * vault seals and opens again byte for byte. An emulator without an audio input cannot record: the test is skipped then
 * (the state machine itself is covered by the JVM tests with a fake microphone).
 */
@RunWith(AndroidJUnit4::class)
class AudioRecorderOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aShortRecordingIsSealedAndOpensAgain() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val dir = File(context.cacheDir, "record-test").apply { deleteRecursively(); mkdirs() }
        val rec = AudioRecorder(dir, MediaRecorderEngine(context))
        try {
            rec.start()
            assumeTrue("no microphone on this device", rec.state.value is AudioRecorder.State.Recording)
            Thread.sleep(1500)
            rec.stop()
            val done = rec.state.value
            assumeTrue("the microphone gave no recording", done is AudioRecorder.State.Recorded)
            val file = rec.take()!!
            val plain = file.readBytes()
            assertTrue(String(plain, 4, 4, Charsets.US_ASCII) == "ftyp") // an MPEG-4 file
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val vault = MediaVault(File(dir, "vault")) { key }
            vault.putFile("rec-1", file)
            assertTrue(!file.exists())
            assertArrayEquals(plain, vault.get("rec-1"))
        } finally {
            rec.discard()
            dir.deleteRecursively()
        }
    }
}
