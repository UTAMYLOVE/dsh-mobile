package app.dshmobile.agency.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The recorder's state machine with a fake microphone (arch T07): record, stop or the limit, play, redo, take, discard. */
class AudioRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Writes invented bytes as the "recording"; [limit] lets the test fire the three-minute stop. */
    class FakeMic(var failStart: Boolean = false, var failStop: Boolean = false) : RecorderEngine {
        var out: File? = null
        var limit: (() -> Unit)? = null
        var maxMs = 0
        var released = 0
        var stops = 0
        override fun start(out: File, maxMs: Int, onLimit: () -> Unit) {
            if (failStart) throw IllegalStateException("busy")
            this.out = out
            this.maxMs = maxMs
            limit = onLimit
            out.writeBytes(ByteArray(2048) { 9 })
        }
        override fun stop(): Boolean {
            stops++
            return !failStop
        }
        override fun release() { released++ }
    }

    private var now = 1_000L
    private val dir by lazy { tmp.newFolder("record") }
    private val mic = FakeMic()
    private val rec by lazy { AudioRecorder(dir, mic, { now }) { "r-${dir.list()!!.size}" } }

    @Test fun recordStopTakeLeavesTheFileForTheCallerAndGoesBackToIdle() {
        rec.start()
        assertTrue(rec.state.value is AudioRecorder.State.Recording)
        assertEquals(AudioRecorder.MAX_MS, mic.maxMs)
        now += 42_000
        rec.stop()
        val done = rec.state.value as AudioRecorder.State.Recorded
        assertEquals(42_000, done.durationMs)
        assertTrue(done.file.isFile)
        val taken = rec.take()!!
        assertEquals(done.file, taken)
        assertEquals(AudioRecorder.State.Idle, rec.state.value)
        assertNull(rec.take())
    }

    @Test fun theThreeMinuteLimitStopsItByItself() {
        rec.start()
        now += AudioRecorder.MAX_MS + 500L
        mic.limit!!()
        val done = rec.state.value as AudioRecorder.State.Recorded
        assertEquals(AudioRecorder.MAX_MS.toLong(), done.durationMs)
        rec.stop() // a tap that comes after does nothing
        assertEquals(1, mic.stops)
    }

    @Test fun redoDeletesTheTakeAndAllowsANewOne() {
        rec.start()
        rec.stop()
        val first = (rec.state.value as AudioRecorder.State.Recorded).file
        rec.redo()
        assertFalse(first.exists())
        assertEquals(AudioRecorder.State.Idle, rec.state.value)
        rec.start()
        assertTrue(rec.state.value is AudioRecorder.State.Recording)
    }

    @Test fun aBusyMicrophoneOrABadStopKeepsNothing() {
        mic.failStart = true
        rec.start()
        assertEquals(AudioRecorder.State.Failed, rec.state.value)
        assertEquals(0, dir.list()!!.size)
        mic.failStart = false
        mic.failStop = true
        rec.start()
        rec.stop()
        assertEquals(AudioRecorder.State.Failed, rec.state.value)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun leavingTheScreenDeletesTheUnfinishedRecordingAndReleasesTheMicrophone() {
        rec.start()
        val file = mic.out!!
        rec.discard()
        assertFalse(file.exists())
        assertEquals(AudioRecorder.State.Idle, rec.state.value)
        assertEquals(1, mic.released)
        rec.start()
        rec.stop()
        val kept = (rec.state.value as AudioRecorder.State.Recorded).file
        rec.discard()
        assertFalse(kept.exists())
    }
}
