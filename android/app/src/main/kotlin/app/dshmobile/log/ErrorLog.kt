package app.dshmobile.log

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Append-only diagnostics file `filesDir/logs/error.log` (one `ISO-8601 [tag] message` per line, rotated to
 * `error.log.1` above 1 MB). Everything is masked before it is written: `token=<value>`, any run of 64 or more hex
 * characters (session tokens) and every string registered with [addSecret]. Writing never throws.
 */
class ErrorLog(filesDir: File, private val clock: Clock = Clock.systemUTC()) {
    private val logFile = File(filesDir, "logs/error.log")
    private val secrets = CopyOnWriteArraySet<String>()

    fun file(): File = logFile

    /** Masks [secret] in everything logged from now on. Blank strings are ignored. */
    fun addSecret(secret: String) {
        if (secret.isNotBlank()) secrets.add(secret)
    }

    fun append(tag: String, msg: String) {
        val now = clock.instant()
        val text = msg.lineSequence().joinToString("") { "$now [${mask(tag)}] ${mask(it)}\n" }
        synchronized(this) {
            try {
                logFile.parentFile?.mkdirs()
                rotateIfNeeded()
                logFile.appendText(text, Charsets.UTF_8)
            } catch (_: IOException) {
                // A full disk must not take dsh down with it.
            }
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.length() <= MAX_BYTES) return
        Files.move(
            logFile.toPath(), File(logFile.parentFile, "error.log.1").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun mask(s: String): String {
        var out = s
        for (secret in secrets.sortedByDescending { it.length }) out = out.replace(secret, MASK)
        return out.replace(TOKEN_PARAM, "token=$MASK").replace(HEX_64, MASK)
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
        const val MASK = "***"
        val TOKEN_PARAM = Regex("""token=\S+""")
        val HEX_64 = Regex("[0-9a-fA-F]{64,}")
    }
}
