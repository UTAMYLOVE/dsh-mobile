package app.dshmobile.wire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.agency.core.agent.Attachment
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.platform.AttachResult
import app.dshmobile.platform.media.InAppCamera
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.paper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the camera screen leaves its photo for the suspended caller that started it (the picture itself never travels in an intent). */
internal object CameraHandoff {
    @Volatile var pending: CompletableDeferred<Attachment.Photo?>? = null
}

/**
 * The in-app camera: a viewfinder, a shutter, a switch between the back and front camera, and a close button. A shot goes from
 * memory through `Attachments.fromCamera` (cleaned for the model, sealed in the vault) and the screen closes by itself; nothing is
 * written to a file or the gallery. The caller is `ActivityMediaUi.takePhoto`.
 */
class CameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val purpose = intent.getStringExtra(EXTRA_PURPOSE).orEmpty()
        setContent {
            CameraScreen(
                purpose = purpose,
                onPhoto = { photo ->
                    CameraHandoff.pending?.complete(photo)
                    finish()
                },
                onClose = {
                    CameraHandoff.pending?.complete(null)
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_PURPOSE = "purpose"
    }
}

@Composable
private fun CameraScreen(purpose: String, onPhoto: (Attachment.Photo) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = context as ComponentActivity
    val graph = (context.applicationContext as DshMobileApp).graph
    val camera = remember { InAppCamera(context) }
    val scope = rememberCoroutineScope()
    var front by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    val preview = remember { PreviewView(context) }
    BackHandler(onBack = onClose)
    LaunchedEffect(front) { camera.bind(owner, preview, front) { failed = true } }
    DisposableEffect(Unit) { onDispose { camera.unbind() } }
    Column(Modifier.fillMaxSize().paper().safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KText(
            if (purpose.isBlank()) stringResource(R.string.cam_title) else stringResource(R.string.cam_title_for, purpose),
            size = Type.Heading, bold = true, modifier = Modifier.padding(top = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView({ preview }, Modifier.fillMaxSize().testTag("camera_preview"))
            if (failed) KText(stringResource(R.string.cam_failed), align = TextAlign.Center, modifier = Modifier.testTag("camera_failed"))
        }
        refused?.let { KText(it, color = Pal.Warn, size = Type.Small) }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            PillButton(stringResource(R.string.cam_close), onClose, Modifier.testTag("camera_close"), Pal.White)
            PillButton(
                stringResource(R.string.cam_shutter),
                {
                    busy = true
                    camera.shoot(
                        onShot = { shot ->
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { graph.attachments.fromCamera(shot) }
                                busy = false
                                when (r) {
                                    is AttachResult.Ok -> (r.attachment as? Attachment.Photo)?.let(onPhoto)
                                    is AttachResult.Refused -> refused = r.reason
                                }
                            }
                        },
                        onFail = { busy = false; failed = true },
                    )
                },
                Modifier.testTag("camera_shutter"), Pal.PinkStrong, enabled = !busy && !failed,
            )
            PillButton(stringResource(R.string.cam_switch), { front = !front }, Modifier.testTag("camera_switch"), Pal.Lavender)
        }
    }
}
