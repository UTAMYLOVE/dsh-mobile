package app.agency.core.llm

import app.agency.core.net.HttpOutcome

/** Why a model call or a search gave no answer. The caller answers with a template; nothing here is shown verbatim. */
enum class Fail {
    NO_KEY, NO_SETUP, CAP, OFFLINE, KEY_REJECTED, RATE, SERVER, REFUSED, BAD_RESPONSE, BLOCKED_HOST,

    /** The message carries photos and the chosen model cannot see images: nothing was sent (never a silent drop). */
    NO_VISION;

    companion object {
        /**
         * A non-2xx status. 402 (DeepSeek: no balance left) counts as KEY_REJECTED: the key cannot be used until he acts on the
         * provider's site, the same as a revoked key. 3xx lands in BAD_RESPONSE because redirects are never followed.
         */
        fun ofStatus(code: Int): Fail = when {
            code == 401 || code == 402 || code == 403 -> KEY_REJECTED
            code == 429 -> RATE
            code >= 500 -> SERVER
            else -> BAD_RESPONSE
        }

        /** The failure of a transport outcome, or null for a 2xx response (whose body the caller then reads). */
        fun of(outcome: HttpOutcome): Fail? = when (outcome) {
            is HttpOutcome.Blocked -> BLOCKED_HOST
            HttpOutcome.Offline -> OFFLINE
            HttpOutcome.TooLarge -> BAD_RESPONSE
            is HttpOutcome.Response -> if (outcome.code in 200..299) null else ofStatus(outcome.code)
        }
    }
}

/** What went wrong on the wire, short enough to show under a message. Bodies of successful calls are never passed here. */
object FailDetail {
    private val key = Regex("""sk-[A-Za-z0-9_\-]{6,}""")
    private val message = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.){0,400})""")

    fun of(outcome: app.agency.core.net.HttpOutcome): String? = when (outcome) {
        is app.agency.core.net.HttpOutcome.Blocked -> "blocked host ${outcome.host}"
        app.agency.core.net.HttpOutcome.Offline -> "no answer (offline or timed out)"
        app.agency.core.net.HttpOutcome.TooLarge -> "answer too large"
        is app.agency.core.net.HttpOutcome.Response ->
            if (outcome.code in 200..299) null
            else {
                val said = message.find(outcome.body)?.groupValues?.get(1)?.replace("\\n", " ")?.replace("\\\"", "\"")
                    ?: outcome.body.take(200)
                ("HTTP ${outcome.code} " + said.replace(key, "sk-…")).trim().take(300)
            }
    }
}
