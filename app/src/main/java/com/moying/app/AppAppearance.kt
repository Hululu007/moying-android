package com.moying.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val GlassFollowsTiltKey = "glass-light-follows-tilt"
private const val GlassLightAngleKey = "glass-light-angle"
private const val DefaultGlassLightAngle = 45f
internal const val MaxGlassLightAngle = 359L

enum class AppThemeMode(val label: String) {
    DARK("深色"), LIGHT("浅色"), SYSTEM("跟随系统")
}

enum class AppBackgroundStyle(val label: String) {
    SOLID("纯色"), BLUE("雾蓝"), SAND("暖砂"), CUSTOM("自选图片"), BUILT_IN("内置壁纸")
}

enum class BuiltInWallpaper(val label: String, val asset: String) {
    BUNNY("云朵兔", "wallpapers/bunny.webp"),
    SILK("柔缎", "wallpapers/silk.webp"),
    MOON("月光", "wallpapers/moon.webp"),
    CITRUS("橘子汽水", "wallpapers/citrus.webp"),
    MINT("薄荷巡游", "wallpapers/mint.webp"),
    BERRY("莓果波普", "wallpapers/berry.webp"),
    ARCADE("霓虹电玩", "wallpapers/arcade.webp"),
    GOAT_AND_WOLF("喜羊羊与灰太狼", "wallpapers/goat-and-wolf.webp"),
    VALLEY_TRAIN("山谷列车", "wallpapers/valley-train.webp"),
}

enum class AppStylePack(val label: String, val wallpaper: BuiltInWallpaper?) {
    ORIGINAL("原版", null),
    TIME("时间主题", null),
    BUNNY("云朵兔", BuiltInWallpaper.BUNNY),
    SILK("柔缎", BuiltInWallpaper.SILK),
    MOON("月光", BuiltInWallpaper.MOON),
    CITRUS("橘子汽水", BuiltInWallpaper.CITRUS),
    MINT("薄荷巡游", BuiltInWallpaper.MINT),
    BERRY("莓果波普", BuiltInWallpaper.BERRY),
    ARCADE("霓虹电玩", BuiltInWallpaper.ARCADE),
    GOAT_AND_WOLF("喜羊羊与灰太狼", BuiltInWallpaper.GOAT_AND_WOLF),
    VALLEY_TRAIN("山谷列车", BuiltInWallpaper.VALLEY_TRAIN),
    ;

    val wallpaperAsset: String? get() = wallpaper?.asset
}

/** 输入：应用上下文；输出：播放缓存与预取的流量开关（设置页与缓存代理共用）。 */
internal object PlaybackNetworkPreferences {
    private const val FileName = "moying-playback"
    private const val WifiOnlyKey = "wifi-only-precache"
    private const val NextEpisodeKey = "next-episode-prefetch"

    /** 默认仅 Wi-Fi 预缓存，移动数据下只按播放进度缓存当前集。 */
    fun read(context: Context): Boolean =
        context.getSharedPreferences(FileName, Context.MODE_PRIVATE).getBoolean(WifiOnlyKey, true)

    fun write(context: Context, value: Boolean) {
        context.getSharedPreferences(FileName, Context.MODE_PRIVATE).edit().putBoolean(WifiOnlyKey, value).apply()
    }

    /** 下一集预取需要用户显式开启，默认关闭。 */
    fun readNextEpisode(context: Context): Boolean =
        context.getSharedPreferences(FileName, Context.MODE_PRIVATE).getBoolean(NextEpisodeKey, false)

    fun writeNextEpisode(context: Context, value: Boolean) {
        context.getSharedPreferences(FileName, Context.MODE_PRIVATE).edit().putBoolean(NextEpisodeKey, value).apply()
    }
}

/** 输入：应用上下文；输出：同一页面树共用、重启后恢复的外观状态。 */
@Composable
fun rememberAppAppearance(context: Context): AppAppearanceState =
    remember(context.applicationContext) { AppAppearanceState(context.applicationContext) }

@Stable
class AppAppearanceState internal constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("moying-appearance", Context.MODE_PRIVATE)
    private val tuning = mutableStateOf(readGlassTuning("glass"))
    private var selectedGlassFollowsTilt by mutableStateOf(preferences.getBoolean(GlassFollowsTiltKey, false))
    val glassFollowsTilt: Boolean get() = selectedGlassFollowsTilt
    private var selectedGlassLightAngle by mutableFloatStateOf(
        preferences.getFloat(GlassLightAngleKey, DefaultGlassLightAngle)
            .takeIf { it.isFinite() }?.coerceIn(0f, MaxGlassLightAngle.toFloat()) ?: DefaultGlassLightAngle,
    )
    val glassLightAngle: Float get() = selectedGlassLightAngle
    private val glassOverrides = GlassArea.entries.associateWith { area ->
        val prefix = glassPreferencePrefix(area)
        mutableStateOf(if (preferences.getBoolean("$prefix-custom", false)) readGlassTuning(prefix) else null)
    }
    private val effectiveGlassTuning = glassOverrides.mapValues { (area, override) ->
        derivedStateOf { override.value ?: area.inheritedTuning(tuning.value) }
    }
    internal val glassTuningState: State<GlassTuning> get() = tuning

    internal fun glassTuningState(area: GlassArea?): State<GlassTuning> =
        if (area == null) tuning else effectiveGlassTuning.getValue(area)

    internal fun hasGlassOverride(area: GlassArea): Boolean = glassOverrides.getValue(area).value != null

    private fun glassPreferencePrefix(area: GlassArea) = "glass-area-" + area.preferenceKey

    private fun readGlassLevel(key: String, default: Float): Float =
        preferences.getFloat(key, default).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default

    private fun readGlassTuning(prefix: String): GlassTuning = GlassTuning(
        transparency = readGlassLevel("$prefix-transparency", GlassTuning.Default.transparency),
        blur = readGlassLevel("$prefix-blur", GlassTuning.Default.blur),
        refraction = readGlassLevel("$prefix-refraction", GlassTuning.Default.refraction),
        highlight = readGlassLevel("$prefix-highlight", GlassTuning.Default.highlight),
        saturation = readGlassLevel("$prefix-saturation", GlassTuning.Default.saturation),
    )

    internal fun setGlassTuning(value: GlassTuning, area: GlassArea? = null) {
        val state = if (area == null) null else glassOverrides.getValue(area)
        if (area == null) {
            if (tuning.value == value) return
            tuning.value = value
        } else {
            if (state?.value == value) return
            state?.value = value
        }
        val prefix = if (area == null) "glass" else glassPreferencePrefix(area)
        val editor = preferences.edit()
        if (area != null) editor.putBoolean("$prefix-custom", true)
        editor.putFloat("$prefix-transparency", value.transparency)
            .putFloat("$prefix-blur", value.blur)
            .putFloat("$prefix-refraction", value.refraction)
            .putFloat("$prefix-highlight", value.highlight)
            .putFloat("$prefix-saturation", value.saturation)
            .apply()
    }

    internal fun followGlobalGlass(area: GlassArea) {
        val state = glassOverrides.getValue(area)
        if (state.value == null) return
        state.value = null
        val prefix = glassPreferencePrefix(area)
        preferences.edit().remove("$prefix-custom")
            .remove("$prefix-transparency").remove("$prefix-blur").remove("$prefix-refraction")
            .remove("$prefix-highlight").remove("$prefix-saturation").apply()
    }

    internal fun clearGlassOverrides() = GlassArea.entries.forEach(::followGlobalGlass)

    fun setGlassFollowsTilt(enabled: Boolean) {
        if (selectedGlassFollowsTilt == enabled) return
        selectedGlassFollowsTilt = enabled
        preferences.edit().putBoolean(GlassFollowsTiltKey, enabled).apply()
    }

    fun setGlassLightAngle(angle: Float) {
        if (!angle.isFinite()) return
        val bounded = angle.coerceIn(0f, MaxGlassLightAngle.toFloat())
        if (selectedGlassLightAngle == bounded) return
        selectedGlassLightAngle = bounded
        preferences.edit().putFloat(GlassLightAngleKey, bounded).apply()
    }

    internal fun resetGlobalGlassTuning() {
        setGlassTuning(GlassTuning.Default)
        setGlassFollowsTilt(false)
        setGlassLightAngle(DefaultGlassLightAngle)
    }

    internal fun resetAllGlassTuning() {
        resetGlobalGlassTuning()
        clearGlassOverrides()
    }

    private var selectedThemeMode by mutableStateOf(runCatching {
        AppThemeMode.valueOf(preferences.getString("theme-mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name)
    }.getOrDefault(AppThemeMode.DARK))
    val themeMode: AppThemeMode get() = selectedThemeMode
    private var selectedStylePack by mutableStateOf(runCatching {
        AppStylePack.valueOf(preferences.getString("style-pack", null).orEmpty())
    }.getOrDefault(AppStylePack.ORIGINAL))
    val stylePack: AppStylePack get() = selectedStylePack
    private var selectedNavigationGlassEnabled by mutableStateOf(preferences.getBoolean("navigation-glass-enabled", true))
    val navigationGlassEnabled: Boolean get() = selectedNavigationGlassEnabled
    var customImageUri by mutableStateOf(preferences.getString("background-image", "").orEmpty())
        private set
    private var selectedBackground by mutableStateOf(readBackground())
    val background: AppBackgroundStyle get() = selectedBackground
    private var selectedWallpaper by mutableStateOf(runCatching {
        BuiltInWallpaper.valueOf(preferences.getString("built-in-wallpaper", null).orEmpty())
    }.getOrDefault(BuiltInWallpaper.BUNNY))
    val builtInWallpaper: BuiltInWallpaper? get() = selectedWallpaper.takeIf { background == AppBackgroundStyle.BUILT_IN }
    private var selectedWifiOnlyPrecache by mutableStateOf(PlaybackNetworkPreferences.read(context))
    val wifiOnlyPrecache: Boolean get() = selectedWifiOnlyPrecache
    private var selectedNextEpisodePrefetch by mutableStateOf(PlaybackNetworkPreferences.readNextEpisode(context))
    val nextEpisodePrefetch: Boolean get() = selectedNextEpisodePrefetch

    private fun readBackground(): AppBackgroundStyle {
        val saved = runCatching { preferences.getString("background-style", null) }.getOrNull()
        if (saved == "DESKTOP") {
            preferences.edit().putString("background-style", AppBackgroundStyle.SOLID.name).apply()
        }
        return AppBackgroundStyle.entries.firstOrNull { it.name == saved } ?: AppBackgroundStyle.SOLID
    }

    /** 输入：是否仅 Wi-Fi 预缓存；输出：立即更新 UI 并持久化，播放链路下一轮采样即生效。 */
    fun setWifiOnlyPrecache(enabled: Boolean) {
        selectedWifiOnlyPrecache = enabled
        PlaybackNetworkPreferences.write(context, enabled)
    }

    /** 输入：是否预取下一集；输出：立即更新 UI 并持久化，缓存代理下一轮采样即生效。 */
    fun setNextEpisodePrefetch(enabled: Boolean) {
        selectedNextEpisodePrefetch = enabled
        PlaybackNetworkPreferences.writeNextEpisode(context, enabled)
    }

    /** 输入：深浅/系统选项；输出：立即更新 UI 并异步保存偏好。 */
    fun setThemeMode(mode: AppThemeMode) {
        selectedThemeMode = mode
        preferences.edit().putString("theme-mode", mode.name).apply()
    }

    fun setStylePack(pack: AppStylePack) {
        selectedStylePack = pack
        val wallpaper = pack.wallpaper
        val editor = preferences.edit().putString("style-pack", pack.name)
        if (wallpaper != null) {
            selectedWallpaper = wallpaper
            selectedBackground = AppBackgroundStyle.BUILT_IN
            editor.putString("built-in-wallpaper", wallpaper.name)
        } else if (background == AppBackgroundStyle.BUILT_IN) {
            selectedBackground = AppBackgroundStyle.SOLID
        }
        editor.putString("background-style", background.name).apply()
    }

    fun setBuiltInWallpaper(wallpaper: BuiltInWallpaper) {
        selectedWallpaper = wallpaper
        selectedBackground = AppBackgroundStyle.BUILT_IN
        preferences.edit().putString("built-in-wallpaper", wallpaper.name)
            .putString("background-style", background.name).apply()
    }

    fun setNavigationGlassEnabled(enabled: Boolean) {
        selectedNavigationGlassEnabled = enabled
        preferences.edit().putBoolean("navigation-glass-enabled", enabled).apply()
    }

    /** 输入：背景选项；输出：切换现有背景；未选择图片时保持纯色。 */
    fun setBackground(style: AppBackgroundStyle) {
        selectedBackground = if (style == AppBackgroundStyle.CUSTOM && customImageUri.isBlank()) AppBackgroundStyle.SOLID else style
        preferences.edit().putString("background-style", background.name).apply()
    }

    /** 输入：系统文档选择器返回的 URI；输出：保存读权限、设置图片并自动选中自定义背景。 */
    fun setCustomImage(uri: String) {
        if (uri.isBlank()) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        customImageUri = uri
        selectedBackground = AppBackgroundStyle.CUSTOM
        preferences.edit()
            .putString("background-image", uri)
            .putString("background-style", background.name)
            .apply()
    }

    fun setCustomImage(uri: Uri) = setCustomImage(uri.toString())

    /** 输入：无；输出：清除已选图片，当前使用图片时回到纯色。 */
    fun clearCustomImage() {
        val previous = customImageUri
        customImageUri = ""
        if (background == AppBackgroundStyle.CUSTOM) selectedBackground = AppBackgroundStyle.SOLID
        preferences.edit().remove("background-image").putString("background-style", background.name).apply()
        if (previous.isNotBlank()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

