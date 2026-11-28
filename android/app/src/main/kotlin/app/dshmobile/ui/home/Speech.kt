package app.dshmobile.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text, on the device only: [SpeechRecognizer.createOnDeviceSpeechRecognizer], Traditional Chinese (Taiwan).
 * Never the network recogniser: when the on-device one is not there the mic is hidden ([available] false).
 */
class OnDeviceSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun interface Sink {
        fun text(text: String, final: Boolean)
    }

    fun start(sink: Sink, onEnd: (Boolean) -> Unit) {
        stop()
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Throwable) {
            onEnd(false)
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onEnd(false)
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { sink.text(it, true) }
                onEnd(true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { sink.text(it, false) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        fun available(context: Context): Boolean = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }
}
