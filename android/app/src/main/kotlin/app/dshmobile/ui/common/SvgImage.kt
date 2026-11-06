package app.dshmobile.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import com.caverock.androidsvg.SVG
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SvgTags {
    const val IMAGE = "svg-image"
    const val BROKEN = "svg-broken"
}

/** Renders an SVG file to a bitmap (longest side at most [maxPx]); null when it cannot be read or drawn. */
object SvgRender {
    fun bitmap(file: File, maxPx: Int = 1600): Bitmap? = runCatching {
        if (!file.isFile || file.length() > 4_000_000) return null
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        val box = svg.documentViewBox
        val w0 = (box?.width() ?: svg.documentWidth).takeIf { it > 0f } ?: 800f
        val h0 = (box?.height() ?: svg.documentHeight).takeIf { it > 0f } ?: 600f
        val scale = minOf(maxPx / w0, maxPx / h0, 2f)
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        svg.setDocumentWidth(w.toFloat())
        svg.setDocumentHeight(h.toFloat())
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(android.graphics.Canvas(it)) }
    }.getOrNull()
}

/** An SVG drawn off the main thread; an unreadable one says 「這張圖打不開」. */
@Composable
fun SvgImage(file: File, modifier: Modifier = Modifier) {
    val state by produceState<Pair<Boolean, ImageBitmap?>>(false to null, file.path, file.lastModified()) {
        value = true to withContext(Dispatchers.IO) { SvgRender.bitmap(file)?.asImageBitmap() }
    }
    val (loaded, image) = state
    when {
        image != null -> Image(
            image, contentDescription = file.nameWithoutExtension, contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().heightIn(max = 420.dp).testTag(SvgTags.IMAGE),
        )
        loaded -> Box(modifier.fillMaxWidth().padding(12.dp).testTag(SvgTags.BROKEN), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.svg_broken), color = Sakura.TextSecondary, fontSize = 13.sp)
        }
        else -> Box(modifier.fillMaxWidth().heightIn(min = 120.dp))
    }
}
