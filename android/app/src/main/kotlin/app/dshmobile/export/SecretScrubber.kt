package app.dshmobile.export

/** Last line of defence for exported diagnostics: masks key-like strings that [app.dshmobile.log.ErrorLog] did not know about. */
object SecretScrubber {
    private const val MASK = "***"
    private val TOKEN_PARAM = Regex("""token=\S+""")
    private val SK_KEY = Regex("""(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+""")
    private val LONG_RUN = Regex("""[A-Za-z0-9_-]{32,}""")

    fun scrub(text: String): String =
        text.replace(TOKEN_PARAM, "token=$MASK")
            .replace(SK_KEY, MASK)
            .replace(LONG_RUN, MASK)
}
