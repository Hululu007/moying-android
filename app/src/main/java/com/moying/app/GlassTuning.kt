package com.moying.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
internal data class GlassTuning(
    val transparency: Float = .77f,
    val blur: Float = .24f,
    val refraction: Float = .70f,
    val highlight: Float = .50f,
    val saturation: Float = .50f,
) {
    val tintStrength: Float get() = .92f - .88f * transparency
    val translucentOpacity: Float get() = .14f + .8f * (1f - transparency)

    companion object {
        val Default = GlassTuning()
    }
}

internal enum class GlassArea(val label: String, val preferenceKey: String) {
    CONTINUE("继续观看", "continue"),
    HISTORY("历史", "history"),
    NAVIGATION("底栏", "navigation"),
    POSTER("海报", "poster"),
    PLAYER("播放/选集", "player"),
    PANELS("面板", "panels");

    fun inheritedTuning(global: GlassTuning): GlassTuning =
        if (this == POSTER) global.copy(blur = 0f) else global
}

// Draw reads the tuning state without recomposing the app on slider movement.
private val DefaultGlassTuningState: State<GlassTuning> = mutableStateOf(GlassTuning.Default)
internal val LocalGlassTuning = staticCompositionLocalOf { DefaultGlassTuningState }
