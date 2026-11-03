package app.dshmobile.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.chat.AttachmentLoader
import app.dshmobile.chat.AttachmentReason
import app.dshmobile.chat.ConversationStore
import app.dshmobile.chat.LoadResult
import app.dshmobile.chat.PendingAttachment
import app.dshmobile.chat.decodeThumbnail
import app.dshmobile.ui.chat.DockSend
import app.dshmobile.ui.chat.sendFromDock
import app.dshmobile.ui.shell.AttachmentChip
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the main screen keeps across a recreated activity (final review B2-I1, B2-M1): where the shell is ([nav]), the dock's
 * composer (text, picked attachments, the sending flag, the error line), the unsaved settings fields and a deep link waiting for
 * a busy settings page. Not in the shell session: a dsh restart must not wipe the owner's draft.
 *
 * A process death keeps the tab, the open conversation, whether the list was open and the composer's text ([SavedStateHandle]);
 * attachments (the picker's grants end with the process) and the settings draft (a half-typed key must never land in a Bundle)
 * are memory only. A fresh start opens the conversation that was open last time ([app.dshmobile.ui.fog.UiPrefs.lastSessionId]).
 *
 * Sends run in the shell session's scope and hold this object, never the activity.
 */
class MainViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app get() = getApplication<DshMobileApp>()

    val nav = ShellNav()

    var composerText by mutableStateOf("")
    /** Why the last dock send did not happen; cleared by typing. */
    var dockError by mutableStateOf<String?>(null)
    /** Picked attachments, in pick order: only the picker's URI and name, never bytes (those are read when sending). */
    var attachments by mutableStateOf(emptyList<PendingAttachment>())
    /** An attachment send is under way (reading and uploading take a while): send is off so it cannot go twice. */
    var sending by mutableStateOf(false)
        private set

    /** Unsaved settings fields live here, outside the tab, so switching tabs (and turning the phone) keeps them. Memory only. */
    var settingsDraft: SettingsDraft? = null

    /** A conversation a notification asked for while settings could not be left; opened once it can (final review B2-M5). */
    var pendingOpen: String? = null
        private set

    val attachmentLoader: AttachmentLoader by lazy {
        val resolver = application.contentResolver
        AttachmentLoader(
            open = { resolver.openInputStream(it) },
            describe = ::describeUri,
            mimeOf = { uri ->
                resolver.getType(uri)
                    ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
                        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            },
        )
    }

    init {
        val saved = handle.get<Bundle>(STATE)
        if (saved != null) {
            saved.getString(TAB)?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }?.let { nav.tab = it }
            nav.openSessionId = saved.getString(OPEN)
            nav.listOpen = saved.getBoolean(LIST)
            composerText = saved.getString(TEXT).orEmpty()
        } else {
            nav.openSessionId = app.uiPrefs.lastSessionId
        }
        handle.setSavedStateProvider(STATE) {
            Bundle().apply {
                putString(TAB, nav.tab.name)
                putString(OPEN, nav.openSessionId)
                putBoolean(LIST, nav.listOpen)
                putString(TEXT, composerText)
            }
        }
        // Leaving settings while one of its actions runs would cancel it (a key save, an export) or hide its result.
        nav.canLeave = { from -> from != ShellTab.SETTINGS || !settingsBusy() }
        nav.onLeaveRefused = { toast(app.getString(R.string.shell_settings_busy)) }
        viewModelScope.launch {
            combine(snapshotFlow { settingsDraft?.localBusy == true }, app.settingsJobs.busy) { local, job -> local || job }
                .collect { busy -> if (!busy) openPending() }
        }
    }

    fun settingsBusy(): Boolean = settingsDraft?.localBusy == true || app.settingsJobs.busy.value

    /** A notification's conversation: opened now like a tab tap, or kept until settings can be left. */
    fun openFromLink(id: String) {
        nav.select(ShellTab.CHAT)
        if (nav.tab == ShellTab.CHAT) {
            pendingOpen = null
            nav.openSession(id)
        } else {
            pendingOpen = id
        }
    }

    private fun openPending() {
        val id = pendingOpen ?: return
        openFromLink(id)
    }

    fun onText(text: String) {
        composerText = text
        dockError = null
    }

    val canSend: Boolean get() = (composerText.isNotBlank() || attachments.isNotEmpty()) && !sending

    /**
     * The dock's send: to the open conversation (a new one when none is open), and the chat tab shows it. Text only: the field is
     * emptied at once and comes back unless the message was sent, also when the send is cut short (final review B2-M4). With
     * attachments the text and the tiles stay until dsh took the message (reading and uploading come first and may fail); send
     * is off meanwhile.
     */
    fun onDockSend() {
        val text = composerText
        val picked = attachments
        val session = app.shellSessions.current.value
        if ((text.isBlank() && picked.isEmpty()) || session == null || sending) return
        nav.select(ShellTab.CHAT)
        if (nav.tab != ShellTab.CHAT) return // settings is busy and said so; the text stays
        val held = picked.isNotEmpty()
        if (held) sending = true else composerText = ""
        dockError = null
        session.scope.launch(Dispatchers.Main.immediate) {
            var outcome: DockSend? = null
            try {
                outcome = sendFromDock(session, nav, text, picked, attachmentLoader)
            } finally {
                if (held) sending = false
                if (outcome != DockSend.Sent && !held && composerText.isEmpty()) composerText = text // nothing typed meanwhile: put it back
            }
            if (outcome == DockSend.Sent && held) {
                if (composerText == text) composerText = ""
                val sent = picked.mapTo(HashSet()) { it.id }
                attachments = attachments.filterNot { it.id in sent }
            }
            dockError = outcome?.let { describe(it, held) }
        }
    }

    private fun describe(outcome: DockSend, held: Boolean): String? = with(app) {
        when (outcome) {
            DockSend.Sent -> null
            is DockSend.NotCreated -> getString(R.string.chat_create_failed, outcome.code)
            is DockSend.Refused ->
                if (outcome.code == ConversationStore.OUT_OF_MEMORY) getString(R.string.attach_out_of_memory)
                else getString(if (held) R.string.attach_refused_with else R.string.attach_refused, outcome.code)
            is DockSend.AttachmentFailed -> when (outcome.reason) {
                AttachmentReason.TOO_LARGE -> getString(R.string.attach_too_large, outcome.name, AttachmentLoader.FILE_LIMIT / MB)
                AttachmentReason.UNREADABLE -> getString(R.string.attach_unreadable, outcome.name)
                AttachmentReason.OUT_OF_MEMORY -> getString(R.string.attach_out_of_memory)
                AttachmentReason.CONNECTION -> getString(R.string.attach_upload_failed, getString(R.string.attach_reason_connection))
                AttachmentReason.UNKNOWN -> getString(R.string.attach_upload_failed, getString(R.string.attach_reason_unknown))
                else -> getString(R.string.attach_upload_failed, outcome.reason)
            }
        }
    }

    /** What the pickers returned (an empty list: the picker was cancelled and nothing changes). Reads each file once to count it, off the main thread. */
    fun addPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { uris.distinct().map(attachmentLoader::inspect) }
            var error: String? = null
            val added = ArrayList<PendingAttachment>()
            results.forEach { r ->
                when (r) {
                    is LoadResult.Ok -> if (attachments.none { it.uri == r.attachment.uri } && added.none { it.uri == r.attachment.uri }) added += r.attachment
                    is LoadResult.TooLarge -> error = app.getString(R.string.attach_too_large, r.name, r.limitMb)
                    is LoadResult.Unreadable -> error = app.getString(R.string.attach_unreadable, r.name)
                }
            }
            attachments = attachments + added
            dockError = error
        }
    }

    fun removeChip(id: String) {
        attachments = attachments.filterNot { it.id == id }
    }

    val chips: List<AttachmentChip>
        get() = attachments.map { a ->
            AttachmentChip(a.id, a.name, a.isImage, if (a.isImage) ({ thumbnailOf(a.uri) }) else null)
        }

    private suspend fun thumbnailOf(uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
        val px = (THUMB_DP * app.resources.displayMetrics.density).toInt()
        decodeThumbnail({ app.contentResolver.openInputStream(uri) }, px)?.asImageBitmap()
    }

    /** The provider's display name and size; either may be missing. */
    private fun describeUri(uri: Uri): Pair<String?, Long?> = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val z = c.getColumnIndex(OpenableColumns.SIZE)
            (if (n >= 0 && !c.isNull(n)) c.getString(n) else null) to (if (z >= 0 && !c.isNull(z)) c.getLong(z) else null)
        } ?: (null to null)
    } catch (_: Exception) {
        null to null
    }

    fun stopReply() {
        val session = app.shellSessions.current.value ?: return
        val id = nav.openSessionId ?: return
        session.scope.launch { session.conversation(id).stop() }
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STATE = "dshm.shell"
        const val TAB = "tab"
        const val OPEN = "open"
        const val LIST = "list"
        const val TEXT = "text"
        const val MB = 1024 * 1024
        const val THUMB_DP = 64
    }
}
