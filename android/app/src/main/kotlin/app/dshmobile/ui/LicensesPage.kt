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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dshmobile.R
import app.dshmobile.ui.kit.Sakura

/** One open-source project the app's startup looks learned from: shown with its author, link and MIT copyright line. */
internal class OpenSourceCredit(val project: String, val author: String, val url: String, val copyright: String, val usedFor: String)

/**
 * One dsh community startup look whose own code ships in the app (assets/intro-web/<[key]>/, with its LICENSE and NOTICE.md):
 * the project, its author, link, the commit it was taken from, its MIT copyright line and what was changed.
 */
internal class PortedLook(
    val key: String, val project: String, val author: String, val url: String, val commit: String, val copyright: String,
    val look: Int, val changes: Int,
) {
    val licence: String get() = "intro-web/$key/LICENSE"
}

/** One licence text bundled in assets/licenses/, with what it covers. */
internal class BundledLicence(val id: String, val what: Int, val copyrights: List<String>, val asset: String)

/**
 * What the 開放原始碼授權 page lists. The fonts and the library ship in the app, so their full licence texts do too; the DSH
 * plugins are credited because the startup looks follow their ideas (OFL and MIT both ask for the notice to travel with the app).
 */
internal object OpenSourceLicences {
    val introFonts = listOf(
        BundledLicence(
            "intro-hand", R.string.licenses_hand,
            listOf("Copyright (c) 2022, Wang, Li-Yu and Liu, Wei-Chen, with Reserved Font Name \"辰宇落雁\" and \"Chenyuluoyan\"."),
            "licenses/intro-hand-OFL.txt",
        ),
        BundledLicence(
            "intro-latin", R.string.licenses_latin,
            listOf("Copyright 2011 The Quicksand Project Authors (https://github.com/andrew-paglinawan/QuicksandFamily), with Reserved Font Name “Quicksand”."),
            "licenses/intro-latin-OFL.txt",
        ),
    )
    val otherFonts = listOf(
        BundledLicence(
            "fonts", R.string.licenses_orbitron,
            listOf(
                "Copyright 2018 The Orbitron Project Authors (https://github.com/theleagueof/orbitron), with Reserved Font Name: \"Orbitron\"",
                "Copyright 2018 The Chakra Petch Project Authors (https://github.com/m4rc1e/Chakra-Petch.git)",
            ),
            "licenses/fonts-OFL.txt",
        ),
    )
    val libraries = listOf(
        BundledLicence("commonmark", R.string.licenses_commonmark, listOf("Copyright (c) 2015, Robin Stocker"), "licenses/commonmark-BSD-2.txt"),
    )
    val startupPlugins = listOf(
        OpenSourceCredit("dsh-startup-animation", "KylinQ01", "https://github.com/KylinQ01/dsh-startup-animation", "Copyright (c) 2026 KylinQ01", "星屑迎接、季節的小彩蛋"),
        OpenSourceCredit("opening-free", "bigfatorangecatjohn", "https://github.com/bigfatorangecatjohn/opening-free-0.21.0", "Copyright (c) 2026 bigfatorangecatjohn", "花吹雪聚形"),
        OpenSourceCredit("dsh-boot-particles", "hbgdjb", "https://github.com/hbgdjb/dsh-boot-particles", "Copyright (c) 2026 hbgdjb", "花吹雪聚形（花瓣飛到畫面邊線）"),
        OpenSourceCredit("dsh-link-start", "FHun", "https://github.com/Rearpoem/dsh-link-start", "Copyright (c) 2026 FHun", "櫻花隧道；手機卡住時動畫不跳拍"),
        OpenSourceCredit("dsh-boot-compass", "linshufan21", "https://github.com/linshufan21/dsh-boot-compass", "Copyright (c) 2026 linshufan21", "櫻紋陣：花陣回彈、刻度一格一格亮"),
        OpenSourceCredit("dsh-startup-screen", "沈宏宏", "https://github.com/6shenhonghong9/dsh-startup-screen", "Copyright (c) 2026 沈宏宏", "櫻紋陣：英文副標逐字打出、粉藍錯位閃光"),
        OpenSourceCredit("dsh-550c-boot", "Ziyang Song", "https://github.com/yannicksong0106/dsh-550c-boot", "Copyright (c) 2026 Ziyang Song", "一筆描"),
        OpenSourceCredit("dsh-apple-liquid-glass", "CTY", "https://github.com/tianyuec11-design/dsh-apple-liquid-glass", "Copyright (c) 2026 CTY", "一筆描（名字一筆一筆寫出來）"),
    )
    const val MIT_ASSET = "licenses/startup-plugins-MIT.txt"

    /** The community looks played as they are (in the picker as 「…（dsh 社群作品）」); each NOTICE.md lists every change. */
    val portedLooks = listOf(
        PortedLook(
            "sakura", "dsh-startup-animation", "KylinQ01", "https://github.com/KylinQ01/dsh-startup-animation",
            "3a8c57a8fe2113b81ff861e9fc4589ad9bae6460", "Copyright (c) 2026 KylinQ01",
            R.string.look_intro_style_web_sakura, R.string.licenses_ported_sakura_changes,
        ),
        PortedLook(
            "neon", "dsh-neon-splash-v3", "Z1ro2", "https://github.com/wsy304450907-boop/Z1ro2",
            "397742bb37c976d6f4531689f2854f8cc6e2392b", "Copyright (c) 2026 Z1ro2",
            R.string.look_intro_style_web_neon, R.string.licenses_ported_neon_changes,
        ),
        PortedLook(
            "wave", "dsh-opening-free", "bigfatorangecatjohn", "https://github.com/bigfatorangecatjohn/opening-free-0.21.0",
            "b75f3e3e5346b48156c2193ca1c11ec3a78a60af", "Copyright (c) 2026 bigfatorangecatjohn",
            R.string.look_intro_style_web_wave, R.string.licenses_ported_wave_changes,
        ),
        PortedLook(
            "access", "dsh-startup-screen", "沈宏宏", "https://github.com/6shenhonghong9/dsh-startup-screen",
            "7f3d2837c1aaef8293abc69093b0a35c80d0f0e8", "Copyright (c) 2026 沈宏宏",
            R.string.look_intro_style_web_access, R.string.licenses_ported_access_changes,
        ),
    )
}

object LicensesTags {
    const val PAGE = "licenses-page"
    const val CLOSE = "licenses-close"
    const val SHOW = "licenses-show-"
    const val TEXT = "licenses-text-"
    const val OPEN = "open-licenses"
    const val PORTED = "licenses-ported-"
}

/** The 開放原始碼授權 page over the settings, full screen; back or 關閉 closes it. */
@Composable
internal fun LicensesDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LicensesPage(onClose)
    }
}

@Composable
internal fun LicensesPage(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Sakura.Background).windowInsetsPadding(WindowInsets.safeDrawing).testTag(LicensesTags.PAGE)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.licenses_title), style = MaterialTheme.typography.titleLarge, color = Sakura.Text, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.testTag(LicensesTags.CLOSE)) { Text(stringResource(R.string.licenses_close)) }
            }
            Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium, color = Sakura.TextSecondary)

            Heading(R.string.licenses_fonts_intro)
            OpenSourceLicences.introFonts.forEach { Licence(it) }
            Heading(R.string.licenses_fonts_other)
            OpenSourceLicences.otherFonts.forEach { Licence(it) }
            Heading(R.string.licenses_libraries)
            OpenSourceLicences.libraries.forEach { Licence(it) }

            Heading(R.string.licenses_ideas)
            Text(stringResource(R.string.licenses_ideas_note), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
            OpenSourceLicences.startupPlugins.forEach { Credit(it) }
            FullText("mit", R.string.licenses_mit_text, OpenSourceLicences.MIT_ASSET)

            Heading(R.string.licenses_ported)
            Text(stringResource(R.string.licenses_ported_note), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
            OpenSourceLicences.portedLooks.forEach { Ported(it) }
        }
    }
}

@Composable
private fun Heading(text: Int) {
    HorizontalDivider(color = Sakura.MilkTea, modifier = Modifier.padding(top = 6.dp))
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = Sakura.Text)
}

@Composable
private fun Licence(l: BundledLicence) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(l.what), style = MaterialTheme.typography.bodyMedium, color = Sakura.Text)
        l.copyrights.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary) }
        FullText(l.id, null, l.asset)
    }
}

@Composable
private fun Credit(c: OpenSourceCredit) {
    Column(Modifier.fillMaxWidth().testTag("licenses-credit-" + c.project), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${c.project} · ${c.author}", style = MaterialTheme.typography.bodyMedium, color = Sakura.Text)
        Text(c.url, style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text("MIT License, ${c.copyright}", style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(stringResource(R.string.licenses_used_for, c.usedFor), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
    }
}

@Composable
private fun Ported(p: PortedLook) {
    Column(Modifier.fillMaxWidth().testTag(LicensesTags.PORTED + p.key), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(p.look), style = MaterialTheme.typography.bodyMedium, color = Sakura.Text)
        Text(stringResource(R.string.licenses_ported_by, p.project, p.author), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(stringResource(R.string.licenses_ported_link, p.url), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(stringResource(R.string.licenses_ported_commit, p.commit), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(stringResource(R.string.licenses_ported_licence, p.copyright), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        Text(stringResource(R.string.licenses_ported_changes, stringResource(p.changes)), style = MaterialTheme.typography.bodySmall, color = Sakura.TextSecondary)
        FullText("ported-" + p.key, null, p.licence)
    }
}

/** A 看授權全文 button that shows (and hides) the licence text in [asset], in a fixed-width face as the file is written. */
@Composable
private fun FullText(id: String, title: Int?, asset: String) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val context = LocalContext.current
    val text = remember(asset, open) {
        if (!open) "" else runCatching { context.assets.open(asset).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrDefault("")
    }
    if (title != null) Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = Sakura.Text)
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag(LicensesTags.SHOW + id)) {
        Text(stringResource(if (open) R.string.licenses_hide else R.string.licenses_show))
    }
    if (open) {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Sakura.Text,
            modifier = Modifier.fillMaxWidth().background(Sakura.White.copy(alpha = .55f)).padding(10.dp).testTag(LicensesTags.TEXT + id),
        )
    }
}
