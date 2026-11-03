package app.dshmobile.runtime

/** The dsh web launch URL (with its one-time `?token=`) and the port it listens on. */
data class LaunchUrl(val url: String, val port: Int)

/** Parses the `dsh web: http://127.0.0.1:<port>/?token=<token>` line dsh prints on stdout. */
object DshLaunchLine {
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val launch = Regex("""^dsh web:\s+(http://127\.0\.0\.1:(\d{1,5})/\?token=\S+)$""")

    /** Returns the launch URL and port, or null unless the line is a loopback-only launch line carrying a non-empty token. */
    fun parse(line: String): LaunchUrl? {
        val match = launch.matchEntire(ansi.replace(line, "").trim()) ?: return null
        val port = match.groupValues[2].toInt()
        if (port !in 1..65535) return null
        return LaunchUrl(match.groupValues[1], port)
    }
}
