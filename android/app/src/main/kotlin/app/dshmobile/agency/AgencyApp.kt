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
)

/** A line about a photo or the recording that could not be kept (shown on the 入社建檔 panel until the next try). */
enum class MediaNotice { NO_CAMERA, LOCKED, NOT_SAVED }

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
    private val clock: AgencyClock = AgencyClock.system(),
) {
    private val agencyPrefs = context.getSharedPreferences(AgencyPrefKeys.NAME, Context.MODE_PRIVATE)
    private val importPrefs = context.getSharedPreferences(AgencyPrefKeys.IMPORT_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LegacyPrefs.NAME, Context.MODE_PRIVATE)
    val settings = AgencySettings(agencyPrefs)
    val notifier = AgencyNotifier(context)
    val scheduler = AlarmScheduler(context)
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
    }

    /**
     * The alarm (or a reboot, a clock change, an app update) fired: the company opens if it is not open yet, and one tick posts
     * what is due and re-arms. Called from the receivers, off the main thread.
     */
    suspend fun onAlarm() {
        start()
        val ready = withTimeoutOrNull(OPEN_WAIT_MS) { _start.first { it is StartState.Ready || it is StartState.Locked } } as? StartState.Ready ?: return
        remind(ready)
    }

    /**
     * One tick of the scheduler (arch 1.6): every post on screen at once with its code template, the gap notification gone
     * when nothing is missing, the next alarm armed, and the model's wording asked for afterwards (within its caps). Also run
     * after each of his messages, so a filled gap clears at once. A failure is logged, never thrown.
     */
    private suspend fun remind(ready: StartState.Ready) {
        try {
            val tick = ready.brain.tick()
            tick.posts.forEach(notifier::post)
            if (tick.gapsCleared) notifier.cancelGaps()
            val mode = scheduler.arm(tick.next)
            if (mode == AlarmScheduler.Mode.WINDOW) errorLog.append(TAG, "exact alarms not allowed: window")
            tick.posts.filter { it.word }.forEach { p ->
                runCatching { WordingWorker.enqueue(context, p) }.onFailure { errorLog.append(TAG, "wording not queued (${it.javaClass.simpleName})") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "reminders failed (${e.javaClass.simpleName})")
        }
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
            val n = PlanStore(store).seedIfMissing(clock.millis())
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
    fun photoIntent(step: OnboardingStep): Intent? {
        _mediaNotice.value = null
        val intent = try {
            camera.begin(step)
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

    /** There is no camera app to take the picture. */
    fun cameraMissing() {
        camera.cancel()
        _mediaNotice.value = MediaNotice.NO_CAMERA
    }

    /**
     * The camera's answer (also after the process died: the activity registers for it at once and this waits until the company
     * is open). The shot is sealed and handed in for its step; cancelled leaves the step open.
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
            handIn(ready, v, captured.step, captured.media)
        }
    }

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
    fun send(text: String) {
        val ready = _start.value as? StartState.Ready ?: return
        scope.launch {
            try {
                ready.brain.onText(text)
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
        return Brain(
            store, ProviderRouter({ settings.provider }, deepseek, claude, compat), { settings.provider }, clock,
            intakeLlm = ProviderRouter({ settings.provider }, deepseekClerk, claudeClerk, compat),
        )
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

        /** The cheapest Claude model, for the data clerk and the second-pass judge only. */
        const val CLAUDE_CLERK = "claude-haiku-5-5"

        /** Set by the instrumentation runner (and tests) before the company first opens. */
        @VisibleForTesting
        @Volatile
        var testOverrides: TestOverrides? = null
    }
}
