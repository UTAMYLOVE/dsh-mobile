package app.dshmobile.agency

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import app.agency.core.brain.Brain
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.llm.AnthropicClient
import app.agency.core.llm.CallChecks
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.DeepSeekSearchClient
import app.agency.core.llm.OpenAiCompatClient
import app.agency.core.llm.ProviderRouter
import app.agency.core.metering.CallBudget
import app.agency.core.metering.Provider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.ImportKind
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.Transport
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.PlanStore
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.media.AudioRecorder
import app.dshmobile.agency.media.CameraCapture
import app.dshmobile.agency.media.MediaPlayerEngine
import app.dshmobile.agency.media.MediaRecorderEngine
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.agency.media.PlayerEngine
import app.dshmobile.agency.media.RecorderEngine
import app.dshmobile.agency.metering.CostMeter
import app.dshmobile.agency.migrate.ImportReport
import app.dshmobile.agency.migrate.LegacyCleanup
import app.dshmobile.agency.migrate.LegacyExport
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.agency.schedule.AlarmScheduler
import app.dshmobile.agency.schedule.WordingWorker
import app.dshmobile.agency.secure.TransientDataException
import app.dshmobile.log.ErrorLog
import app.dshmobile.security.KeyVault
import okhttp3.HttpUrl
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Seams the instrumentation runner and Robolectric tests set before the company first opens (always null in production).
 * [modelBase]: every model request goes to this base (a fake model on 127.0.0.1) and the network gate allows only its origin.
 */
class TestOverrides(
    val modelBase: HttpUrl? = null,
    val openDatabase: ((Context) -> AgencyDatabase)? = null,
    val mediaVault: ((Context) -> MediaVault)? = null,
    /** Wraps the store the importer and the brain use (a test makes it fail on purpose). */
    val store: ((AgencyStore) -> AgencyStore)? = null,
    /** Stands in for the camera app: writes a picture into the file and says whether it did (RESULT_OK). */
    val camera: ((File) -> Boolean)? = null,
    /** Stands in for the microphone. */
    val recorder: (() -> RecorderEngine)? = null,
    /** Stands in for the speaker. */
    val player: (() -> PlayerEngine)? = null,
    /** The rollout week a test starts in (分週上線; a new install starts in week 1). */
    val rollout: app.agency.core.schedule.RolloutSpec? = null,
    /** The in-app camera cannot run here (Robolectric has no camera): photos take the system-camera fallback. */
    val systemCamera: Boolean = false,
    /** The company's clock (the emulator's whole-day test moves through an invented day); the phone's own clock when null. */
    val clock: AgencyClock? = null,
    /** 入社建檔 opens by itself on a fresh install (a screen test of something else turns it off). */
    val autoOnboarding: Boolean = true,
)

/** A line about a photo or the recording that could not be kept (shown on the 入社建檔 panel until the next try). */
enum class MediaNotice { NO_CAMERA, LOCKED, NOT_SAVED, NO_CAMERA_PERMISSION }

/** Where the company stands at start. [Ready] carries what the screens use. */
sealed interface StartState {
    /** Opening the encrypted database. */
    data object Opening : StartState

    /** The first import of his coach records is running (「正在搬資料」). */
    data object Importing : StartState

    data class Ready(val store: AgencyStore, val brain: Brain) : StartState

    /** The database does not open with its key, or cannot be opened at all (「資料鎖打不開」); nothing was deleted. */
    data object Locked : StartState

    /** The database could not be opened for a passing reason (disk full, busy): he can try again. Nothing was deleted. */
    data object Unavailable : StartState
}

/**
 * A line on the home screen after the import. [PARTIAL] and [MEDIA_PENDING] are shown once (kept in prefs until he taps
 * 知道了, so a process that dies first does not lose them); [FAILED] comes back at each start that fails again.
 */
enum class ImportNotice { PARTIAL, MEDIA_PENDING, FAILED }

/** What 設定 › 舊資料 shows, read from the outcome row `import-legacy` only (never from the failed-attempt row). */
data class LegacyStatus(
    val kind: Kind,
    val counts: Map<ImportKind, Int> = emptyMap(),
    val note: ImportNote? = null,
    /** The folder the legacy files are in now: `coach` while partial, `coach.migrated-<ts>` after the import. */
    val folder: File? = null,
) {
    enum class Kind { NOTHING, DONE, PARTIAL, NOT_YET }
}

/**
 * The agency's singletons (arch 1.1 `AgencyApp`): the encrypted store, the media vault, the importer, the provider clients
 * behind the network gate, the brain and the notifier stub. Held by [app.dshmobile.DshMobileApp]; nothing runs until [start].
 *
 * [start] opens the database and runs the first import, never on the main thread. Every failure is caught and nothing is ever
 * deleted: a lost key or an unopenable file is [StartState.Locked], a passing failure [StartState.Unavailable] (try again), and
 * an import that fails, is partial or throws still ends in [StartState.Ready] (the import tries again at the next start; a
 * partial one when its files change or he taps 「再試一次」). 「正在搬資料」 shows only while a first import runs, never while
 * `agency_prefs.migrated == 2`.
 */
class AgencyApp(
    private val context: Context,
    val keyVault: KeyVault,
    val costMeter: CostMeter,
    private val errorLog: ErrorLog,
    private val overrides: TestOverrides? = testOverrides,
    private val clock: AgencyClock = overrides?.clock ?: AgencyClock.system(),
) {
    private val agencyPrefs = context.getSharedPreferences(AgencyPrefKeys.NAME, Context.MODE_PRIVATE)
    private val importPrefs = context.getSharedPreferences(AgencyPrefKeys.IMPORT_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LegacyPrefs.NAME, Context.MODE_PRIVATE)
    val settings = AgencySettings(agencyPrefs)
    val notifier = AgencyNotifier(context)
    val scheduler = AlarmScheduler(context)

    /** Whether 入社建檔's page opens by itself while it was never finished (always on the phone). */
    val autoOnboarding: Boolean get() = testOverrides?.autoOnboarding ?: true
    /** Anything thrown outside the start's own catches is logged and ends on a try-again screen, never a crash at start. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            errorLog.append(TAG, "start threw (${e.javaClass.simpleName})")
            if (_start.value !is StartState.Ready) _start.value = StartState.Unavailable
        },
    )

    /** His coach-day start hour (profile), for the compatible service's daily cap; read at open and after each message. */
    @Volatile private var dayStartHour = DEFAULT_DAY_START

    private val _start = MutableStateFlow<StartState>(StartState.Opening)
    val start: StateFlow<StartState> = _start.asStateFlow()

    private val _notice = MutableStateFlow<ImportNotice?>(null)
    val notice: StateFlow<ImportNotice?> = _notice.asStateFlow()

    private val _mediaNotice = MutableStateFlow<MediaNotice?>(null)
    val mediaNotice: StateFlow<MediaNotice?> = _mediaNotice.asStateFlow()

    /** The 入社建檔 camera (arch T07): its pending capture survives the process in `agency_prefs`. */
    val camera = CameraCapture(MediaVault.cameraDir(context), agencyPrefs, { f ->
        FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, f)
    })

    @Volatile private var job: Job? = null
    @Volatile private var vault: MediaVault? = null
    @Volatile private var db: AgencyDatabase? = null
    @Volatile private var importer: LegacyImporter? = null

    /**
     * Opens the company once, and again after [StartState.Unavailable] or [StartState.Locked] (再試一次: opening deletes
     * nothing, and a Keystore that was only busy may answer now). A call while it opens or once it is open does nothing.
     */
    fun start() {
        synchronized(this) {
            if (job?.isActive == true) return
            if (_start.value is StartState.Ready) return
            job = scope.launch { open() }
        }
    }

    private suspend fun open() {
        _start.value = StartState.Opening
        // A shot the camera may still answer for (the process died while it was open) is kept; everything else goes.
        runCatching { MediaVault.sweepCameraCache(MediaVault.cameraDir(context), keep = setOfNotNull(camera.pendingName())) }
        runCatching { MediaVault.sweepCameraCache(AudioRecorder.recordDir(context)) }
        // Copies he shared from 設定 › 舊資料 are plaintext: they live only until the next start.
        runCatching { File(context.cacheDir, EXPORT_DIR).listFiles()?.forEach { it.deleteRecursively() } }
        storedNotice()?.let { _notice.value = it }
        val opened = try {
            overrides?.openDatabase?.invoke(context) ?: AgencyDatabase.open(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientDataException) {
            errorLog.append(TAG, "database not available now (${e.javaClass.simpleName})")
            _start.value = StartState.Unavailable
            return
        } catch (e: Throwable) {
            // DataLockedException, Room's IllegalStateException, a Keystore runtime error, UnsatisfiedLinkError: never a crash
            // loop and never a wipe.
            errorLog.append(TAG, "database locked (${e.javaClass.simpleName})")
            _start.value = StartState.Locked
            return
        }
        db = opened
        val store = RoomAgencyStore(opened).let { overrides?.store?.invoke(it) ?: it }
        val vault = overrides?.mediaVault?.invoke(context) ?: MediaVault.forApp(context)
        this.vault = vault
        val imp = LegacyImporter(context.filesDir, legacyPrefs, agencyPrefs, importPrefs, store, vault)
        importer = imp
        if (agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == 0 && legacyPresent()) _start.value = StartState.Importing
        importAndSeal(imp)
        seedPlan(store)
        refreshDayStart(store)
        val brain = brain(store)
        startOnboarding(brain, imp)
        val ready = StartState.Ready(store, brain)
        _start.value = ready
        remind(ready)
        checkRelease()
    }

    /** The release check behind the network gate (null until the brain is built, and in tests with a model server). */
    private var releases: app.agency.core.net.ReleaseCheck? = null

    /**
     * 新版本通知 (owner 2026-11-27): at most once a day (the day is kept before the request, so a failure is not retried the
     * same day), the latest release; a newer one is kept for 設定 and notified once. Never with a test model server.
     */
    suspend fun checkRelease() {
        // Tests never reach the network: they call [checkReleaseWith] with their own server.
        if (overrides != null) return
        checkReleaseWith(releases ?: return)
    }

    /** [checkRelease] with [check] (a test's server). */
    internal suspend fun checkReleaseWith(check: app.agency.core.net.ReleaseCheck) {
        val today = java.time.LocalDate.now(clock.zone).toString()
        if (settings.releaseCheckedDay == today) return
        settings.releaseCheckedDay = today
        val r = try {
            check.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "release check failed (${e.javaClass.simpleName})")
            null
        } ?: return
        if (app.agency.core.net.ReleaseCheck.newer(r, app.dshmobile.BuildConfig.VERSION_NAME, app.dshmobile.BuildConfig.VERSION_CODE)) {
            val known = settings.newRelease
            settings.newRelease = "${r.versionName}|${r.page}"
            if (known?.substringBefore('|') != r.versionName) notifier.newRelease(r.versionName, r.page)
        } else {
            settings.newRelease = null
        }
    }

    /**
     * The alarm (or a reboot, a clock change, an app update) fired: the company opens if it is not open yet, and one tick posts
     * what is due and re-arms. Called from the receivers, off the main thread.
     */
    suspend fun onAlarm() {
        // A slow database, a lock held, the receiver's time running out or a throw never ends the chain (S2): unless a tick
        // armed the next alarm, a safety alarm is armed on the way out (cancellation included).
        var armed = false
        try {
            start()
            val ready = withTimeoutOrNull(OPEN_WAIT_MS) { _start.first { it is StartState.Ready || it is StartState.Locked } } as? StartState.Ready ?: return
            armed = remind(ready)
            checkRelease()
        } finally {
            if (!armed) armFallback()
        }
    }

    /**
     * One tick of the scheduler (arch 1.6): every post on screen at once with its code template, the gap notification gone
     * when nothing is missing, the next alarm armed, and the model's wording asked for afterwards (within its caps). Also run
     * after each of his messages, so a filled gap clears at once. A failure is logged, never thrown.
     */
    private suspend fun remind(ready: StartState.Ready): Boolean {
        try {
            val tick = ready.brain.tick()
            tick.posts.forEach(notifier::post)
            if (tick.gapsCleared) notifier.cancelGaps()
            val mode = scheduler.arm(tick.next)
            if (mode == AlarmScheduler.Mode.WINDOW) errorLog.append(TAG, "exact alarms not allowed: window")
            tick.posts.filter { it.word }.forEach { p ->
                runCatching { WordingWorker.enqueue(context, p) }.onFailure { errorLog.append(TAG, "wording not queued (${it.javaClass.simpleName})") }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "reminders failed (${e.javaClass.simpleName})")
            armFallback()
            return false
        }
    }

    /** The next alarm in [FALLBACK_MS] whatever happens next (S2: the chain of reminders never breaks). */
    private fun armFallback() {
        runCatching { scheduler.arm(app.agency.core.engine.Fire(java.time.Instant.now().plusMillis(FALLBACK_MS), app.agency.core.engine.FireKind.CHASE)) }
            .onFailure { errorLog.append(TAG, "fallback alarm not armed (${it.javaClass.simpleName})") }
    }

    /** The wording worker: the model's words for [post] (it stays the template when there are none). */
    suspend fun word(post: app.agency.core.engine.Post) {
        val ready = _start.value as? StartState.Ready ?: run {
            start()
            withTimeoutOrNull(OPEN_WAIT_MS) { _start.first { it is StartState.Ready } } as? StartState.Ready
        } ?: return
        try {
            ready.brain.wordNudge(post.nudgeId)?.let { notifier.update(post.copy(text = it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "wording failed (${e.javaClass.simpleName})")
        }
    }

    /**
     * 入社建檔 on the first open (arch T07), once the import is done: never while a partial or failed import may still bring
     * his old 入社建檔, nor while its photos wait to be sealed (they are linked when they are; he is not asked for them again).
     */
    private suspend fun startOnboarding(brain: Brain, imp: LegacyImporter) {
        try {
            if (agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) != AgencyPrefKeys.MIGRATED_DONE) return
            if (imp.pendingMedia().isNotEmpty()) return
            brain.startOnboardingIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "onboarding not started (${e.javaClass.simpleName})")
        }
    }

    /** Plan v3's sections on the first open (arch T11); a failure is logged and the defaults are used until the next open. */
    private suspend fun seedPlan(store: AgencyStore) {
        try {
            val n = PlanStore(store).seedIfMissing(clock.millis(), overrides?.rollout ?: app.agency.core.schedule.RolloutSpec.SEED)
            if (n > 0) errorLog.append(TAG, "plan sections seeded=$n")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "plan not seeded (${e.javaClass.simpleName})")
        }
    }

    /** The media vault, once the company is open (thumbnails and playback decrypt from it into memory only). */
    fun mediaVault(): MediaVault? = vault

    /** Tests stand in for the microphone (no permission is asked then). */
    fun hasRecorderSeam(): Boolean = overrides?.recorder != null

    fun recorderEngine(): RecorderEngine = overrides?.recorder?.invoke() ?: MediaRecorderEngine(context)

    fun playerEngine(): PlayerEngine = overrides?.player?.invoke() ?: MediaPlayerEngine()

    /**
     * 拍照 for a 入社建檔 photo: the camera intent to launch, or null when the test camera already answered (then the result is
     * on its way, as from the camera app).
     */
    fun photoIntent(step: OnboardingStep): Intent? = capture { camera.begin(step) }

    /** 拍這餐 (T15): the camera intent for the meal being reported, or null when the test camera already answered. */
    fun mealPhotoIntent(): Intent? = capture { camera.beginMeal() }

    /** A 理論課 實作 photo (T19c) for lesson [lessonId], or null when the test camera already answered. */
    fun lessonPhotoIntent(lessonId: String): Intent? = capture { camera.beginLesson(lessonId) }

    private fun capture(begin: () -> Intent): Intent? {
        _mediaNotice.value = null
        val intent = try {
            begin()
        } catch (e: Exception) {
            errorLog.append(TAG, "camera not prepared (${e.javaClass.simpleName})")
            _mediaNotice.value = MediaNotice.NOT_SAVED
            return null
        }
        val seam = overrides?.camera ?: return intent
        val ok = camera.pending()?.file?.let(seam) ?: false
        onCameraResult(ok)
        return null
    }

    private val _inAppCamera = MutableStateFlow<app.dshmobile.agency.media.PhotoTarget?>(null)

    /** The in-app camera's target while its viewfinder is open (T15b); the activity shows it over the company. */
    val inAppCamera: StateFlow<app.dshmobile.agency.media.PhotoTarget?> = _inAppCamera.asStateFlow()

    /** Whether photos take the system-camera round trip: a test stands in for the camera, or says the in-app one cannot run. */
    fun hasCameraSeam(): Boolean = overrides?.camera != null || overrides?.systemCamera == true

    fun openCamera(target: app.dshmobile.agency.media.PhotoTarget) {
        _mediaNotice.value = null
        _inAppCamera.value = target
    }

    fun closeCamera() {
        _inAppCamera.value = null
    }

    /** The camera permission was refused: nothing is taken (從相簿選 needs no permission). */
    fun cameraDenied() {
        _inAppCamera.value = null
        _mediaNotice.value = MediaNotice.NO_CAMERA_PERMISSION
    }

    /**
     * The system camera for [target] (the fallback when CameraX cannot run, and the test seam's path): its intent, or null
     * when the test camera already answered.
     */
    fun systemCameraIntent(target: app.dshmobile.agency.media.PhotoTarget): Intent? = when (target) {
        app.dshmobile.agency.media.PhotoTarget.Meal -> mealPhotoIntent()
        is app.dshmobile.agency.media.PhotoTarget.Lesson -> lessonPhotoIntent(target.lessonId)
        is app.dshmobile.agency.media.PhotoTarget.Baseline -> photoIntent(target.step)
        else -> capture { camera.beginFor(target.purpose) }
    }

    /**
     * A frame of the in-app camera (T15b), in memory only: cleaned (upright, ≤ 1600 px, a fresh JPEG with no metadata), sealed
     * into the vault, the plaintext buffers cleared, and handed in for [target]. No file is written and nothing reaches the
     * gallery.
     */
    fun onFrame(target: app.dshmobile.agency.media.PhotoTarget, jpeg: ByteArray, rotation: Int) {
        _inAppCamera.value = null
        scope.launch {
            val ready = _start.first { it is StartState.Ready } as StartState.Ready
            val v = vault ?: return@launch
            val clean = app.dshmobile.agency.media.PhotoBytes.clean(jpeg, rotation)
            jpeg.fill(0)
            if (clean == null) {
                _mediaNotice.value = MediaNotice.NOT_SAVED
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val sealed = try {
                v.put(id, clean)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "frame not sealed (${e.javaClass.simpleName})")
                _mediaNotice.value = if (e is app.dshmobile.agency.secure.DataUnavailableException) MediaNotice.LOCKED else MediaNotice.NOT_SAVED
                return@launch
            } finally {
                clean.fill(0)
            }
            handPhoto(ready, v, target, Media(id, MediaKind.PHOTO, target.purpose, CameraCapture.MIME, sealed.bytes, sealed.sha256, clock.millis()))
        }
    }

    /**
     * 從相簿選 (T15c): a photo he picked with the system photo picker (no storage permission), read from [uri] on IO. Its capture
     * time is read from its EXIF first (on the phone only), then it is cleaned and sealed like a frame; a meal photo goes on
     * the meal of that time (補交 when late).
     */
    fun onPicked(target: app.dshmobile.agency.media.PhotoTarget, uri: android.net.Uri) {
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        check(out.size() <= MAX_PICK_BYTES) { "too large" }
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            if (bytes == null) {
                _mediaNotice.value = MediaNotice.NOT_SAVED
                return@launch
            }
            onPickedBytes(target, bytes)
        }
    }

    /** [onPicked] after the read: the capture time, then clean, seal and hand in. */
    internal suspend fun onPickedBytes(target: app.dshmobile.agency.media.PhotoTarget, bytes: ByteArray) {
        val ready = _start.first { it is StartState.Ready } as StartState.Ready
        val v = vault ?: return
        val takenAt = app.dshmobile.agency.media.PhotoBytes.takenAt(bytes, clock.zone)
        val clean = app.dshmobile.agency.media.PhotoBytes.clean(bytes)
        bytes.fill(0)
        if (clean == null) {
            _mediaNotice.value = MediaNotice.NOT_SAVED
            return
        }
        val id = UUID.randomUUID().toString()
        val sealed = try {
            v.put(id, clean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "picked photo not sealed (${e.javaClass.simpleName})")
            _mediaNotice.value = if (e is app.dshmobile.agency.secure.DataUnavailableException) MediaNotice.LOCKED else MediaNotice.NOT_SAVED
            return
        } finally {
            clean.fill(0)
        }
        handPhoto(ready, v, target, Media(id, MediaKind.PHOTO, target.purpose, CameraCapture.MIME, sealed.bytes, sealed.sha256, clock.millis()), takenAt, picked = true)
    }

    /** A sealed photo for [target]: its step, its lesson, its meal (then read when it has no words yet), or the model's read. */
    private suspend fun handPhoto(ready: StartState.Ready, v: MediaVault, target: app.dshmobile.agency.media.PhotoTarget, media: Media, takenAt: Long? = null, picked: Boolean = false) {
        when (target) {
            is app.dshmobile.agency.media.PhotoTarget.Baseline -> handIn(ready, v, target.step, media)
            is app.dshmobile.agency.media.PhotoTarget.Lesson -> practical(ready, v, target.lessonId, media)
            app.dshmobile.agency.media.PhotoTarget.Meal -> {
                val line = mealPhoto(ready, v, media, takenAt, picked) ?: return
                val meal = line.refs.records.firstOrNull()?.let { runCatching { ready.store.meals.get(it) }.getOrNull() }
                if (meal != null && meal.items.isEmpty() && meal.photoMediaId == media.id) readPhoto(ready, v, app.agency.core.model.PhotoReadKind.MEAL, media)
            }
            app.dshmobile.agency.media.PhotoTarget.Check -> runCatching { ready.brain.handInPractice(app.agency.core.model.TrainingKind.SURPRISE_CHECK, media) }
                .onFailure { runCatching { if (ready.store.media.get(media.id) == null) v.delete(media.id) } }
            app.dshmobile.agency.media.PhotoTarget.Body -> {
                // The Sunday 全身對比照 (when it is due today) goes on the timeline, then the teacher's words as always.
                runCatching { ready.brain.handInPractice(app.agency.core.model.TrainingKind.BODY_PHOTO, media) }
                readPhoto(ready, v, app.agency.core.model.PhotoReadKind.BODY, media)
            }
            else -> readPhoto(ready, v, target.read ?: return, media)
        }
    }

    /** A practice timer finished, a practice tap, the 心情 tap (rollout weeks 3 and 4). */
    fun practiceDone(kind: app.agency.core.model.TrainingKind, seconds: Int) = talk { it.practiceDone(kind, seconds) }

    fun practiceTap(kind: app.agency.core.model.TrainingKind) = talk { it.practiceTap(kind) }

    fun mood(level: Int) = talk { it.mood(level) }

    /** An evaluation recording (每週評鑑, 月評): sealed into the vault, its media (not written yet), or null. */
    suspend fun sealEvalAudio(file: File): Media? = sealFile(file, MediaKind.AUDIO, AudioRecorder.MIME)

    private suspend fun sealFile(file: File, kind: MediaKind, mime: String): Media? {
        val v = vault ?: return null.also { file.delete() }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            try {
                val sealed = v.putFile(id, file)
                Media(id, kind, MediaPurpose.EVAL, mime, sealed.bytes, sealed.sha256, clock.millis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "eval media not sealed (${e.javaClass.simpleName})")
                _mediaNotice.value = MediaNotice.NOT_SAVED
                null
            } finally {
                file.delete()
            }
        }
    }

    private var videoFile: File? = null
    private val _evalVideo = MutableStateFlow<Media?>(null)

    /** The dance take of an evaluation, once the camera app gave it (sealed); the screen takes it with [takeEvalVideo]. */
    val evalVideo: StateFlow<Media?> = _evalVideo.asStateFlow()

    /** Still frames of the last dance take (cleaned, in memory only) for the 月評 suggestion; cleared when used. */
    private var danceFrames: List<app.agency.core.vision.VisionImage> = emptyList()

    /** Where the camera app writes the dance take (`cache/camera/`, through the FileProvider). */
    fun videoUri(): android.net.Uri? = runCatching {
        val dir = MediaVault.cameraDir(context).apply { mkdirs() }
        val f = File(dir, "eval-${UUID.randomUUID()}.mp4")
        videoFile = f
        FileProvider.getUriForFile(context, "${context.packageName}.export", f)
    }.getOrNull()

    /** The camera app's answer for the dance take: two frames are kept in memory for the 月評, the file sealed and deleted. */
    fun onVideoResult(ok: Boolean) {
        val f = videoFile ?: return
        videoFile = null
        scope.launch {
            if (!ok || !f.isFile || f.length() == 0L) {
                f.delete()
                return@launch
            }
            danceFrames = runCatching { frames(f) }.getOrDefault(emptyList())
            _evalVideo.value = sealFile(f, MediaKind.VIDEO, "video/mp4")
        }
    }

    fun takeEvalVideo(): Media? = _evalVideo.value.also { _evalVideo.value = null }

    private fun frames(f: File): List<app.agency.core.vision.VisionImage> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
            listOf(ms / 3, ms * 2 / 3).mapNotNull { t ->
                val bmp = r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                app.dshmobile.agency.media.PhotoBytes.forModel(out.toByteArray())
            }
        } finally {
            runCatching { r.release() }
        }
    }

    fun weeklyEval(song: Media, dance: Media) = talk { it.weeklyEval(song, dance) }

    /**
     * 月評 handed in: the image model's suggestions (today's 全身對比照 and the dance frames, only when he chose a provider that
     * sees photos), then code's scores and the announcement.
     */
    fun monthlyEval(handIn: app.agency.core.engine.MonthlyResult, media: List<Media>, self: Map<app.agency.core.engine.EvalItem, Int>) = talk { brain ->
        val v = vault
        val body = runCatching { brain.bodyTimeline().lastOrNull()?.takeIf { it.first == brain.today().toString() }?.second }.getOrNull()
        val images = if (v == null || !brain.photosReadable()) emptyList() else {
            listOfNotNull(body?.let { b -> runCatching { app.dshmobile.agency.media.PhotoBytes.forModel(v.get(b)) }.getOrNull() }) + danceFrames.take(2)
        }
        danceFrames = emptyList()
        val looks = if (images.isEmpty() || body == null) emptyList() else brain.evalLooks(images, hasDance = images.size > 1)
        val vocal = handIn.song?.let { id -> runCatching { songPitch(v, id) }.getOrNull() }
        brain.monthlyEval(handIn.copy(bodyPhoto = body), media, self, vocal, looks)
    }

    /** The solo song's 音準 share by code (T19d): decoded and tracked on the phone; null when it cannot be read. */
    private suspend fun songPitch(v: MediaVault?, mediaId: String): Int? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val vault = v ?: return@withContext null
        val f = File(AudioRecorder.recordDir(context).apply { mkdirs() }, "pitch-${UUID.randomUUID()}.m4a")
        try {
            f.writeBytes(vault.get(mediaId))
            val pcm = app.dshmobile.agency.media.AudioDecode.pcm(f) ?: return@withContext null
            val s16 = app.agency.core.singing.Wav.resample(pcm.samples, pcm.rate, SINGING_RATE)
            app.agency.core.singing.Analysis.of(app.agency.core.singing.Pitch.track(s16, SINGING_RATE)).inTunePct
        } finally {
            f.delete()
        }
    }

    private var asr: app.agency.core.singing.DeepSeekAsr? = null

    /** The reference melody he imported (UltraStar or MIDI), or his 示範錄音; kept for this session only (his own file stays his). */
    private val _singingRef = MutableStateFlow<List<app.agency.core.singing.RefNote>?>(null)
    val singingRef: StateFlow<List<app.agency.core.singing.RefNote>?> = _singingRef.asStateFlow()

    /** The last take's chart (PNG) and code's lines, for the singing screen. */
    data class SingingView(val chart: ByteArray, val lines: List<String>)

    private val _singing = MutableStateFlow<SingingView?>(null)
    val singing: StateFlow<SingingView?> = _singing.asStateFlow()

    /** 匯入原唱: an UltraStar song file or a MIDI melody he picked; false when it could not be read. */
    fun importReference(uri: android.net.Uri, done: (Boolean) -> Unit) {
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size <= MAX_PICK_BYTES } } }.getOrNull()
            }
            val notes = bytes?.let { b ->
                if (b.size >= 4 && String(b, 0, 4, Charsets.US_ASCII) == "MThd") app.agency.core.singing.Reference.midi(b)
                else app.agency.core.singing.Reference.ultraStar(String(b, Charsets.UTF_8))
            }
            if (notes != null) _singingRef.value = notes
            done(notes != null)
        }
    }

    fun clearReference() {
        _singingRef.value = null
    }

    /**
     * A take (T19d): decoded and tracked on the phone, the facts and (with a reference) the comparison computed by code, the
     * chart drawn; with [lyrics] and his consent the take (≤ 2 minutes) goes to speech to text for 咬字; [asDemo] keeps it as
     * the reference instead. The recording is sealed (it never goes anywhere else); the teacher's words come after.
     */
    suspend fun handInSinging(file: File, lyrics: String, aCappella: Boolean, asDemo: Boolean): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false.also { file.delete() }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pcm = app.dshmobile.agency.media.AudioDecode.pcm(file)
            if (pcm == null) {
                file.delete()
                _mediaNotice.value = MediaNotice.NOT_SAVED
                return@withContext false
            }
            val s16 = app.agency.core.singing.Wav.resample(pcm.samples, pcm.rate, SINGING_RATE)
            val frames = app.agency.core.singing.Pitch.track(s16, SINGING_RATE)
            if (asDemo) {
                _singingRef.value = app.agency.core.singing.Reference.demo(frames)
                file.delete()
                return@withContext true
            }
            val ref = _singingRef.value
            val lines = app.agency.core.singing.Analysis.of(frames).lines().toMutableList()
            ref?.let { lines += app.agency.core.singing.Score.compare(frames, it, ready.brain.singingStrictness(), aCappella).lines() }
            if (lyrics.isNotBlank() && settings.dictionConsent) {
                val heard = asr?.transcribe(app.agency.core.singing.Wav.write(s16, SINGING_RATE))
                if (heard is app.agency.core.singing.DeepSeekAsr.Result.Ok) lines += app.agency.core.singing.Diction.lines(app.agency.core.singing.Diction.check(lyrics, heard.text))
            }
            val png = app.agency.core.singing.Chart.png(frames, ref.orEmpty())
            _singing.value = SingingView(png, lines)
            val media = sealFile(file, MediaKind.AUDIO, AudioRecorder.MIME)?.copy(purpose = MediaPurpose.SINGING) ?: return@withContext false
            val chart = runCatching {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                bmp.recycle()
                app.dshmobile.agency.media.PhotoBytes.forModel(out.toByteArray())
            }.getOrNull()
            runCatching { ready.brain.singing(media, lines, chart) }.isSuccess
        }
    }

    /** The image model's read of [media] (T15b): the cleaned vault copy, and for 身體照 the 入社建檔 front photo to compare. */
    private suspend fun readPhoto(ready: StartState.Ready, v: MediaVault, kind: app.agency.core.model.PhotoReadKind, media: Media) {
        try {
            val images = if (!ready.brain.photosReadable()) {
                emptyList()
            } else {
                val own = runCatching { app.dshmobile.agency.media.PhotoBytes.forModel(v.get(media.id)) }.getOrNull()
                val base = if (kind == app.agency.core.model.PhotoReadKind.BODY) {
                    ready.store.baselines.get(app.agency.core.model.Baseline.JOIN)?.photoFront?.let { f -> runCatching { app.dshmobile.agency.media.PhotoBytes.forModel(v.get(f)) }.getOrNull() }
                } else {
                    null
                }
                listOfNotNull(own, base.takeIf { own != null })
            }
            ready.brain.readPhoto(kind, media, images)
            if (costMeter.consumeAlert()) notifier.costAlert(costMeter.todayUsd())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "photo not read (${e.javaClass.simpleName})")
            runCatching { if (ready.store.media.get(media.id) == null) v.delete(media.id) }
        }
    }

    /** 確認 / 不要 on a reading card (T15b): his kept rows become facts only now. */
    fun confirmPhoto(readId: String, draft: app.agency.core.vision.ReadDraft) = talk { it.confirmPhoto(readId, draft) }

    fun dropPhoto(readId: String) = talk { it.dropPhoto(readId) }

    /** There is no camera app to take the picture. */
    fun cameraMissing() {
        camera.cancel()
        _mediaNotice.value = MediaNotice.NO_CAMERA
    }

    /**
     * The camera's answer (also after the process died: the activity registers for it at once and this waits until the company
     * is open). The shot is sealed and handed in for its step, or put on the meal (拍這餐); cancelled leaves the step open and
     * the meal 「未證實」.
     */
    fun onCameraResult(ok: Boolean) {
        scope.launch {
            val ready = _start.first { it is StartState.Ready } as StartState.Ready
            val v = vault ?: return@launch
            val captured = try {
                camera.finish(ok, v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "photo not sealed (${e.javaClass.simpleName})")
                _mediaNotice.value = if (e is app.dshmobile.agency.secure.DataUnavailableException) MediaNotice.LOCKED else MediaNotice.NOT_SAVED
                null
            } ?: return@launch
            val step = captured.step
            val lesson = captured.lessonId
            val target = when {
                lesson != null -> app.dshmobile.agency.media.PhotoTarget.Lesson(lesson)
                step != null -> app.dshmobile.agency.media.PhotoTarget.Baseline(step)
                captured.media.purpose == MediaPurpose.MENU -> app.dshmobile.agency.media.PhotoTarget.Menu
                captured.media.purpose == MediaPurpose.RECEIPT -> app.dshmobile.agency.media.PhotoTarget.Receipt
                captured.media.purpose == MediaPurpose.BODY -> app.dshmobile.agency.media.PhotoTarget.Body
                captured.media.purpose == MediaPurpose.CHECK -> app.dshmobile.agency.media.PhotoTarget.Check
                else -> app.dshmobile.agency.media.PhotoTarget.Meal
            }
            handPhoto(ready, v, target, captured.media)
        }
    }

    /** A meal's photo: the media row and its meal are written together; a failure removes the sealed file again. */
    private suspend fun mealPhoto(ready: StartState.Ready, v: MediaVault, media: Media, takenAt: Long? = null, picked: Boolean = false): app.agency.core.model.Message? {
        return try {
            (if (picked) ready.brain.attachPickedMealPhoto(media, takenAt) else ready.brain.attachMealPhoto(media)).also {
                _mediaNotice.value = null
                if (ready.store.media.get(media.id) == null) runCatching { v.delete(media.id) } // the feature was closed: nothing kept
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "meal photo not saved (${e.javaClass.simpleName})")
            runCatching { if (ready.store.media.get(media.id) == null) v.delete(media.id) }
            _mediaNotice.value = MediaNotice.NOT_SAVED
            null
        }
    }

    /**
     * A lesson's 實作 (T19c), sealed in the vault: the media row and its link are written together; when the brain does not
     * take it (another kind, a lesson without a 實作) or it fails, the sealed file goes again. It never leaves the phone.
     */
    private suspend fun practical(ready: StartState.Ready, v: MediaVault, lessonId: String, media: Media): Boolean {
        val kept = try {
            ready.brain.handInPractical(lessonId, media)
            ready.store.media.get(media.id) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "practical not saved (${e.javaClass.simpleName})")
            false
        }
        if (!kept) {
            runCatching { if (ready.store.media.get(media.id) == null) v.delete(media.id) }
            _mediaNotice.value = MediaNotice.NOT_SAVED
        } else {
            _mediaNotice.value = null
        }
        return kept
    }

    /** 交出 for a lesson's recording (T19c): sealed as a stream, the plaintext deleted, handed in. */
    suspend fun handInLessonRecording(lessonId: String, file: File, durationMs: Long): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false.also { file.delete() }
        val v = vault ?: return false.also { file.delete() }
        return scope.async {
            val id = UUID.randomUUID().toString()
            val sealed = try {
                v.putFile(id, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "lesson recording not sealed (${e.javaClass.simpleName})")
                _mediaNotice.value = if (e is app.dshmobile.agency.secure.DataUnavailableException) MediaNotice.LOCKED else MediaNotice.NOT_SAVED
                return@async false
            }
            errorLog.append(TAG, "lesson recording sealed ms=$durationMs")
            practical(ready, v, lessonId, Media(id, MediaKind.AUDIO, MediaPurpose.LESSON, AudioRecorder.MIME, sealed.bytes, sealed.sha256, clock.millis()))
        }.await()
    }

    /** 理論課 (T19c): what the tab's buttons do, off the main thread (each answer is a manager line and reloads the tab). */
    fun findVideos(category: app.agency.core.model.LessonCategory?) = talk { it.findVideos(category) }
    fun approveLesson(id: String) = talk { it.approveLesson(id) }
    fun dropLesson(id: String) = talk { it.dropLesson(id) }
    fun lessonWatched(id: String) = talk { it.lessonWatched(id) }
    fun lessonKeyPoints(id: String, points: List<String>) = talk { it.lessonKeyPoints(id, points) }
    fun answerLesson(id: String, choices: List<Int>) = talk { it.answerLesson(id, choices) }
    fun lessonNote(id: String, text: String) = talk { it.lessonNote(id, text) }
    fun startReview(id: String) = talk { it.startReview(id) }
    fun answerReview(id: String, choices: List<Int>) = talk { it.answerReview(id, choices) }

    /** 去看: the stored link of the open lesson (the activity hands it to his YouTube app or browser), or null. */
    suspend fun openLesson(id: String): String? {
        val ready = _start.value as? StartState.Ready ?: return null
        return scope.async { runCatching { ready.brain.openLesson(id) }.getOrNull() }.await()
    }

    /** 刪掉照片 on a meal, after his confirm (T15): the meal goes back to 「未證實」 and the sealed file goes. */
    suspend fun deleteMealPhoto(mealId: String): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false
        return scope.async {
            val media = try {
                ready.brain.deleteMealPhoto(mealId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog.append(TAG, "meal photo not deleted (${e.javaClass.simpleName})")
                null
            } ?: return@async false
            runCatching { vault?.delete(media) }
            true
        }.await()
    }

    /** The label form (T15): kcal and protein he typed for an item waiting for its label. */
    fun label(mealId: String, itemIdx: Int, kcal: Int, proteinG: Double?) = talk { it.label(mealId, itemIdx, kcal, proteinG) }

    /**
     * 交出 for the 清唱錄音: [file] (the recorder's plaintext) is sealed as a stream, deleted, and handed in. False when it
     * could not be kept (the line says why); the plaintext is gone either way.
     */
    suspend fun handInRecording(file: File, durationMs: Long): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false.also { file.delete() }
        val v = vault ?: return false.also { file.delete() }
        return scope.async {
            val id = UUID.randomUUID().toString()
            val sealed = try {
                v.putFile(id, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "recording not sealed (${e.javaClass.simpleName})")
                _mediaNotice.value = if (e is app.dshmobile.agency.secure.DataUnavailableException) MediaNotice.LOCKED else MediaNotice.NOT_SAVED
                return@async false
            }
            errorLog.append(TAG, "recording sealed ms=$durationMs")
            handIn(ready, v, OnboardingStep.RECORDING, Media(id, MediaKind.AUDIO, MediaPurpose.BASELINE, AudioRecorder.MIME, sealed.bytes, sealed.sha256, clock.millis()))
        }.await()
    }

    /** Writes the media row and the link; a failure removes the sealed file again (no row points at it). */
    private suspend fun handIn(ready: StartState.Ready, v: MediaVault, step: OnboardingStep, media: Media): Boolean = try {
        ready.brain.handInBaseline(step, media)
        _mediaNotice.value = null
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorLog.append(TAG, "baseline media not saved (${e.javaClass.simpleName})")
        runCatching { if (ready.store.media.get(media.id) == null) v.delete(media.id) }
        _mediaNotice.value = MediaNotice.NOT_SAVED
        false
    }

    /** 補交 on the trainee card: the first missing 入社建檔 item is asked again. */
    fun reopenOnboarding() {
        val ready = _start.value as? StartState.Ready ?: return
        scope.launch {
            try {
                ready.brain.reopenOnboarding()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog.append(TAG, "onboarding not reopened (${e.javaClass.simpleName})")
            }
        }
    }

    /** The import (first run, a changed partial, or the retry of a failed one), then any pending media; sets the notice. */
    private suspend fun importAndSeal(imp: LegacyImporter) {
        val report = try {
            imp.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "import threw (${e.javaClass.simpleName})")
            null
        }
        logImport(report)
        when {
            report == null -> _notice.value = ImportNotice.FAILED
            report.partialNotice -> setNotice(ImportNotice.PARTIAL)
            !report.ok && !report.skipped && !report.partial -> _notice.value = ImportNotice.FAILED
            report.ok && !report.skipped && report.note?.mediaPending.orEmpty().isNotEmpty() -> setNotice(ImportNotice.MEDIA_PENDING)
            report.ok && _notice.value == ImportNotice.FAILED -> _notice.value = null
        }
        val sealed = try {
            imp.sealPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog.append(TAG, "pending media threw (${e.javaClass.simpleName})")
            0
        }
        if (sealed > 0) {
            errorLog.append(TAG, "pending media sealed=$sealed")
            // Everything is in now: the line about pending photos is no longer true.
            if (_notice.value == ImportNotice.MEDIA_PENDING && runCatching { imp.pendingMedia() }.getOrNull()?.isEmpty() == true) dismissNotice()
        }
    }

    private suspend fun refreshDayStart(store: AgencyStore) {
        dayStartHour = runCatching { store.profile.get()?.dayStartHour }.getOrNull() ?: DEFAULT_DAY_START
    }

    /** The coach day the compatible service's daily cap counts in (his day start, not a fixed hour). */
    internal fun quotaDay(): String = CoachRules.coachDay(clock.now(), clock.zone, dayStartHour).toString()

    private fun storedNotice(): ImportNotice? =
        agencyPrefs.getString(NOTICE_KEY, null)?.let { n -> ImportNotice.entries.firstOrNull { it.name == n } }

    private fun setNotice(n: ImportNotice) {
        agencyPrefs.edit().putString(NOTICE_KEY, n.name).commit()
        _notice.value = n
    }

    private fun legacyPresent(): Boolean = File(context.filesDir, LegacyImporter.LEGACY_DIR).isDirectory || legacyPrefs.all.isNotEmpty()

    /** Counts and flags only, never content. */
    private fun logImport(r: ImportReport?) {
        if (r == null || r.skipped) return
        errorLog.append(TAG, "import ok=${r.ok} partial=${r.partial} inserted=${r.inserted.values.sum()} planned=${r.counts.values.sum()}")
    }

    fun dismissNotice() {
        agencyPrefs.edit().remove(NOTICE_KEY).commit()
        _notice.value = null
    }

    /**
     * 設定 › 舊資料 「再試一次」 after a failed first import (NOT_YET): runs the import now instead of at the next process
     * start, and the pending media after it. It runs in the company's own scope on IO (its prefs writes and log lines too), so
     * leaving the page does not cut it short; the caller only waits for it.
     */
    suspend fun retryFirstImport() {
        val imp = importer ?: return
        scope.async {
            importAndSeal(imp)
            (_start.value as? StartState.Ready)?.let { startOnboarding(it.brain, imp) }
        }.await()
    }

    /** Sends his message to the brain, off the main thread; a failure is logged, never thrown at the screen. */
    fun send(text: String) = talk { it.onText(text) }

    /** A guided timer finished (T18): the core, the rounds with their report, the stretch; 快走完成. */
    fun coreDone(seconds: Int) = talk { it.coreDone(seconds) }

    fun strengthDone(report: app.agency.core.brain.handlers.StrengthReport) = talk { it.strengthDone(report) }

    fun stretchDone(seconds: Int) = talk { it.stretchDone(seconds) }

    fun cardioDone(seconds: Int) = talk { it.cardioDone(seconds) }

    fun walkDone() = talk { it.walkDone() }

    /** The 狀態打分 picker and the bedtime review sheet (T19). */
    fun condition(score: Int, sleep: Int?, energy: Int?, ill: app.agency.core.engine.IllKind?) = talk { it.condition(score, sleep, energy, ill) }

    fun review(good: String, fix: String) = talk { it.review(good, fix) }

    /** 重送 on a line of his marked 「沒送出」 (T12): the same row is answered again; nothing is copied. */
    fun resend(messageId: String) = talk { it.resend(messageId) }

    private fun talk(say: suspend (app.agency.core.brain.Brain) -> Unit) {
        val ready = _start.value as? StartState.Ready ?: return
        scope.launch {
            try {
                say(ready.brain)
                refreshDayStart(ready.store)
                remind(ready)
                if (costMeter.consumeAlert()) notifier.costAlert(costMeter.todayUsd())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog.append(TAG, "message not answered (${e.javaClass.simpleName})")
            }
        }
    }

    /** 設定 › 舊資料: the import's state, from the `import-legacy` row. */
    suspend fun legacyStatus(): LegacyStatus {
        val store = (_start.value as? StartState.Ready)?.store ?: return LegacyStatus(LegacyStatus.Kind.NOT_YET)
        val row = store.journal.migrations().firstOrNull { it.id == LegacyImporter.LOG_ID }
        if (row == null) {
            val done = agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == AgencyPrefKeys.MIGRATED_DONE
            return LegacyStatus(if (done) LegacyStatus.Kind.NOTHING else LegacyStatus.Kind.NOT_YET)
        }
        val counts = runCatching {
            Json.parseToJsonElement(row.countsJson).jsonObject.mapNotNull { (k, v) ->
                ImportKind.entries.firstOrNull { it.name == k }?.let { it to v.jsonPrimitive.int }
            }.toMap()
        }.getOrDefault(emptyMap())
        val note = row.note?.let { runCatching { ImportNote.fromJson(it) }.getOrNull() }
        val folder = if (row.ok) {
            context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith(LegacyImporter.LEGACY_DIR + ".migrated-") }?.maxByOrNull { it.name }
        } else {
            File(context.filesDir, LegacyImporter.LEGACY_DIR).takeIf { it.isDirectory }
        }
        return LegacyStatus(if (row.ok) LegacyStatus.Kind.DONE else LegacyStatus.Kind.PARTIAL, counts, note, folder)
    }

    /** His old dsh conversations and novels (`filesDir/userdata`), when anything is left there; never deleted by the app. */
    fun oldChats(): File? = File(context.filesDir, LegacyCleanup.USER_DATA).takeIf { d -> d.isDirectory && d.walkTopDown().any { it.isFile } }

    /** His old novels (`userdata/workspace/novels`), when any file is there (owner 2026-11-27: the novels may go, chats stay). */
    fun oldNovels(): File? = File(context.filesDir, LegacyCleanup.NOVELS).takeIf { d -> d.isDirectory && d.walkTopDown().any { it.isFile } }

    /**
     * 刪除舊小說 after his two confirmations: only `userdata/workspace/novels` (the old chats stay), and an `audit_log` row
     * (counts only). True when they are gone.
     */
    suspend fun deleteOldNovels(): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false
        val dir = File(context.filesDir, LegacyCleanup.NOVELS)
        if (!dir.isDirectory) return false
        return scope.async {
            val files = dir.walkTopDown().count { it.isFile }
            val gone = dir.deleteRecursively() || !dir.exists()
            runCatching { ready.store.journal.audit(app.agency.core.model.AuditLog(UUID.randomUUID().toString(), clock.millis(), "old_novels_deleted", "files=$files ok=$gone")) }
            errorLog.append(TAG, "old novels deleted ok=$gone")
            gone
        }.await()
    }

    /**
     * 匯出舊聊天與小說 / 先分享整個舊資料夾: [folder] zipped into `cache/export/` (served by the FileProvider, swept at the next
     * start); the folder itself is not changed. Null when it could not be written.
     */
    suspend fun zipForShare(folder: File, name: String): File? = scope.async {
        try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date(clock.millis()))
            val r = LegacyExport.zip(folder, File(File(context.cacheDir, EXPORT_DIR), "$name-$stamp.zip"))
            errorLog.append(TAG, "zipped files=${r.files} skipped=${r.skipped}")
            r.zip
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "zip failed (${e.javaClass.simpleName})")
            null
        }
    }.await()

    /**
     * 刪除舊資料夾 (arch 1.14 step 5): the kept `coach.migrated-*` folder, only after a finished import, only that folder in
     * `filesDir`, and an `audit_log` row (counts only). True when it is gone.
     */
    suspend fun deleteLegacyFolder(folder: File): Boolean {
        val ready = _start.value as? StartState.Ready ?: return false
        val ok = folder.parentFile?.canonicalFile == context.filesDir.canonicalFile &&
            folder.name.startsWith(LegacyImporter.LEGACY_DIR + ".migrated-") && legacyStatus().kind == LegacyStatus.Kind.DONE
        if (!ok) return false
        return scope.async {
            val gone = folder.deleteRecursively() || !folder.exists()
            runCatching {
                ready.store.journal.audit(app.agency.core.model.AuditLog(UUID.randomUUID().toString(), clock.millis(), "legacy_folder_deleted", "ok=$gone"))
            }
            errorLog.append(TAG, "legacy folder deleted ok=$gone")
            gone
        }.await()
    }

    /** 設定 › 舊資料 「再試一次」 after a partial import: in the company's own scope on IO, like [retryFirstImport]. */
    suspend fun retryImport(): ImportReport? {
        val imp = importer ?: return null
        return scope.async {
            try {
                imp.retry().also { logImport(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorLog.append(TAG, "retry threw (${e.javaClass.simpleName})")
                null
            }
        }.await()
    }

    /** The provider clients behind the one network gate, and the brain on top. */
    private fun brain(store: AgencyStore): Brain {
        val base = overrides?.modelBase
        val gate = NetworkGate(
            allowed = {
                if (base != null) {
                    setOf(Origin(base.scheme, base.host, base.port))
                } else {
                    runCatching { NetworkGate.allowlist(settings.compatUrl) }.getOrElse { NetworkGate.allowlist(null) }
                }
            },
            onBlocked = { host -> errorLog.append("net", "blocked host $host") },
        )
        val transport = Transport(HttpFactory.create(gate))
        releases = app.agency.core.net.ReleaseCheck(transport)
        val checks = CallChecks(costMeter, CallBudget(clock = Clock.systemUTC()), onBudgetRefused = notifier::rateLimited)
        val deepseek = OpenAiCompatClient(
            transport,
            { if (base != null) CompatEndpoint.deepseek(settings.deepseekModel, base) else CompatEndpoint.deepseek(settings.deepseekModel) },
            { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter,
        )
        val claude = AnthropicClient(transport, { settings.claudeModel }, { keyVault.get(Provider.ANTHROPIC) }, checks, costMeter, base ?: AnthropicClient.BASE)
        val compat = OpenAiCompatClient(
            transport, { settings.compatEndpoint(allowHttp = base != null) }, { keyVault.getNamed(AgencySettings.COMPAT_KEY) }, checks, costMeter,
            takeQuota = { settings.takeCompatRequest(quotaDay()) },
        )
        // The data clerk's call and the second-pass judge's (arch 1.9b) go to the cheapest model of the provider he chose (never
        // another provider): one sorts his sentence into a kind (controller ruling on the T06 review M3), the other reads a
        // guarded reply for three paraphrase classes. Both share the checks and the meter, so they count toward the spend cap.
        // The compatible service has one model.
        val deepseekClerk = OpenAiCompatClient(
            transport,
            { if (base != null) CompatEndpoint.deepseek(CompatEndpoint.DEEPSEEK_FLASH, base) else CompatEndpoint.deepseek(CompatEndpoint.DEEPSEEK_FLASH) },
            { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter,
        )
        val claudeClerk = AnthropicClient(transport, { CLAUDE_CLERK }, { keyVault.get(Provider.ANTHROPIC) }, checks, costMeter, base ?: AnthropicClient.BASE)
        // Nearby food (T16): DeepSeek Search with his DeepSeek key, through the same gate, checks and meter (the spend cap
        // stops it before any socket; a non-empty answer is billed). Only his coarse area and a dish name are sent.
        val search = DeepSeekSearchClient(transport, { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter, base ?: CompatEndpoint.DEEPSEEK_BASE)
        // Photos (T15b): the image model of the provider he chose, through the same gate, checks and meter (the spend cap stops
        // it before any socket). Only a cleaned photo can be sent; never a recording.
        val vision = app.agency.core.vision.VisionRouter(
            { settings.provider },
            app.agency.core.vision.DeepSeekVisionClient(transport, { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter, base ?: CompatEndpoint.DEEPSEEK_BASE),
            app.agency.core.vision.ClaudeVisionClient(transport, { settings.claudeModel }, { keyVault.get(Provider.ANTHROPIC) }, checks, costMeter, base ?: AnthropicClient.BASE),
        )
        // 咬字 (T19d): the only path a recording may take, after his one-time consent, through the same gate, checks and meter.
        asr = app.agency.core.singing.DeepSeekAsr(
            transport, { keyVault.get(Provider.DEEPSEEK) }, checks, costMeter, { settings.dictionConsent },
            base?.newBuilder()?.addPathSegments("v1/tools/asr")?.build() ?: okhttp3.HttpUrl.Builder().scheme("https").host("api.deepseek.com").addPathSegments("v1/tools/asr").build(),
        )
        return Brain(
            store, ProviderRouter({ settings.provider }, deepseek, claude, compat), { settings.provider }, clock,
            intakeLlm = ProviderRouter({ settings.provider }, deepseekClerk, claudeClerk, compat),
            log = { line -> errorLog.append("brain", line) },
            search = search,
            vision = vision,
        )
    }

    /** The backup of everything (加密備份), or null before the company is open. */
    private fun backup(): app.dshmobile.agency.backup.Backup? {
        val d = db ?: return null
        val v = vault ?: return null
        return app.dshmobile.agency.backup.Backup(
            { d.openHelper.writableDatabase }, v,
            older = { version -> app.dshmobile.agency.backup.OlderSchema.open(context, version) },
            migrations = app.dshmobile.agency.data.AgencyDatabase.MIGRATIONS.toList(),
        )
    }

    /** 匯出備份檔: everything, sealed with [password], into [out] (closed afterwards). The keys are never in it. */
    suspend fun exportBackup(out: () -> java.io.OutputStream?, password: CharArray): Result<app.dshmobile.agency.backup.BackupSummary> = scope.async {
        try {
            val b = backup() ?: return@async Result.failure(IllegalStateException("not open"))
            val stream = out() ?: return@async Result.failure(java.io.IOException("no file"))
            Result.success(b.export(stream, password, clock.millis())).also { errorLog.append(TAG, "backup exported rows=${it.getOrNull()?.rows?.values?.sum()}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "backup not exported (${e.javaClass.simpleName})")
            Result.failure(e)
        } finally {
            password.fill(' ')
        }
    }.await()

    /** 匯回 step one: reads and checks the whole file, changes nothing; what it holds. */
    suspend fun backupSummary(input: () -> java.io.InputStream?, password: CharArray): Result<app.dshmobile.agency.backup.BackupSummary> = scope.async {
        try {
            val b = backup() ?: return@async Result.failure(IllegalStateException("not open"))
            val stream = input() ?: return@async Result.failure(java.io.IOException("no file"))
            stream.use { Result.success(b.summary(it, password)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "backup not read (${e.javaClass.simpleName})")
            Result.failure(e)
        } finally {
            password.fill(' ')
        }
    }.await()

    /**
     * 匯回 step two, after his two confirmations: every record and photo is replaced by the backup's, then the company opens
     * again on them. His keys stay as they are on this phone (a new phone asks for them in 設定).
     */
    suspend fun restoreBackup(input: () -> java.io.InputStream?, password: CharArray): Result<app.dshmobile.agency.backup.BackupSummary> = scope.async {
        val r = try {
            val b = backup() ?: return@async Result.failure(IllegalStateException("not open"))
            val stream = input() ?: return@async Result.failure(java.io.IOException("no file"))
            stream.use { Result.success(b.restore(it, password)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "backup not restored (${e.javaClass.simpleName})")
            Result.failure(e)
        } finally {
            password.fill(' ')
        }
        if (r.isSuccess) {
            errorLog.append(TAG, "backup restored")
            reopen()
        }
        r
    }.await()

    /** Closes the database and opens the company again (after a restore: a new brain reads the restored records). */
    private suspend fun reopen() {
        db?.close()
        db = null
        vault = null
        importer = null
        _start.value = StartState.Opening
        job = null
        start()
    }

    /** Test only: closes the database and forgets the start, so the next [start] opens again (the e2e suite shares a process). */
    @VisibleForTesting
    suspend fun stopForTest() {
        job?.cancelAndJoin()
        db?.close()
        db = null
        vault = null
        importer = null
        _mediaNotice.value = null
        _notice.value = null
        _start.value = StartState.Opening
    }

    companion object {
        private const val TAG = "agency"
        private const val NOTICE_KEY = "notice_pending"
        private const val EXPORT_DIR = "export"
        private const val PROVIDER_SUFFIX = ".export"
        private const val DEFAULT_DAY_START = 5
        private const val OPEN_WAIT_MS = 8_000L

        /** The safety alarm's delay (S2). */
        const val FALLBACK_MS = 15 * 60_000L
        const val SINGING_RATE = 16_000
        private const val MAX_PICK_BYTES = 30 * 1024 * 1024

        /** The cheapest Claude model, for the data clerk and the second-pass judge only. */
        const val CLAUDE_CLERK = "claude-haiku-5-5"

        /** Set by the instrumentation runner (and tests) before the company first opens. */
        @VisibleForTesting
        @Volatile
        var testOverrides: TestOverrides? = null
    }
}
