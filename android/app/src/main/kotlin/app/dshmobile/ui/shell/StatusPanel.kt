package app.dshmobile.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.theme.Sakura

/**
 * dsh is starting or could not start: shown in the pages that need dsh (chat, agents) instead of their content, so the rest of
 * the app (overview, settings, the dock) stays usable. Nothing in [MainUiState.Ready].
 */
@Composable
fun StatusPanel(state: MainUiState, onRetry: () -> Unit, onExportErrorLog: () -> Unit) {
    if (state == MainUiState.Ready) return
    FogPanel(
        title = stringResource(R.string.shell_status_title),
        en = "STATUS",
        // One node for the texts (read out together); the buttons stay their own nodes.
        modifier = Modifier.testTag(Tags.STATUS_PANEL).semantics(mergeDescendants = true) {},
    ) {
        when (state) {
            MainUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Sakura.Primary, trackColor = Sakura.RoseBrown.copy(alpha = .14f), strokeWidth = 3.dp)
                Text(stringResource(R.string.main_loading), color = Sakura.Text, fontSize = 15.sp)
            }
            MainUiState.Error -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.main_error_title), color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)
                Text(stringResource(R.string.main_error_body), color = Sakura.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                FogButton(stringResource(R.string.main_retry), FogButtonKind.PINK, onRetry, Modifier.fillMaxWidth().testTag(Tags.RETRY))
                FogButton(stringResource(R.string.main_export_log), FogButtonKind.GHOST, onExportErrorLog, Modifier.fillMaxWidth().testTag(Tags.EXPORT_LOG))
            }
            MainUiState.Ready -> Unit
        }
    }
}
