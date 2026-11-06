package app.dshmobile.ui.shell

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura

/** What another app shared into DSH (Android's share sheet): text (a URL counts), images or files. */
data class SharedContent(val text: String?, val subject: String?, val uris: List<Uri>) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()

    /** The message the conversation gets: the owner's question (if any), then what was shared. */
    fun message(question: String): String = listOfNotNull(
        question.trim().takeIf { it.isNotEmpty() },
        listOfNotNull(subject?.trim()?.takeIf { it.isNotEmpty() && text?.contains(it) != true }, text?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString("\n").takeIf { it.isNotEmpty() }?.let { if (question.isBlank()) it else "「$it」" },
    ).joinToString("\n\n")

    companion object {
        const val MAX_FILES = 10

        fun from(intent: Intent?): SharedContent? {
            intent ?: return null
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(stream(intent))
                Intent.ACTION_SEND_MULTIPLE -> streams(intent)
                else -> return null
            }
            val c = SharedContent(
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(20_000),
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(200),
                uris.filter { it.scheme == "content" }.take(MAX_FILES), // never file:// paths another app points at
            )
            return c.takeUnless { it.isEmpty }
        }

        @Suppress("DEPRECATION")
        private fun stream(i: Intent): Uri? =
            if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streams(i: Intent): List<Uri> =
            (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
    }
}

object ShareTags {
    const val SHEET = "share-sheet"
    const val QUESTION = "share-question"
    const val ASK = "share-ask"
    const val ADD = "share-add"
    const val SHELF = "share-shelf"
}

/** Mockup 9 (bottom half): 開新對話問・加到現有對話・存到書架. */
@Composable
fun ShareSheet(
    content: SharedContent,
    onAsk: (question: String) -> Unit,
    onAddToExisting: (question: String) -> Unit,
    onSaveToShelf: () -> Unit,
    onDismiss: () -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(12.dp).clip(RoundedCornerShape(22.dp)).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag(ShareTags.SHEET).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Sakura.Background), contentAlignment = Alignment.Center) {
                    Text("DSH", color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FogFonts.ChakraPetch)
                }
                Column(Modifier.weight(1f)) {
                    val what = when {
                        content.uris.isNotEmpty() && !content.text.isNullOrBlank() -> stringResource(R.string.share_title_both, content.uris.size)
                        content.uris.isNotEmpty() -> stringResource(R.string.share_title_files, content.uris.size)
                        else -> stringResource(R.string.share_title_text)
                    }
                    Text(what, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    content.text?.takeIf { it.isNotBlank() }?.let {
                        Text("「${it.replace(Regex("\\s+"), " ").trim()}」", color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            OutlinedTextField(
                question, { question = it }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag(ShareTags.QUESTION),
                placeholder = { Text(stringResource(R.string.share_question_hint), fontSize = 13.sp) },
            )
            Option(stringResource(R.string.share_ask), stringResource(R.string.share_ask_note), ShareTags.ASK) { onAsk(question) }
            Option(stringResource(R.string.share_add), stringResource(R.string.share_add_note), ShareTags.ADD) { onAddToExisting(question) }
            Option(stringResource(R.string.share_shelf), stringResource(R.string.share_shelf_note), ShareTags.SHELF, onSaveToShelf)
        }
    }
}

@Composable
private fun Option(title: String, note: String, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).border(1.dp, Sakura.Primary.copy(alpha = .5f), shape)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(note, color = Sakura.TextSecondary, fontSize = 11.sp)
        }
    }
}

/** Writes shared content into the workspace's `shared/` folder; returns (workspace-relative path, title) per saved file. */
class SharedSaver(private val workspace: java.io.File, private val resolver: android.content.ContentResolver?, private val clock: () -> Long = System::currentTimeMillis) {
    fun save(content: SharedContent): List<Pair<String, String>> {
        val dir = java.io.File(workspace, DIR).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei") }.format(java.util.Date(clock()))
        val out = ArrayList<Pair<String, String>>()
        content.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val head = (content.subject ?: text).replace(Regex("\\s+"), " ").trim().take(16)
            val f = unique(dir, "$stamp-${safe(head).ifEmpty { "分享" }}.md")
            runCatching {
                f.writeText(listOfNotNull(content.subject?.let { "# $it" }, text).joinToString("\n\n") + "\n")
                out += "$DIR/${f.name}" to head.ifEmpty { f.name }
            }
        }
        for (u in content.uris) {
            runCatching {
                val r = resolver ?: return@runCatching
                val name = r.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "分享-$stamp"
                val f = unique(dir, safe(name).ifEmpty { "分享-$stamp" })
                r.openInputStream(u)?.use { input -> f.outputStream().use { o -> input.copyTo(o, LIMIT) } } ?: error("unreadable")
                if (f.length() > LIMIT_BYTES) { f.delete(); error("too large") }
                out += "$DIR/${f.name}" to f.name
            }
        }
        return out
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimStart('.').take(60)

    private fun unique(dir: java.io.File, name: String): java.io.File {
        var f = java.io.File(dir, name)
        var n = 2
        while (f.exists()) {
            val base = name.substringBeforeLast('.')
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            f = java.io.File(dir, "$base-$n$ext"); n++
        }
        return f
    }

    companion object {
        const val DIR = "shared"
        private const val LIMIT = 64 * 1024
        const val LIMIT_BYTES = 50L * 1024 * 1024
    }
}
