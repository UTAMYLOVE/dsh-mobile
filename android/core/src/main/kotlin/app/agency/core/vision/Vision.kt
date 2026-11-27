package app.agency.core.vision

import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import kotlinx.serialization.json.JsonObject

/**
 * One photo question to the image-capable model (T15b): a system line, the question, the cleaned photos and the JSON shape of
 * the answer. Only [VisionImage]s can be in it (a recording cannot be one), and it goes out only through the clients behind
 * the network gate, under the spend cap and the meter, like any call.
 */
data class VisionRequest(
    val system: String,
    val prompt: String,
    val images: List<VisionImage>,
    val schema: JsonObject,
    val maxTokens: Int,
) {
    init {
        require(images.isNotEmpty() && images.size <= MAX_IMAGES) { "one to $MAX_IMAGES photos" }
    }

    companion object {
        const val MAX_IMAGES = 3
    }
}

/** What a photo question got: the reply's text (JSON), or why there is none. */
sealed interface VisionResult {
    data class Ok(val text: String) : VisionResult
    data class Failed(val fail: Fail) : VisionResult
}

/** A provider that can look at photos. */
fun interface VisionClient {
    suspend fun see(request: VisionRequest): VisionResult
}

/**
 * The provider he chose, for photos: DeepSeek's image model (its official docs, checked 2026-11-27: `deepseek-flash` takes
 * `image_url` parts with a base64 data URL; images are billed as input tokens) or Claude's image blocks. His compatible
 * service has no image support here: [see] answers [Fail.NO_SETUP] and the caller says so once and asks him to type it.
 */
class VisionRouter(
    private val choice: () -> ProviderChoice,
    private val deepseek: VisionClient,
    private val claude: VisionClient,
) : VisionClient {
    fun supported(): Boolean = choice() != ProviderChoice.COMPAT

    override suspend fun see(request: VisionRequest): VisionResult = when (choice()) {
        ProviderChoice.DEEPSEEK -> deepseek.see(request)
        ProviderChoice.CLAUDE -> claude.see(request)
        ProviderChoice.COMPAT -> VisionResult.Failed(Fail.NO_SETUP)
    }
}
