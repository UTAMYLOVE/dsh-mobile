package app.agency.core.guard

/**
 * Links and file names, one definition for the guard (model text) and FactsBuilder (what goes out): scheme URLs, `www.`,
 * any bare domain with a letter top-level part (`youtu.be/x`, `bit.ly/3abc`, `shop.example.co.jp/menu`), and file names
 * of photos, recordings and vault files. No `\b`: on the device a Han character counts as a word character, so a link
 * written right after Chinese text (「上網搜example.com就有」) would not match there.
 */
object Links {
    val URL = Regex("(?i)(?:https?|content|file)://\\S*|www\\.\\S*|(?<![a-z0-9-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?![a-z0-9])(?:/\\S*)?")
    val FILE = Regex("(?i)[a-z0-9_.-]*\\.(?:jpg|jpeg|png|webp|heic|m4a|wav|mp4|bin)(?![a-z0-9])")

    fun containsAny(text: String): Boolean = URL.containsMatchIn(text) || FILE.containsMatchIn(text)
}
