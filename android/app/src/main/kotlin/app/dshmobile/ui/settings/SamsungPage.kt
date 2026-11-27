package app.dshmobile.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.kit.SoftButton
import app.dshmobile.ui.kit.Sakura

/** The scheme of an app's own page in the system settings (not shown on screen). */
private const val PACKAGE_SCHEME = "package"

object SamsungTags {
    const val STEP = "ag-samsung-step-"
    const val OPEN = "ag-samsung-open"
}

/**
 * 設定 › 三星手機：提醒不被關掉 (owner 2026-11-27): the steps on a Samsung phone so the system never puts the app to sleep (battery
 * 「不受限制」, out of the sleeping lists), and a button to the app's own page in the system settings.
 */
@Composable
fun SamsungPage() {
    val context = LocalContext.current
    Section(stringResource(R.string.ag_set_samsung)) {
        Note(stringResource(R.string.ag_samsung_intro))
        stringArrayResource(R.array.ag_samsung_steps).forEachIndexed { i, step ->
            Text("${i + 1}. $step", color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(SamsungTags.STEP + (i + 1)))
        }
        SoftButton(stringResource(R.string.ag_samsung_open), onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts(PACKAGE_SCHEME, context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, modifier = Modifier.testTag(SamsungTags.OPEN))
    }
}
