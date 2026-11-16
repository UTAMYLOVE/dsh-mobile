package app.dshmobile.ui.theme

import app.dshmobile.ui.fog.Look

/**
 * The theme the app wears: 主題, or with 跟著開場 the theme of the chosen 開場樣式. It follows the style even while 打開時播放 is
 * off: the style is still chosen and shown selected, and the row reads 「跟著開場（櫻紋陣）」, so what it says is what it does.
 */
val Look.themeId: ThemeId get() = theme ?: ThemeId.of(intro.style)

/** The colours: the four community themes follow 顏色; every other theme has one set (its own, which is the six). */
val Look.colorMode: ColorMode get() = if (themeId.ownColours) themeColors else ColorMode.SIX

/** The war room: 指揮室樣式, or with 跟著主題 the worn theme's own (R3). */
val Look.warRoom: WarStyle get() = warStyle ?: themeId.warStyle
