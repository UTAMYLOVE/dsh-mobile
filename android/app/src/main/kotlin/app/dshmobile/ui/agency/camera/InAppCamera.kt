package app.dshmobile.ui.agency.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.dshmobile.R
import app.dshmobile.agency.media.PhotoTarget
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.SoftButton

object CameraTags {
    const val SCREEN = "ag-cam"
    const val SHOOT = "ag-cam-shoot"
    const val FLIP = "ag-cam-flip"
    const val CLOSE = "ag-cam-close"
    const val HINT = "ag-cam-hint"
}

/**
 * The app's own camera (T15b, CameraX): a viewfinder, 拍照, 換鏡頭 and 取消; the body photos get the same-spot hint. A shot is
 * taken into memory ([ImageCapture.takePicture] with an in-memory callback: no file, nothing in the gallery) and handed to
 * [onShot] with its rotation; [onFail] means CameraX could not run here (the caller falls back to the system camera).
 */
@Composable
fun InAppCamera(target: PhotoTarget, onShot: (ByteArray, Int) -> Unit, onFail: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(target == PhotoTarget.Body) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(front) {
        val bound = bind(context, owner, preview, capture, front, onFail)
        onDispose { runCatching { bound.provider?.unbindAll() } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(CameraTags.SCREEN)) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        if (target == PhotoTarget.Body || target is PhotoTarget.Baseline) {
            // 同一個位置: an outline where the whole body goes, and the line under it.
            Box(Modifier.fillMaxWidth(0.55f).fillMaxHeight(0.72f).align(Alignment.Center).border(2.dp, Color.White.copy(alpha = .7f), RoundedCornerShape(40.dp)))
            Text(
                stringResource(R.string.ag_cam_body_hint), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp).background(Color.Black.copy(alpha = .45f)).padding(8.dp).testTag(CameraTags.HINT),
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton(stringResource(R.string.ag_cam_close), onClick = onClose, modifier = Modifier.testTag(CameraTags.CLOSE))
                PinkButton(stringResource(R.string.ag_cam_shoot), enabled = !busy, onClick = {
                    busy = true
                    capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bytes = try {
                                val buf = image.planes[0].buffer
                                ByteArray(buf.remaining()).also { buf.get(it) }
                            } finally {
                                image.close()
                            }
                            onShot(bytes, image.imageInfo.rotationDegrees)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            onFail()
                        }
                    })
                }, modifier = Modifier.testTag(CameraTags.SHOOT))
                SoftButton(stringResource(R.string.ag_cam_flip), onClick = { front = !front }, modifier = Modifier.testTag(CameraTags.FLIP))
            }
        }
    }
}

private class Bound {
    @Volatile var provider: ProcessCameraProvider? = null
}

private fun bind(context: Context, owner: LifecycleOwner, preview: PreviewView, capture: ImageCapture, front: Boolean, onFail: () -> Unit): Bound {
    val bound = Bound()
    val future = runCatching { ProcessCameraProvider.getInstance(context) }.getOrElse { onFail(); return bound }
    future.addListener({
        try {
            val p = future.get()
            bound.provider = p
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val view = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            p.unbindAll()
            p.bindToLifecycle(owner, selector, view, capture)
        } catch (e: Exception) {
            onFail()
        }
    }, ContextCompat.getMainExecutor(context))
    return bound
}
