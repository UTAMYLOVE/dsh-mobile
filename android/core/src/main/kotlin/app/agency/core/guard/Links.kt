package app.agency.core.guard

/**
 * Links and file names, one definition for the guard (model text) and FactsBuilder (what goes out): scheme URLs, `www.`,
 * any bare domain with a letter top-level part (`youtu.be/x`, `bit.ly/3abc`, `shop.example.co.jp/menu`), and file names
 * of photos, recordings and vault files.
 */
object Links {
    val URL = Regex("(?i)(?:https?|content|file)://\\S*|www\\.\\S*|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}\\b(?:/\\S*)?")
    val FILE = Regex("(?i)\\S+\\.(?:jpg|jpeg|png|webp|heic|m4a|wav|mp4|bin)\\b")

    fun containsAny(text: String): Boolean = URL.containsMatchIn(text) || FILE.containsMatchIn(text)
}
