package app.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.Sakura

/**
 * One open-source part the app ships or learned from: [what] is the one Chinese line saying what it covers; [project] and
 * [copyrights] are its legal notice as written (Latin names are content here, accepted on this page, arch T09); [asset] is the
 * full licence text in `assets/licenses/`.
 */
internal class Notice(val id: String, val what: Int, val project: String, val licence: String, val copyrights: List<String>, val asset: String)

/** What the 授權 page lists (arch T09: the dsh, PRoot, talloc, Termux, Node, font and start-up animation notices left with them). */
internal object OpenSourceLicences {
    const val APACHE = "licenses/apache-2.0.txt"
    const val SQLCIPHER = "licenses/sqlcipher-BSD.txt"

    val notices = listOf(
        Notice("sqlcipher", R.string.ag_lic_sqlcipher, "SQLCipher for Android", "BSD-style License", listOf("Copyright (c) Zetetic LLC"), SQLCIPHER),
        Notice(
            "androidx", R.string.ag_lic_androidx, "AndroidX (Room, SQLite, Compose, Activity, Core, WorkManager)", "Apache License 2.0",
            listOf("Copyright The Android Open Source Project"), APACHE,
        ),
        Notice("okhttp", R.string.ag_lic_okhttp, "OkHttp, Okio", "Apache License 2.0", listOf("Copyright Square, Inc."), APACHE),
        Notice(
            "kotlin", R.string.ag_lic_kotlin, "Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0",
            listOf("Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"), APACHE,
        ),
        Notice(
            "kai", R.string.ag_lic_kai, "Kai 9000 (https://github.com/SimonSchubert/Kai)", "Apache License 2.0",
            listOf("Copyright (c) Simon Schubert"), APACHE,
        ),
    )
}

object LicensesTags {
    const val PAGE = "licenses-page"
    const val CLOSE = "licenses-close"
    const val SHOW = "licenses-show-"
    const val TEXT = "licenses-text-"
    const val NOTICE = "licenses-notice-"
}

/** 設定 › 授權, full screen; 關閉 closes it. */
@Composable
internal fun LicensesPage(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Sakura.Background).windowInsetsPadding(WindowInsets.safeDrawing).testTag(LicensesTags.PAGE)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ag_lic_title), style = MaterialTheme.typography.titleLarge, color = Sakura.Text, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.testTag(LicensesTags.CLOSE)) { Text(stringResource(R.string.ag_lic_close)) }
            }
            Text(stringResource(R.string.ag_lic_intro), style = MaterialTheme.typography.bodyMedium, color = Sakura.TextSecondary)
            OpenSourceLicences.notices.forEach { NoticeBlock(it) }
        }
    }
}

@Composable
private fun NoticeBlock(n: Notice) {
    HorizontalDivider(color = Sakura.MilkTea, modifier = Modifier.padding(top = 6.dp))
    Column(Modifier.fillMaxWidth().testTag(LicensesTags.NOTICE + n.id), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(n.what), style = MaterialTheme.typography.bodyMedium, color = Sakura.Text)
        Text(n.project, style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(n.licence, style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        n.copyrights.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary) }
        FullText(n.id, n.asset)
    }
}

/** 看授權全文 shows (and hides) the licence text in [asset], in a fixed-width face as the file is written. */
@Composable
private fun FullText(id: String, asset: String) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val context = LocalContext.current
    val text = remember(asset, open) {
        if (!open) "" else runCatching { context.assets.open(asset).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrDefault("")
    }
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag(LicensesTags.SHOW + id)) {
        Text(stringResource(if (open) R.string.ag_lic_hide else R.string.ag_lic_show))
    }
    if (open) {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Sakura.Text,
            modifier = Modifier.fillMaxWidth().background(Sakura.White.copy(alpha = .55f)).padding(10.dp).testTag(LicensesTags.TEXT + id),
        )
    }
}
