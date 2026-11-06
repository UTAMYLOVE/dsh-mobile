package app.dshmobile.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 朗讀 (0.5.0): reads one message with Android's TextToSpeech in zh-TW, Markdown marks taken out, a paragraph per utterance.
 * [speaking] is the key of the message being read, null otherwise. A phone without a TTS engine answers false from [speak].
 */
class Speaker(private val context: Context) {
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: Pair<String, List<String>>? = null

    fun speak(key: String, markdown: String): Boolean {
        val parts = paragraphs(markdown)
        if (parts.isEmpty()) return false
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { _speaking.value = null; return@TextToSpeech }
            tts?.language = Locale.TAIWAN
            queued?.let { (k, p) -> queued = null; say(k, p) }
        }.also { t ->
            tts = t
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith(":last") == true) _speaking.value = null }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { _speaking.value = null }
            })
        }
        if (engine.engines.isEmpty()) {
            _speaking.value = null
            return false
        }
        _speaking.value = key
        if (ready) say(key, parts) else queued = key to parts
        return true
    }

    fun stop() {
        queued = null
        tts?.stop()
        _speaking.value = null
    }

    fun close() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun say(key: String, parts: List<String>) {
        val t = tts ?: return
        parts.forEachIndexed { i, p ->
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "$key:last" else "$key:$i")
        }
    }

    companion object {
        /** The message as plain paragraphs: no Markdown marks, no code blocks, no URLs read out letter by letter. */
        fun paragraphs(markdown: String): List<String> = markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .split(Regex("\\n\\s*\\n"))
            .map { ConversationReducer.plainLine(it).replace(Regex("^\\|.*\\|$"), "") }
            .filter { it.isNotBlank() }
    }
}
