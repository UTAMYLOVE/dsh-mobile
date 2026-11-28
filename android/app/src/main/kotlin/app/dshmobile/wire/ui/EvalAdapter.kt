package app.dshmobile.wire.ui

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.agency.core.agent.CompanyAdmin
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.extras.EvalDim
import app.agency.core.extras.EvalResult
import app.agency.core.extras.MonthlyInput
import app.agency.core.store.Body
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.dshmobile.R
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.SystemCamera
import app.dshmobile.ui.EvalItem
import app.dshmobile.ui.EvalUi
import app.dshmobile.ui.EvalView
import app.dshmobile.ui.MediaUi
import app.dshmobile.wire.ActivityHost
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The weekly and monthly evaluation. What he hands in is kept for the period in the small preferences [PREFS] (a song take, a
 * dance video, the monthly extra item, the body photo; videos are sealed in the vault, never kept in the clear), and 「確認」
 * hands everything to the company, which computes the scores itself ([CompanyAdmin.submitWeeklyEval] / `submitMonthlyEval`).
 * The scores he picks on the page are his own rating and go to the company as his self-report. A refusal (not a Sunday, already
 * handed in…) is shown in the page title once.
 *
 * Limits of this version: the monthly fitness numbers (push-ups, plank seconds) have no field on the page and go in as 0; the
 * dance and the monthly extra item are recorded with the system's video camera.
 */
class EvalAdapter(
    private val context: Context,
    private val host: ActivityHost,
    private val admin: CompanyAdmin,
    private val store: Store,
    private val media: MediaUi,
    private val singing: SingingAdapter,
    private val vault: () -> MediaVault,
    private val clock: () -> Clock,
) : EvalUi {
    private val prefs get() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var refusal: String? = null

    private fun today(): LocalDate = Days.today(clock())

    private fun period(monthly: Boolean): String =
        if (monthly) YearMonth.from(today()).toString() else today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    private fun periodStart(monthly: Boolean): LocalDate =
        if (monthly) YearMonth.from(today()).atDay(1) else today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun handed(monthly: Boolean, key: String): String? {
        val v = prefs.getString("${if (monthly) "m" else "w"}.$key", null) ?: return null
        val (p, ref) = v.split('|', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
        return ref.takeIf { p == period(monthly) && it.isNotBlank() }
    }

    private fun hand(monthly: Boolean, key: String, ref: String) {
        prefs.edit().putString("${if (monthly) "m" else "w"}.$key", "${period(monthly)}|$ref").commit()
    }

    /** The song of this period: the one he picked, else the newest take since the period began. */
    private fun songId(monthly: Boolean): Long? =
        handed(monthly, "song")?.toLongOrNull()
            ?: admin.songs().filter { !it.day.isBefore(periodStart(monthly)) }.maxByOrNull { it.entryId }?.entryId

    override fun view(monthly: Boolean): EvalView {
        val v = admin.evalView()
        val rec = if (monthly) v.monthly else v.weekly
        fun score(dim: EvalDim) = rec?.scores?.firstOrNull { it.dim == dim }
        val songRec = songId(monthly)?.let { id -> admin.songs().firstOrNull { it.entryId == id }?.rec }
        val items = ArrayList<EvalItem>()
        items += EvalItem(
            "song", context.getString(R.string.eval_item_song), songId(monthly) != null,
            score(EvalDim.VOCAL)?.value,
            score(EvalDim.VOCAL)?.reason ?: songRec?.let { context.getString(R.string.eval_song_reason, it.inTunePct) }.orEmpty(),
        )
        items += EvalItem("dance", context.getString(R.string.eval_item_dance), handed(monthly, "dance") != null, score(EvalDim.DANCE)?.value, score(EvalDim.DANCE)?.reason.orEmpty())
        if (monthly) {
            items += EvalItem("rotating", v.rotatingItem, handed(true, "rotating") != null, null, "")
            items += EvalItem("body", context.getString(R.string.eval_item_body), handed(true, "body") != null, null, "")
        }
        val shown = refusal
        refusal = null
        val title = context.getString(if (monthly) R.string.eval_title_month else R.string.eval_title_week)
        return EvalView(if (shown != null) "$title（$shown）" else title, items, confirmed = rec != null)
    }

    override suspend fun record(monthly: Boolean, key: String): Boolean = when (key) {
        "song" -> {
            if (songId(monthly) != null) true
            else singing.recordFor(SONG_SECONDS)?.also { hand(monthly, "song", it.entryId.toString()) } != null
        }
        "dance", "rotating" -> captureVideo()?.also { hand(monthly, key, it) } != null
        "body" -> {
            val photo = media.takePhoto(context.getString(R.string.eval_item_body))
            if (photo == null) false else {
                withContext(Dispatchers.IO) { admin.bodyPhoto(photo.photoId) }
                hand(monthly, "body", photo.photoId)
                true
            }
        }
        else -> false
    }

    /** The system video camera into a cache file, sealed in the vault; the id, or null when he backed out. */
    private suspend fun captureVideo(): String? {
        if (!host.askCamera()) return null
        val dir = SystemCamera.cameraDir(context).also { it.mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".mp4")
        val ok = withContext(Dispatchers.IO) { runCatching { file.createNewFile() }.getOrDefault(false) }
        if (!ok) return null
        val uri = FileProvider.getUriForFile(context, context.packageName + ".export", file)
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = host.launch(ActivityResultContracts.StartActivityForResult(), intent)
        return withContext(Dispatchers.IO) {
            if (result?.resultCode != android.app.Activity.RESULT_OK || !file.isFile || file.length() == 0L) {
                file.delete()
                null
            } else {
                val id = UUID.randomUUID().toString()
                runCatching { vault().putFile(id, file); id }.getOrNull()
            }
        }
    }

    override fun confirm(monthly: Boolean, scores: Map<String, Int>) {
        val song = songId(monthly)
        val dance = handed(monthly, "dance")
        val self = scores.entries.joinToString("、") { (k, s) -> "${label(k)} $s 分" }.let { if (it.isBlank()) "" else context.getString(R.string.eval_self, it) }
        val result = kotlinx.coroutines.runBlocking {
            if (song == null || dance == null) {
                EvalResult.Refused(context.getString(R.string.eval_missing))
            } else if (!monthly) {
                admin.submitWeeklyEval(song, dance, self)
            } else {
                val day = today()
                val bodies = store.entries(day.minusDays(120), day, Kind.BODY).mapNotNull { runCatching { it.read<Body>() }.getOrNull() }
                admin.submitMonthlyEval(
                    MonthlyInput(
                        song, dance, handed(true, "rotating").orEmpty(), handed(true, "body").orEmpty(),
                        bodies.mapNotNull { it.kg }.lastOrNull() ?: 0.0, bodies.mapNotNull { it.waistCm }.lastOrNull() ?: 0.0, 0, 0, self,
                    ),
                )
            }
        }
        refusal = (result as? EvalResult.Refused)?.reason
    }

    private fun label(key: String): String = when (key) {
        "song" -> context.getString(R.string.eval_item_song)
        "dance" -> context.getString(R.string.eval_item_dance)
        "body" -> context.getString(R.string.eval_item_body)
        else -> admin.evalView().rotatingItem
    }

    companion object {
        const val PREFS = "xiaoyu_eval"
        const val SONG_SECONDS = 20
    }
}
