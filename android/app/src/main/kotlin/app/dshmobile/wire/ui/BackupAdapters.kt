package app.dshmobile.wire.ui

import android.content.Context
import android.net.Uri
import app.agency.core.backup.BackupException
import app.agency.core.base.Clock
import app.dshmobile.R
import app.dshmobile.platform.Backup
import app.dshmobile.platform.OldData
import app.dshmobile.platform.OldLine
import app.dshmobile.platform.OldWho
import app.dshmobile.secure.DataUnavailableException
import app.dshmobile.ui.BackupUi
import app.dshmobile.ui.OldDataUi
import app.dshmobile.ui.OldMessage
import app.dshmobile.wire.ActivityHost
import java.io.IOException
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypted backup export and import through the system's file pickers. The passphrase is only ever held as a char array for the
 * length of one call (the one kept between 預覽 and 還原 is wiped when the restore is done or a new preview begins).
 */
class BackupAdapter(
    private val context: Context,
    private val host: ActivityHost,
    private val backup: () -> Backup,
    private val clock: () -> Clock,
    private val onRestored: () -> Unit,
) : BackupUi {
    private var chosen: Uri? = null
    private var chosenPass: CharArray? = null

    override suspend fun export(passphrase: String): String {
        val now = clock().now()
        val uri = host.launch(Backup.createContract(), Backup.suggestedName(now)) ?: return context.getString(R.string.wire_backup_cancelled)
        return withContext(Dispatchers.IO) {
            val pass = passphrase.toCharArray()
            try {
                val s = context.contentResolver.openOutputStream(uri)?.use { backup().export(it, pass, now) }
                    ?: return@withContext context.getString(R.string.backup_failed)
                context.getString(R.string.wire_backup_done, s.rows.values.sum(), s.media)
            } catch (e: BackupException) {
                Backup.reasonText(context, e)
            } catch (_: IOException) {
                context.getString(R.string.backup_failed)
            } catch (_: DataUnavailableException) {
                context.getString(R.string.backup_failed)
            } finally {
                pass.fill('\u0000')
            }
        }
    }

    override suspend fun preview(passphrase: String): String? {
        val uri = host.launch(Backup.openContract(), arrayOf("*/*")) ?: return null
        return withContext(Dispatchers.IO) {
            val pass = passphrase.toCharArray()
            try {
                val s = context.contentResolver.openInputStream(uri)?.use { backup().preview(it, pass) } ?: return@withContext null
                chosenPass?.fill('\u0000')
                chosen = uri
                chosenPass = passphrase.toCharArray()
                context.getString(R.string.wire_backup_preview, s.rows.values.sum(), s.media, s.firstDay.orEmpty(), s.lastDay.orEmpty())
            } catch (_: BackupException) {
                null
            } catch (_: IOException) {
                null
            } finally {
                pass.fill('\u0000')
            }
        }
    }

    override suspend fun apply(): String = withContext(Dispatchers.IO) {
        val uri = chosen
        val pass = chosenPass
        chosen = null
        chosenPass = null
        if (uri == null || pass == null) return@withContext context.getString(R.string.wire_backup_nothing)
        try {
            context.contentResolver.openInputStream(uri)?.use { backup().restore(it, pass) }
                ?: return@withContext context.getString(R.string.backup_failed)
            onRestored()
            context.getString(R.string.wire_backup_restored)
        } catch (e: BackupException) {
            Backup.reasonText(context, e)
        } catch (_: IOException) {
            context.getString(R.string.backup_failed)
        } catch (_: DataUnavailableException) {
            context.getString(R.string.backup_failed)
        } finally {
            pass.fill('\u0000')
        }
    }
}

/** 「舊版資料」: the 0.9 conversation, read-only, through [OldData]; delete removes the old database and old media. */
class OldDataAdapter(private val old: () -> OldData, private val clock: () -> Clock) : OldDataUi {
    private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")

    override fun available(): Boolean = runCatching { old().exists() && old().count() > 0 }.getOrDefault(false)

    override fun messages(): List<OldMessage> {
        val zone = clock().zone
        val lines = ArrayList<OldLine>()
        var before: OldLine? = null
        runCatching {
            repeat(MAX_PAGES) {
                val page = old().page(PAGE, before)
                if (page.isEmpty()) return@repeat
                lines.addAll(0, page)
                before = page.first()
            }
        }
        return lines.map { OldMessage(who(it.who), it.text, it.at.atZone(zone).format(fmt)) }
    }

    private fun who(w: OldWho) = when (w) {
        OldWho.OWNER -> "你"
        OldWho.COMPANY -> "小葵"
        OldWho.SYSTEM -> "系統"
    }

    override fun delete(): Boolean = runCatching { old().deleteAll() }.getOrDefault(false)

    private companion object {
        const val PAGE = 500
        const val MAX_PAGES = 8
    }
}
