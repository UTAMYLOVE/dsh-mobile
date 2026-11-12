package app.dshmobile.chat

import java.io.File

/**
 * The picture-making hook (0.7.0, the owner: 「電腦生圖留個接口就好」). The owner will run an image model on his own computer
 * later; until then the app only keeps the address of that service and tells dsh about it in `.dshm/image-gen.md`, which the
 * `dshm-image` skill reads. No address: the file says so and the AI tells the owner instead of pretending.
 */
object ImageGen {
    const val FILE = ".dshm/image-gen.md"
    const val KEY = "image_gen_url"
    const val MAX_URL = 300

    /** A usable address: http(s), a host, no spaces, no credentials; anything else is no address. */
    fun clean(input: String): String? {
        val url = input.trim()
        if (url.isEmpty() || url.length > MAX_URL || url.any { it.isWhitespace() }) return null
        val rest = when {
            url.startsWith("http://") -> url.removePrefix("http://")
            url.startsWith("https://") -> url.removePrefix("https://")
            else -> return null
        }
        val host = rest.substringBefore('/')
        return if (host.isEmpty() || '@' in host) null else url
    }

    fun note(url: String?): String = if (url == null) {
        "# 生圖服務\n\n狀態：還沒接上。\n\n主人還沒有填生圖服務的位址，所以現在不能產生照片或插畫。請直接告訴主人：到 App 的「設定 → 進階設定 → 生圖服務位址」填上電腦上生圖服務的位址之後就能用。流程圖、圖表這類向量圖不受影響，照常用 dshm-draw 畫。\n"
    } else {
        "# 生圖服務\n\n狀態：已設定。\n位址：$url\n\n這是主人自己電腦上的生圖服務（和手機在同一個網路時才連得到）。先用 web_fetch 或 curl 看這個位址回什麼，認出它是哪一種服務（例如 ComfyUI 或 Stable Diffusion WebUI 的 API）再照它的做法送出要求；圖檔存到工作區 `drawings/`，用 present 交給主人。連不上就告訴主人「電腦沒開或不在同一個網路」，不要改用別的方式假裝畫好了。\n"
    }

    /** Writes the note into [workspace]; never throws (a failure only means the AI finds no note and says so). */
    fun write(workspace: File, url: String?) {
        runCatching {
            val file = File(workspace, FILE)
            file.parentFile?.mkdirs()
            val text = note(url)
            if (!file.isFile || file.readText() != text) file.writeText(text)
        }
    }
}
