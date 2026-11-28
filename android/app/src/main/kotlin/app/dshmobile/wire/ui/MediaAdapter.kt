package app.dshmobile.wire.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.agency.core.agent.Attachment
import app.dshmobile.platform.AttachPickers
import app.dshmobile.platform.AttachResult
import app.dshmobile.platform.Attachments
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.ui.MediaUi
import app.dshmobile.wire.ActivityHost
import app.dshmobile.wire.CameraActivity
import app.dshmobile.wire.CameraHandoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera, pickers, paste and thumbnails. The in-app camera is its own screen ([CameraActivity]); the pickers are the system's
 * ([AttachPickers]); every URI becomes an [Attachment] through [Attachments] (photos cleaned and sealed in the vault, files read
 * to text). All suspend functions are called on the main thread and move to IO for the work.
 */
class ActivityMediaUi(
    private val context: Context,
    private val host: ActivityHost,
    private val attachments: () -> Attachments,
    private val vault: () -> MediaVault,
) : MediaUi {

    override suspend fun takePhoto(purpose: String): Attachment.Photo? {
        if (!host.askCamera()) return null
        val result = CompletableDeferred<Attachment.Photo?>()
        CameraHandoff.pending = result
        try {
            val intent = Intent(context, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_PURPOSE, purpose)
            host.launch(ActivityResultContracts.StartActivityForResult(), intent)
            // The screen finished (a photo was handed over, or he closed it, or it died): nothing more will come.
            if (!result.isCompleted) result.complete(null)
            return result.await()
        } finally {
            if (CameraHandoff.pending === result) CameraHandoff.pending = null
        }
    }

    override suspend fun pickPhotos(): List<Attachment.Photo> {
        val uris = host.launch(AttachPickers.photos(), AttachPickers.photosRequest).orEmpty()
        return withContext(Dispatchers.IO) {
            uris.mapNotNull { (attachments().photo(it, fromGallery = true) as? AttachResult.Ok)?.attachment as? Attachment.Photo }
        }
    }

    override suspend fun pickFile(): Attachment.File? {
        val uri = host.launch(AttachPickers.document(), AttachPickers.documentTypes) ?: return null
        return withContext(Dispatchers.IO) { (attachments().file(uri) as? AttachResult.Ok)?.attachment as? Attachment.File }
    }

    override suspend fun fromUris(uris: List<Uri>): List<Attachment> = withContext(Dispatchers.IO) {
        uris.mapNotNull { (attachments().pasted(it) as? AttachResult.Ok)?.attachment }
    }

    override suspend fun thumbnail(photoId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = vault().get(photoId)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }

    private companion object {
        const val THUMB_PX = 320
    }
}
