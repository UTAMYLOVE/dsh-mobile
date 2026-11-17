package app.dshmobile.ui.theme.themes

import app.dshmobile.ui.theme.ColorMode
import app.dshmobile.ui.theme.MaterialSkin
import app.dshmobile.ui.theme.SakuraColors
import app.dshmobile.ui.theme.SakuraShapes
import app.dshmobile.ui.theme.Theme
import app.dshmobile.ui.theme.ThemeColors
import app.dshmobile.ui.theme.ThemeId
import app.dshmobile.ui.theme.ThemeType

/**
 * 原本的樣子: today's app, pixel for pixel. No frames, controls, backdrop or bars, so every call site runs its current code
 * (E1); the colours, type and Material skin are today's values.
 */
internal fun classicTheme(): Theme = Theme(
    ThemeId.CLASSIC, ColorMode.SIX, ThemeColors.Classic, ThemeType.Classic,
    frames = null, material = MaterialSkin(SakuraColors, SakuraShapes),
)
