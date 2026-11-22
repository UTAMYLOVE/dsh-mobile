package app.dshmobile.ui.theme

import androidx.compose.runtime.Composable

// The Sakura palette and theme moved to ui/kit (T06). These names keep the old screens compiling until T08 deletes them.

typealias Sakura = app.dshmobile.ui.kit.Sakura

internal val SakuraColors = app.dshmobile.ui.kit.SakuraColors
internal val SakuraShapes = app.dshmobile.ui.kit.SakuraShapes

@Composable
fun SakuraTheme(content: @Composable () -> Unit) = app.dshmobile.ui.kit.SakuraTheme(content)
