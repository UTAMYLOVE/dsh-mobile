package app.dshmobile.ui.agency

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.Intent
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import app.dshmobile.agency.notify.AgencyNotifier
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.agency.core.brain.handlers.OnboardingStep
import app.dshmobile.DshMobileApp
import app.dshmobile.ui.kit.SakuraTheme

/**
 * The launcher (arch 1.13): 小雨娛樂. Opens the company ([app.dshmobile.agency.AgencyApp.start], off the main thread) and shows
 * the start screens, then 公司 / 指揮室 / 設定.
 *
 * The camera's answer is registered here, before the screen exists, so a result that comes back after the process died (the
 * camera app was open for a long time) still reaches the pending capture (arch T07).
 */
class AgencyActivity : ComponentActivity() {
    private val agency get() = (application as DshMobileApp).agency

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        agency.onCameraResult(r.resultCode == Activity.RESULT_OK)
    }

    /** The camera permission (T15b), asked the first time the in-app camera is needed; [waiting] is the photo it is for. */
    private var waiting: app.dshmobile.agency.media.PhotoTarget? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val t = waiting ?: return@registerForActivityResult
        waiting = null
        if (granted) {
            agency.openCamera(t)
        } else {
            // Refused: 從相簿選 still works (no permission), so it opens for the same photo.
            agency.cameraDenied()
            pick(t)
        }
    }

    /** 從相簿選 (T15c): the system photo picker, no storage permission; [picking] is the photo it is for. */
    private var picking: app.dshmobile.agency.media.PhotoTarget? = null
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val t = picking ?: return@registerForActivityResult
        picking = null
        if (uri != null) agency.onPicked(t, uri)
    }

    /** The dance take of an evaluation (每週評鑑, 月評): the camera app records into cache/camera/, then it is sealed. */
    private val video = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> agency.onVideoResult(ok) }

    /** The camera app needs the camera permission the app declares: asked first, then the take. */
    private val videoPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) takeVideo() else agency.cameraDenied() }

    private fun takeVideo() {
        if (!agency.hasCameraSeam() && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return runCatching { videoPermission.launch(Manifest.permission.CAMERA) }.let { }
        }
        val uri = agency.videoUri() ?: return agency.cameraMissing()
        try {
            video.launch(uri)
        } catch (_: ActivityNotFoundException) {
            agency.cameraMissing()
        } catch (_: SecurityException) {
            agency.cameraDenied()
        }
    }

    /** Reminders need notifications (asked at run time); asked once, 設定 › 權限檢查 shows it afterwards. */
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val agency = agency
        agency.start()
        setContent {
            SakuraTheme {
                AgencyRoot(
                    agency,
                    AgencyActions(
                        takePhoto = ::takePhoto, askNotifications = ::askNotificationsOnce, takeMealPhoto = ::takeMealPhoto, openLink = ::openLink,
                        takeLessonPhoto = ::takeLessonPhoto, photo = ::photo, pick = ::pick, takeVideo = ::takeVideo,
                    ),
                )
                // The in-app camera over everything while it is open (T15b).
                val target by agency.inAppCamera.collectAsState()
                target?.let { t ->
                    androidx.activity.compose.BackHandler { agency.closeCamera() }
                    app.dshmobile.ui.agency.camera.InAppCamera(
                        t, onShot = { bytes, rotation -> agency.onFrame(t, bytes, rotation) },
                        onFail = { agency.closeCamera(); systemCamera(t) }, onClose = agency::closeCamera,
                        onPick = { agency.closeCamera(); pick(t) },
                    )
                }
            }
        }
        if (savedInstanceState == null) fromNotification(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        fromNotification(intent)
    }

    /** A meal slot's reminder leads to the camera (arch T13/T15): its photo goes on the meal being reported. */
    private fun fromNotification(intent: Intent?) {
        val slot = SlotKey.parse(intent?.getStringExtra(AgencyNotifier.EXTRA_SLOT)) ?: return
        intent?.removeExtra(AgencyNotifier.EXTRA_SLOT)
        if (slot.kind == SlotKind.MEAL) takeMealPhoto()
    }

    /** Only an http(s) link from a search result, to whatever browser he has. */
    private fun openLink(url: String) {
        // https only: a plain http link would leave the phone unencrypted (REVIEW-safety M5).
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()?.takeIf { it.scheme == HTTPS } ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    private fun takeMealPhoto() = photo(app.dshmobile.agency.media.PhotoTarget.Meal)

    /** A 理論課 實作 photo (T19c). */
    private fun takeLessonPhoto(lessonId: String) = photo(app.dshmobile.agency.media.PhotoTarget.Lesson(lessonId))

    /**
     * A photo for [target] (T15b): the app's own camera, after the camera permission (asked the first time it is needed); a
     * test camera seam keeps the system-camera round trip.
     */
    private fun photo(target: app.dshmobile.agency.media.PhotoTarget) {
        if (agency.hasCameraSeam()) return systemCamera(target)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return agency.openCamera(target)
        waiting = target
        try {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } catch (_: Exception) {
            waiting = null
            agency.cameraDenied()
        }
    }

    /** 從相簿選 for [target]: the system photo picker (images only). */
    private fun pick(target: app.dshmobile.agency.media.PhotoTarget) {
        picking = target
        try {
            picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {
            picking = null
            agency.cameraMissing()
        }
    }

    /** The system camera app: the fallback when the in-app camera cannot run here. */
    private fun systemCamera(target: app.dshmobile.agency.media.PhotoTarget) {
        val intent = agency.systemCameraIntent(target) ?: return
        try {
            camera.launch(intent)
        } catch (_: ActivityNotFoundException) {
            agency.cameraMissing()
        } catch (_: SecurityException) {
            agency.cameraDenied()
        }
    }

    private fun askNotificationsOnce() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(app.agency.core.migrate.AgencyPrefKeys.NAME, MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun takePhoto(step: OnboardingStep) = photo(app.dshmobile.agency.media.PhotoTarget.Baseline(step))
}

private const val ASKED_NOTIFICATIONS = "asked_notifications"
private const val HTTPS = "https"

/**
 * What the screens ask the activity to do (things that need an activity): the camera app (a 入社建檔 photo, a meal's), and
 * the notification permission (asked once when the reminders start, after 入社建檔).
 */
class AgencyActions(
    val takePhoto: (OnboardingStep) -> Unit = {},
    val askNotifications: () -> Unit = {},
    /** 拍這餐 (T15): the camera for the meal being reported. */
    val takeMealPhoto: () -> Unit = {},
    /** 打開 on a nearby result (T16), 去看 on a lesson (T19c): its link in his browser or YouTube app (the app has no web view). */
    val openLink: (String) -> Unit = {},
    /** A 理論課 實作 photo (T19c). */
    val takeLessonPhoto: (String) -> Unit = {},
    /** Any photo by its target (T15b: 拍菜單, 拍收據, 身體照 too): the in-app camera. */
    val photo: (app.dshmobile.agency.media.PhotoTarget) -> Unit = {},
    /** 從相簿選 (T15c): the system photo picker for the same targets. */
    val pick: (app.dshmobile.agency.media.PhotoTarget) -> Unit = {},
    /** The dance take of an evaluation: the camera app records a video. */
    val takeVideo: () -> Unit = {},
)
