package app.dshmobile.ui.home

import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyApi
import app.agency.core.agent.SayResult
import app.agency.core.agent.Today
import app.agency.core.plan.Screen
import app.agency.core.plan.Tap
import app.agency.core.store.Message
import app.dshmobile.ui.PanelPrefs
import app.dshmobile.ui.PhotoUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What he sent that the store does not show yet (or could not send). Ids are negative so they never meet a stored id. */
data class LocalMessage(val id: Long, val text: String, val attachments: List<Attachment>, val failed: Boolean)

/** Where a tap wants to go; the screen host turns it into navigation. */
fun interface Navigator {
    fun open(screen: Screen, arg: String)
}

/**
 * Home's state. A plain class: the owner of [scope] decides its life (the screen host uses a scope tied to the composition).
 * All store work runs on [io]; the state flows are read on the main thread.
 */
class HomeViewModel(
    private val api: CompanyApi,
    private val scope: CoroutineScope,
    private val panels: PanelPrefs? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pageSize: Int = 60,
    private val photos: PhotoUi? = null,
) {
    /** A quiet line for what just happened that has no message of its own (a photo that found no door, a card that expired). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Sending a meal to chat failed: the screen offers 「記這一餐」 with this slot and photo. */
    class MealFallback(val slot: String, val photo: Attachment.Photo?)

    private val _mealFallback = MutableStateFlow<MealFallback?>(null)
    val mealFallback: StateFlow<MealFallback?> = _mealFallback.asStateFlow()
    fun mealFallbackHandled() { _mealFallback.value = null }

    fun clearNotice() { _notice.value = null }

    private val _today = MutableStateFlow<Today?>(null)
    val today: StateFlow<Today?> = _today.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _local = MutableStateFlow<List<LocalMessage>>(emptyList())
    val local: StateFlow<List<LocalMessage>> = _local.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _stickersOpen = MutableStateFlow(panels?.get("stickers", true) ?: true)
    val stickersOpen: StateFlow<Boolean> = _stickersOpen.asStateFlow()
    private val _handbookOpen = MutableStateFlow(panels?.get("handbook", false) ?: false)
    val handbookOpen: StateFlow<Boolean> = _handbookOpen.asStateFlow()
    private val _chipsOpen = MutableStateFlow(panels?.get("chips", true) ?: true)
    val chipsOpen: StateFlow<Boolean> = _chipsOpen.asStateFlow()

    private var hasOlder = true
    private var loadingOlder = false
    private var localId = -1L
    private var refreshJob: Job? = null

    fun toggleStickers() = flip(_stickersOpen, "stickers")
    fun toggleHandbook() = flip(_handbookOpen, "handbook")
    fun toggleChips() = flip(_chipsOpen, "chips")

    private fun flip(flow: MutableStateFlow<Boolean>, name: String) {
        val v = !flow.value
        flow.value = v
        panels?.set(name, v)
    }

    /** Reloads today and the newest messages (on start, on resume, after every action). */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val (t, m) = withContext(io) {
                api.rollover()
                api.today() to api.messages(pageSize, null)
            }
            _today.value = t
            // keep older pages already loaded, replace the newest page
            val oldestNew = m.firstOrNull()?.id
            val older = if (oldestNew == null) emptyList() else _messages.value.filter { it.id < oldestNew }
            _messages.value = older + m
            if (m.size < pageSize && older.isEmpty()) hasOlder = false
        }
    }

    /** Called when the list is scrolled to its oldest message. */
    fun loadOlder() {
        val oldest = _messages.value.firstOrNull() ?: return
        if (!hasOlder || loadingOlder) return
        loadingOlder = true
        scope.launch {
            val page = withContext(io) { api.messages(pageSize, oldest.id) }
            if (page.size < pageSize) hasOlder = false
            _messages.update { page + it }
            loadingOlder = false
        }
    }

    fun addAttachments(list: List<Attachment>) {
        if (list.isNotEmpty()) _attachments.update { it + list }
    }

    fun removeAttachment(a: Attachment) = _attachments.update { it - a }

    /** Sends [text] (with the attached things). His bubble is shown at once. */
    fun send(text: String) {
        val clean = text.trim()
        val att = _attachments.value
        if (clean.isEmpty() && att.isEmpty()) return
        _attachments.value = emptyList()
        dispatch(clean, att)
    }

    /**
     * A photo from the camera: [arg] says what it is for (see [PhotoRouting]). [sentence] names the meal for chat. When sending to
     * chat fails the meal sheet is offered instead.
     */
    fun photoTaken(arg: String, photo: Attachment.Photo, noSpot: String, noDoor: String, sentence: (String) -> String) {
        scope.launch {
            when (val r = withContext(io) { PhotoRouting.decide(arg, photo, photos, noSpot, noDoor) }) {
                is PhotoRoute.Done -> { _notice.value = r.line; refresh() }
                is PhotoRoute.Said -> _notice.value = r.text
                is PhotoRoute.Chat -> {
                    _attachments.update { it + photo }
                    val att = _attachments.value
                    _attachments.value = emptyList()
                    dispatch(sentence(r.slot), att, mealSlot = r.slot.takeIf { it.isNotEmpty() }, mealPhoto = photo)
                }
            }
        }
    }

    private fun dispatch(
        text: String,
        att: List<Attachment>,
        reuseId: Long? = null,
        mealSlot: String? = null,
        mealPhoto: Attachment.Photo? = null,
    ) {
        val id = reuseId ?: localId--
        _local.update { list -> if (list.any { it.id == id }) list.map { if (it.id == id) it.copy(failed = false) else it } else list + LocalMessage(id, text, att, false) }
        _thinking.value = true
        scope.launch {
            val result = withContext(io) { api.say(text, att) }
            val (t, m) = withContext(io) { api.today() to api.messages(pageSize, null) }
            _today.value = t
            val oldestNew = m.firstOrNull()?.id
            _messages.value = (if (oldestNew == null) emptyList() else _messages.value.filter { it.id < oldestNew }) + m
            _thinking.value = false
            when (result) {
                SayResult.Ok -> _local.update { l -> l.filterNot { it.id == id } }
                is SayResult.NotSent -> {
                    if (mealSlot != null) _mealFallback.value = MealFallback(mealSlot, mealPhoto)
                    // the store keeps a failed copy when it can; only keep ours when it does not show one
                    val stored = m.any { it.meta["failed"]?.toString() == "true" && it.text == text }
                    _local.update { l -> if (stored) l.filterNot { it.id == id } else l.map { if (it.id == id) it.copy(failed = true) else it } }
                }
            }
        }
    }

    /** 「沒送出 · 重送」 on a stored failed message. */
    fun resend(messageId: Long) {
        _thinking.value = true
        scope.launch {
            withContext(io) { api.resend(messageId) }
            _thinking.value = false
            refresh()
        }
    }

    /** The same on a failed bubble that only exists here. */
    fun resendLocal(id: Long) {
        val m = _local.value.firstOrNull { it.id == id } ?: return
        dispatch(m.text, m.attachments, reuseId = id)
    }

    fun answerCard(messageId: Long, cardIndex: Int, answer: String) {
        scope.launch {
            val r = withContext(io) { api.answerCard(messageId, cardIndex, answer) }
            if (r is SayResult.NotSent) _notice.value = r.reason
            refresh()
        }
    }

    /** A tap on a plan item or on 「下一件事」. */
    fun onTap(tap: Tap, nav: Navigator) {
        when (tap) {
            is Tap.Say -> dispatch(tap.text, emptyList())
            is Tap.Open -> nav.open(tap.screen, tap.arg)
            is Tap.Direct -> scope.launch {
                withContext(io) { api.direct(tap.tool, tap.args) }
                refresh()
            }
        }
    }
}
