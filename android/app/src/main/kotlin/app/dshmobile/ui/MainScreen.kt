package app.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dshmobile.R

enum class MainUiState { Loading, Error, Ready }

/**
 * Drawn over the WebView: a full-screen loading or error screen until dsh's page is up, and always the small settings button
 * in the top-right corner. In [MainUiState.Ready] it draws nothing else, so touches reach the page below.
 */
@Composable
fun MainOverlay(
    state: MainUiState,
    onRetry: () -> Unit,
    onExportErrorLog: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (state) {
            MainUiState.Loading -> FullScreen {
                CircularProgressIndicator()
                Text(stringResource(R.string.main_loading), style = MaterialTheme.typography.bodyLarge)
            }
            MainUiState.Error -> FullScreen {
                Text(stringResource(R.string.main_error_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.main_error_body), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.main_retry)) }
                OutlinedButton(onClick = onExportErrorLog) { Text(stringResource(R.string.main_export_log)) }
            }
            MainUiState.Ready -> Unit
        }
        val description = stringResource(R.string.main_settings_description)
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(32.dp)
                .semantics { contentDescription = description }
                .clickable(onClick = onOpenSettings),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.main_settings_button)) }
        }
    }
}

@Composable
private fun FullScreen(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {}, // swallow touches: the page below is not usable yet
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
