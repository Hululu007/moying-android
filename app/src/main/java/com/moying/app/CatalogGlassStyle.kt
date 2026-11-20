package com.moying.app

/** 只选择 Catalog 材质配方；不持有页面、播放状态或动画对象。 */
enum class CatalogGlassStyle {
    DEFAULT, BOTTOM_TABS, SCROLL, SLIDER_TRACK, SLIDER_THUMB, PLAYGROUND,
    OSHIN_CONTROL, OSHIN_PANEL, OSHIN_POSTER,
}

internal val CatalogGlassStyle.isTunable: Boolean
    get() = when (this) {
        CatalogGlassStyle.BOTTOM_TABS, CatalogGlassStyle.SCROLL,
        CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_PANEL, CatalogGlassStyle.OSHIN_POSTER -> true
        else -> false
    }

// Preserve OShin's resting radius at the global default and keep the upper end
// within the padded sample layer when stronger blur is selected.
private fun adjustableBlur(value: Float, base: Float, maximum: Float): Float {
    val default = GlassTuning.Default.blur
    return if (value <= default) base * (value / default)
    else base + (maximum - base) * ((value - default) / (1f - default))
}

/** 输入：材质和外部按压进度；输出：dp 模糊半径，零值直接跳过 GPU 模糊。 */
internal fun catalogBlurRadius(
    style: CatalogGlassStyle,
    progress: Float,
    tuning: GlassTuning = GlassTuning.Default,
    panelExpansion: Float = 1f,
): Float = when (style) {
    CatalogGlassStyle.SLIDER_TRACK, CatalogGlassStyle.PLAYGROUND -> Theme.Space.None.value
    CatalogGlassStyle.BOTTOM_TABS, CatalogGlassStyle.SCROLL -> Theme.GlassTuning.MaxBlur.value * tuning.blur
    CatalogGlassStyle.OSHIN_CONTROL -> adjustableBlur(tuning.blur, 2f, 14f)
    CatalogGlassStyle.OSHIN_PANEL -> {
        val control = adjustableBlur(tuning.blur, 2f, 14f)
        val panel = adjustableBlur(tuning.blur, if (Theme.isDark) 8f else 16f, 24f)
        control + (panel - control) * panelExpansion
    }
    CatalogGlassStyle.OSHIN_POSTER -> adjustableBlur(tuning.blur, .75f, 3f)
    CatalogGlassStyle.SLIDER_THUMB -> Theme.CatalogGlass.SliderBlur.value * (1f - progress.coerceIn(0f, 1f))
    else -> Theme.NavGlass.BlurRadius.value
}
