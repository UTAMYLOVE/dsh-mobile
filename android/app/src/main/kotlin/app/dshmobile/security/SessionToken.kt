package app.dshmobile.security

import java.security.SecureRandom

/** Random per-session secret (64 lowercase hex chars). `toString()` never exposes the value so it cannot leak into logs. */
class SessionToken(val value: String) {
    override fun toString(): String = "SessionToken(***)"

    companion object {
        private const val BYTES = 32
        private val random = SecureRandom()

        fun generate(): SessionToken {
            val bytes = ByteArray(BYTES).also { random.nextBytes(it) }
            return SessionToken(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
