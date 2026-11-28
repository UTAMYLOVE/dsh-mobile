package app.dshmobile.platform.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID

/** One frame from the in-app camera: the JPEG bytes as CameraX produced them and the rotation to turn them upright. */
class CameraShot(val bytes: ByteArray, val rotation: Int)

/**
 * The app's own camera (CameraX) without any screen code: the viewfinder composable owns a [PreviewView] and calls [bind] /
 * [unbind] with its lifecycle; [shoot] takes one picture into memory (no file, nothing in the gallery) and hands it to
 * `Attachments.fromCamera`. [onFail] means CameraX cannot run here (the caller falls back to [SystemCamera]).
 */
class InAppCamera(private val context: Context) {
    private val capture: ImageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()

    @Volatile private var provider: ProcessCameraProvider? = null

    fun bind(owner: LifecycleOwner, preview: PreviewView, front: Boolean, onFail: () -> Unit) {
        val future = runCatching { ProcessCameraProvider.getInstance(context) }.getOrElse {
            onFail()
            return
        }
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val view = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, view, capture)
            } catch (_: Exception) {
                onFail()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
    }

    fun shoot(onShot: (CameraShot) -> Unit, onFail: () -> Unit) {
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val shot = try {
                        val buf = image.planes[0].buffer
                        CameraShot(ByteArray(buf.remaining()).also { buf.get(it) }, image.imageInfo.rotationDegrees)
                    } finally {
                        image.close()
                    }
                    onShot(shot)
                }

                override fun onError(exception: ImageCaptureException) = onFail()
            },
        )
    }
}

/**
 * The fallback when CameraX cannot run: one photo through the system camera. [begin] makes an empty `cache/camera/<uuid>.jpg`,
 * remembers it in [prefs] (so a result that arrives after the process died still finds it) and returns the
 * `ACTION_IMAGE_CAPTURE` intent that writes into it through the FileProvider ([uriFor]). The caller launches it, then hands the
 * answer to [take], which returns the plaintext bytes and always deletes the file. The plaintext never outlives [take].
 */
class SystemCamera(
    private val cameraDir: File,
    private val prefs: SharedPreferences,
    private val uriFor: (File) -> Uri,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun begin(): Intent {
        cameraDir.mkdirs()
        pendingFile()?.delete() // an older capture that never came back
        val file = File(cameraDir, newId() + ".jpg")
        file.createNewFile()
        check(prefs.edit().putString(KEY, file.name).commit()) { "could not save the pending capture" }
        val uri = uriFor(file)
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri("", uri) }
    }

    /** The file name a start-up sweep must keep (a result may still come for it). */
    fun pendingName(): String? = pendingFile()?.name

    /** The camera's answer: the shot's bytes, or null (cancelled, nothing written, nothing waiting). The file is gone after. */
    fun take(ok: Boolean): ByteArray? {
        val file = pendingFile() ?: return null
        try {
            if (!ok || !file.isFile || file.length() == 0L) return null
            return file.readBytes()
        } finally {
            file.delete()
            prefs.edit().remove(KEY).commit()
        }
    }

    /** Forgets a capture that could not start (no camera app): its file goes too. */
    fun cancel() {
        pendingFile()?.delete()
        prefs.edit().remove(KEY).commit()
    }

    private fun pendingFile(): File? = prefs.getString(KEY, null)?.takeIf { NAME.matches(it) }?.let { File(cameraDir, it) }

    companion object {
        const val KEY = "pending_capture"
        private val NAME = Regex("[A-Za-z0-9-]{1,64}\\.jpg")

        fun cameraDir(context: Context) = File(context.cacheDir, "camera")

        fun forApp(context: Context): SystemCamera = SystemCamera(
            cameraDir(context),
            context.getSharedPreferences("xiaoyu_camera", Context.MODE_PRIVATE),
            { f -> androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".export", f) },
        )
    }
}
