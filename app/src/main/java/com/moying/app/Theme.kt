package com.moying.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 所有视觉数值集中于此；Screen 只引用语义化常量。 */
object Theme {
    private data class AppliedAppearance(
        val dark: Boolean,
        val stylePack: AppStylePack,
        val minute: Int = 0,
        val timePalette: Palette? = null,
    )

    private data class Palette(
        val background: Color,
        val surface: Color,
        val elevated: Color,
        val border: Color,
        val text: Color,
        val secondary: Color,
        val muted: Color,
        val accent: Color,
        val accentSurface: Color,
    )

    private val originalDark = Palette(
        Color(0xFF0D1016), Color(0xFF181D26), Color(0xFF252D3A), Color(0xFF303A48),
        Color(0xFFF5F7FB), Color(0xFFB0BAC9), Color(0xFF8390A3), Color(0xFF7EAEFF), Color(0xFF253954),
    )
    private val originalLight = Palette(
        Color(0xFFF2F4F7), Color(0xFFFDFEFF), Color(0xFFE4EAF1), Color(0xFFD2DAE4),
        Color(0xFF18202B), Color(0xFF526174), Color(0xFF6C7A8C), Color(0xFF086FD6), Color(0xFFDDEBFC),
    )
    private val bunnyDark = Palette(
        Color(0xFF20121C), Color(0xFF2C1A27), Color(0xFF3C2836), Color(0xFF59394E),
        Color(0xFFFFF2F7), Color(0xFFEACAD9), Color(0xFFC3A4B5), Color(0xFFFFC2DE), Color(0xFF512941),
    )
    private val bunnyLight = Palette(
        Color(0xFFFFF2F6), Color(0xFFFFF9FA), Color(0xFFF8DFEA), Color(0xFFEBC1D2),
        Color(0xFF352032), Color(0xFF614258), Color(0xFF76556A), Color(0xFF943259), Color(0xFFF6D7E7),
    )
    private val silkDark = Palette(
        Color(0xFF1D1718), Color(0xFF2A2022), Color(0xFF392C2E), Color(0xFF524046),
        Color(0xFFFFF3EE), Color(0xFFE7CDC8), Color(0xFFBEA4A4), Color(0xFFEDB3AE), Color(0xFF4F3337),
    )
    private val silkLight = Palette(
        Color(0xFFF7EDE8), Color(0xFFFFF9F5), Color(0xFFEEDFD9), Color(0xFFE1C6BD),
        Color(0xFF372627), Color(0xFF62474B), Color(0xFF775A5B), Color(0xFF883E50), Color(0xFFF0D8D8),
    )
    private val moonDark = Palette(
        Color(0xFF141423), Color(0xFF1D2030), Color(0xFF2C3046), Color(0xFF414967),
        Color(0xFFF4F0FF), Color(0xFFD3CFEB), Color(0xFFACAED0), Color(0xFFBEC4FF), Color(0xFF383B65),
    )
    private val moonLight = Palette(
        Color(0xFFECEBFC), Color(0xFFF8F7FF), Color(0xFFE4E1F6), Color(0xFFD1CBEA),
        Color(0xFF252345), Color(0xFF4B4770), Color(0xFF625D80), Color(0xFF4D45A8), Color(0xFFE3DDFC),
    )

    private val citrusDark = Palette(
        Color(0xFF25150C), Color(0xFF342014), Color(0xFF442B1B), Color(0xFF684328),
        Color(0xFFFFF3DF), Color(0xFFF3D2B8), Color(0xFFDAB296), Color(0xFFFFB575), Color(0xFF593021),
    )
    private val citrusLight = Palette(
        Color(0xFFFFF2DC), Color(0xFFFFF9EB), Color(0xFFFBE5CB), Color(0xFFEDC8A6),
        Color(0xFF402316), Color(0xFF6C412B), Color(0xFF815136), Color(0xFFA43B17), Color(0xFFFFD7B4),
    )
    private val mintDark = Palette(
        Color(0xFF0D2421), Color(0xFF16312B), Color(0xFF21423A), Color(0xFF326457),
        Color(0xFFE8FFF5), Color(0xFFC3F0DF), Color(0xFF9DCCBA), Color(0xFF75F2C2), Color(0xFF1F5345),
    )
    private val mintLight = Palette(
        Color(0xFFE5FAEE), Color(0xFFF3FFF8), Color(0xFFD3F2E3), Color(0xFFAADCC8),
        Color(0xFF14352C), Color(0xFF2F5B4E), Color(0xFF456D5E), Color(0xFF05614F), Color(0xFFBAEEDB),
    )
    private val berryDark = Palette(
        Color(0xFF2B1026), Color(0xFF3C1733), Color(0xFF4A2041), Color(0xFF713161),
        Color(0xFFFFF1F8), Color(0xFFF7C9E4), Color(0xFFDFA5C9), Color(0xFFFFA1DB), Color(0xFF652656),
    )
    private val berryLight = Palette(
        Color(0xFFFFE7F3), Color(0xFFFFF5FB), Color(0xFFF8D4EA), Color(0xFFE8ACD2),
        Color(0xFF481B3A), Color(0xFF6D3159), Color(0xFF7F426C), Color(0xFF9B166B), Color(0xFFF6B9DF),
    )
    private val arcadeDark = Palette(
        Color(0xFF131333), Color(0xFF1D1D46), Color(0xFF292853), Color(0xFF464578),
        Color(0xFFF6F3FF), Color(0xFFD8D3FF), Color(0xFFB3ACEC), Color(0xFFB5B5FF), Color(0xFF3C347B),
    )
    private val arcadeLight = Palette(
        Color(0xFFEFE7FF), Color(0xFFFAF5FF), Color(0xFFE4D8FF), Color(0xFFCBB9EF),
        Color(0xFF2E1950), Color(0xFF4D3976), Color(0xFF635089), Color(0xFF5030B0), Color(0xFFD9CBFF),
    )
    private val goatAndWolfDark = Palette(
        Color(0xFF10232A), Color(0xFF18323A), Color(0xFF21414B), Color(0xFF4A7078),
        Color(0xFFF3FCFF), Color(0xFFD2E6E9), Color(0xFFABC7CE), Color(0xFFACF0C5), Color(0xFF24574D),
    )
    private val goatAndWolfLight = Palette(
        Color(0xFFE7F5FF), Color(0xFFF6FCFF), Color(0xFFD9EFF9), Color(0xFFAFD4E3),
        Color(0xFF153444), Color(0xFF305666), Color(0xFF406272), Color(0xFF14614D), Color(0xFFCCEDCE),
    )
    private val valleyTrainDark = Palette(
        Color(0xFF121D19), Color(0xFF203029), Color(0xFF2B3C34), Color(0xFF4B6556),
        Color(0xFFF3F6EE), Color(0xFFD4E0D6), Color(0xFFABC0AF), Color(0xFFD9BD90), Color(0xFF4A4435),
    )
    private val valleyTrainLight = Palette(
        Color(0xFFF3F3E8), Color(0xFFFBFAF1), Color(0xFFE2E9DB), Color(0xFFB8C9B8),
        Color(0xFF23362C), Color(0xFF3D5547), Color(0xFF4D6457), Color(0xFF774827), Color(0xFFEBDDCA),
    )

    private data class TimeAnchor(val hour: Int, val light: Palette, val dark: Palette)

    private val timeAnchors = listOf(
        TimeAnchor(0, moonLight, moonDark),
        TimeAnchor(4, originalLight, originalDark),
        TimeAnchor(7, citrusLight, citrusDark),
        TimeAnchor(12, mintLight, mintDark),
        TimeAnchor(16, originalLight, originalDark),
        TimeAnchor(19, silkLight, silkDark),
        TimeAnchor(21, berryLight, berryDark),
        TimeAnchor(24, moonLight, moonDark),
    )

    private fun paletteAtTime(dark: Boolean, minute: Int): Palette {
        val nextIndex = timeAnchors.indexOfFirst { it.hour * 60 > minute }
        val start = timeAnchors[nextIndex - 1]
        val end = timeAnchors[nextIndex]
        val fraction = (minute - start.hour * 60).toFloat() / ((end.hour - start.hour) * 60)
        val blend = fraction * fraction * (3f - 2f * fraction)
        val from = if (dark) start.dark else start.light
        val to = if (dark) end.dark else end.light
        return Palette(
            background = lerp(from.background, to.background, blend),
            surface = lerp(from.surface, to.surface, blend),
            elevated = lerp(from.elevated, to.elevated, blend),
            border = lerp(from.border, to.border, blend),
            text = lerp(from.text, to.text, blend),
            secondary = lerp(from.secondary, to.secondary, blend),
            muted = lerp(from.muted, to.muted, blend),
            accent = lerp(from.accent, to.accent, blend),
            accentSurface = lerp(from.accentSurface, to.accentSurface, blend),
        )
    }

    private var appearance by mutableStateOf(AppliedAppearance(true, AppStylePack.ORIGINAL))
    val isDark: Boolean get() = appearance.dark
    private val isOriginal: Boolean get() = appearance.stylePack == AppStylePack.ORIGINAL
    private val palette: Palette get() = when (appearance.stylePack) {
        AppStylePack.ORIGINAL -> if (isDark) originalDark else originalLight
        AppStylePack.TIME -> checkNotNull(appearance.timePalette)
        AppStylePack.BUNNY -> if (isDark) bunnyDark else bunnyLight
        AppStylePack.SILK -> if (isDark) silkDark else silkLight
        AppStylePack.MOON -> if (isDark) moonDark else moonLight
        AppStylePack.CITRUS -> if (isDark) citrusDark else citrusLight
        AppStylePack.MINT -> if (isDark) mintDark else mintLight
        AppStylePack.BERRY -> if (isDark) berryDark else berryLight
        AppStylePack.ARCADE -> if (isDark) arcadeDark else arcadeLight
        AppStylePack.GOAT_AND_WOLF -> if (isDark) goatAndWolfDark else goatAndWolfLight
        AppStylePack.VALLEY_TRAIN -> if (isDark) valleyTrainDark else valleyTrainLight
    }

    /** 深浅与配色一起更新，应用内各区域读取同一套颜色。 */
    fun applyAppearance(dark: Boolean, stylePack: AppStylePack = AppStylePack.ORIGINAL, minuteOfDay: Int = 0) {
        val minute = if (stylePack == AppStylePack.TIME) Math.floorMod(minuteOfDay, 24 * 60) else 0
        if (appearance.dark == dark && appearance.stylePack == stylePack && appearance.minute == minute) return
        val timePalette = if (stylePack == AppStylePack.TIME) paletteAtTime(dark, minute) else null
        appearance = AppliedAppearance(dark, stylePack, minute, timePalette)
    }

    object Colors {
        val Background get() = palette.background
        val Surface get() = palette.surface
        val Elevated get() = palette.elevated
        val Border get() = palette.border
        val Text get() = palette.text
        val Secondary get() = palette.secondary
        val Muted get() = palette.muted
        val Accent get() = palette.accent
        val AccentSurface get() = palette.accentSurface
        val OnMedia = Color(0xFFF7F9FF)
        val OnAccent get() = if (!isOriginal && isDark) palette.background else OnMedia
        val MediaSecondary = Color(0xFFD3DAE5)
        val Black = Color(0xFF000000)
        val Overlay = Color(0xB3000000)
        val PlayerControls = Color(0x99000000)
        val Buffered = Color(0xFF6D707A)
        val Transparent = Color.Transparent
        val BatteryCharging = Color(0xFF6DD49B)
        val BatteryLow get() = if (isDark) Color(0xFFFF7B84) else Color(0xFFC83A46)
        val PosterFallback get() = when {
            !isOriginal -> palette.elevated
            isDark -> Color(0xFF252D39)
            else -> Color(0xFFDDE4EC)
        }
    }
    object Glass {
        val Fill get() = if (isOriginal) {
            if (isDark) Color(0x941C2738) else Color(0xA3F8FBFF)
        } else palette.surface.copy(alpha = if (isDark) .58f else .64f)
        val FieldFill get() = if (isOriginal) {
            if (isDark) Color(0x8C101A29) else Color(0xB3F7FBFF)
        } else palette.surface.copy(alpha = if (isDark) .55f else .70f)
        val SelectedFill get() = if (isOriginal) {
            if (isDark) Color(0x7A354E73) else Color(0x995B9DDD)
        } else palette.accentSurface.copy(alpha = if (isDark) .72f else .82f)
        val RimLight get() = if (isDark) Color(0x14FFFFFF) else Color(0xD9FFFFFF)
        val RimQuiet get() = if (isDark) Color(0x0AFFFFFF) else Color(0x24647892)
        val RimBottom get() = if (isDark) Color(0x26000000) else Color(0x50849AB4)
        val InnerLight get() = if (isDark) Color(0x14FFFFFF) else Color(0x80FFFFFF)
        val InnerShade get() = if (isDark) Color(0x55000000) else Color(0x12607490)
        val TouchLight get() = if (isDark) Color(0x1FFFFFFF) else Color(0x66FFFFFF)
        val CardTint get() = if (isOriginal) {
            if (isDark) Color(0xFF152338) else Color(0xFFF5FAFF)
        } else palette.surface
        val CardTintAlpha get() = if (isDark) .19f else .26f
        val Edge = .75.dp
        val InnerEdge = 3.dp
        const val LightRadius = 1.4f
        val RefractionPixels = 18.dp
        val LensEdge = 28.dp
        val BlurRadius = 2.dp
        val SpecularWidth = 12.dp
        const val SoftShade = .055f
        const val AmbientReflection = .075f
        const val Saturation = 1.12f
        const val TouchBulge = .035f
        const val ChromaticSplit = .035f
        val RefractedTint = Color(0xFF111722)
        const val RefractedTintAlpha = .22f
        const val RefractionHighlight = .42f
        const val RefractionAlpha = 1f
    }
    object NavGlass {
        const val ForegroundZ = 1f
        const val IconScaleRest = 1f
        const val IconScalePeak = 1.2f
        const val IconInfluenceRadius = 1f
        val FallbackFill get() = Tint.copy(alpha = .40f)
        val Tint get() = if (isOriginal) {
            if (isDark) Color(0xFF121212) else Color(0xFFFAFAFA)
        } else palette.surface
        val InactiveText get() = if (isOriginal) {
            if (isDark) Color(0xFFD3DDEC) else Color(0xFF4C5B70)
        } else palette.secondary
        val TopRim get() = if (isDark) Color(0x14FFFFFF) else Color(0xF2FFFFFF)
        val QuietRim get() = if (isDark) Color(0x0AFFFFFF) else Color(0x30536C8A)
        val BottomRim get() = if (isDark) Color(0x33000000) else Color(0x736D87A9)
        val InnerLight get() = if (isDark) Color(0x18FFFFFF) else Color(0x99FFFFFF)
        val InnerShade get() = if (isDark) Color(0x80000000) else Color(0x1A60748C)
        val CapsuleTop get() = if (isDark) Color(0x1AFFFFFF) else Color(0xB3FFFFFF)
        val CapsuleBottom get() = if (isDark) Color(0x08000000) else Color(0x667CA4D0)
        val CapsuleRim get() = if (isDark) Color(0x14FFFFFF) else Color(0xB3FFFFFF)
        val TouchLight get() = if (isDark) Color(0x18FFFFFF) else Color(0x80FFFFFF)
        val RefractionPixels = 24.dp
        val LensEdge = 24.dp
        val BlurRadius = 8.dp
        // 所附 Catalog 材质：24dp 圆弧透镜、8dp 模糊，中心自然保持平面。
        val SamplePadding = 32.dp
        val CapsuleRefraction = 14.dp
        val CapsuleLensEdge = 10.dp
        val SpecularWidth = .5.dp
        val CaptureMargin = 30.dp
        val OuterStroke = .5.dp
        val InnerStroke = 3.dp
        val BevelInset = 2.dp
        val CapsuleInset = 4.dp
        val CapsuleGap = 0.dp
        val CapsuleStroke = .5.dp
        const val TintAlpha = .4f
        const val ChromaticSplit = 1f
        // 暗色下 shader 的方向高光是边缘发白的来源（白色加色），压到约四分之一。
        val Highlight get() = if (isDark) .14f else .5f
        const val HighlightFalloff = 1f
        const val Saturation = 1.14f
        const val LightX = .70710678f
        const val LightY = .70710678f
        const val MaxPress = 1.15f
        const val TouchBulge = .055f
        val ReleaseAmplitude = 2.dp
        val ReleaseRadius = 64.dp
        const val SelectionDamping = .72f
        const val SelectionStiffness = 340f
        const val PressDamping = .58f
        const val PressStiffness = 380f
        const val PressStretch = .025f
        const val PressSquash = .09f
        const val VelocityStretch = .045f
        const val MaxVelocityStretch = .13f
        const val TouchFollow = 0f
        const val CapsuleTintAlpha = .10f
        const val TouchRadius = .75f
        const val PressMillis = 130
        val TetherUp = 12.dp
        val TetherDown = 4.dp
        val HorizontalTether = 4.dp
        val SwipeSlop = 8.dp
        const val TetherStiffness = 340f
        const val TetherDamping = .68f
        const val TetherFollow = .34f
        const val TetherSpeed = 1400f
        const val TetherScaleX = .018f
        const val TetherScaleY = .035f
        const val SwipeCommitFraction = .18f
        const val SwipeRubber = .22f
        const val MaxSwipeVelocity = 10f
    }
    object PagerMotion {
        val PageNames = listOf("首页", "播放", "历史")
        const val PageCount = 3
        const val HomePage = 0
        const val PlayerPage = 1
        const val HistoryPage = 2
        const val AdjacentPages = 1
        const val SettledEpsilon = .001f
        const val VisibleEpsilon = .001f
        const val SettleDamping = .86f
        const val SettleStiffness = 380f
        const val VelocityStiffness = 20f
    }
    object CatalogGlass {
        val ScrollLensHeight = 16.dp
        val SliderBlur = 8.dp
        val SliderLensHeight = 10.dp
        val SliderRefraction = 14.dp
        val SliderNominalHeight = 24.dp
        const val PlaygroundLensFraction = .1f
        const val PlaygroundRefractionFraction = .2f
        // 暗色下整圈边缘的白色加色是海报卡白边的根源，大幅压暗。
        val PlainHighlight get() = if (isDark) .10f else .38f
        val AmbientHighlight get() = if (isDark) .10f else .38f
    }
    object GlassTuning {
        val MaxBlur = 20.dp
        val MaxRefraction = 44.dp
        val HighlightWidth = 1.dp
        val Highlight get() = if (isDark) .39f else .47f
    }
    object BottomTabsGlass {
        const val RestLensStrength = .42f
        val SideInset = 40.dp
        val IconSize = 24.dp
        val LabelSize = 12.sp
        val LabelLineHeight = 16.sp
        val ContentGap = 2.dp
        val PressExpansion = 16.dp
        const val LensRestScale = 1f
        const val LensPressedScale = 78f / 56f
        const val VelocityDivisor = 10f
        const val VelocityStretchX = .75f
        const val VelocitySquashY = .25f
        const val MaxVelocityDistortion = .2f
        const val FlingProjectionSeconds = .12f
        const val VelocityResetMillis = 64L
    }
    object ScrollGlass {
        val Radius = 32.dp
        val MinHeight = 160.dp
        val PageInset = 16.dp
        val ItemSpacing = 16.dp
        val ContentPadding = 16.dp
        val PosterWidth = 80.dp
        val MaxEdgePull = 8.dp
        const val EdgePullGain = .16f
        const val EdgeReturnDamping = .76f
        const val EdgeReturnStiffness = 400f
        const val MinimumPull = .1f
    }
    object PlaygroundGlass {
        val MaskInset = 4.dp
        val PosterRadius = 18.dp
        val PanelRadius = 14.dp
        val PanelInset = 5.dp
        val PanelPadding = 7.dp
        val PanelRowGap = 3.dp
        val ScoreHorizontal = 6.dp
        val ScoreVertical = 3.dp
        val EnterLift = 12.dp
        const val EnterScale = .965f
        const val EnterMillis = 320
        const val PressScale = .025f
        const val TiltDegrees = 3f
        const val CameraDistance = 12f
        const val TouchRadius = 1.5f
        const val PressWash = .08f
        const val PressSpotlight = .15f
        val MediaTint = Color(0x55101824)
        val ScoreTint = Color(0x3D101824)
    }
    object SliderGlass {
        val ThumbWidth = 40.dp
        val ThumbHeight = 24.dp
        val TrackHeight = 6.dp
        val TrackColor get() = if (isDark) Color(0xFF787880).copy(alpha = .36f) else Color(0xFF787878).copy(alpha = .20f)
        val ProgressColor get() = if (isOriginal) {
            if (isDark) Color(0xFF0091FF) else Color(0xFF0088FF)
        } else palette.accent
        const val RestScale = 1f
        const val PressedScale = 1.5f
        const val ValueDamping = 1f
        const val ValueStiffness = 1000f
        const val ValueThreshold = .001f
        const val VelocityDamping = .5f
        const val VelocityStiffness = 300f
        const val VelocityThreshold = .01f
        const val PressDamping = 1f
        const val PressStiffness = 1000f
        const val ScaleXDamping = .6f
        const val ScaleYDamping = .7f
        const val ScaleStiffness = 250f
        const val ScaleThreshold = .001f
        const val VelocityDivisor = 10f
        const val VelocityStretchX = .75f
        const val VelocitySquashY = .25f
        const val MaxVelocityDistortion = .2f
        const val MinScaleY = .8f
        const val TrackProjectionRestScaleX = 2f / 3f
        const val ReleaseSettleFraction = .025f
        const val KeyboardStep = .05f
        const val SeekAcknowledgementMillis = 1500L
        const val SeekTimeoutMillis = 2000L
    }
    object Opacity {
        const val DisabledControl = .42f
    }
    object Space {
        val None = 0.dp
        val Hairline = 1.dp
        val Tiny = 2.dp
        val StatusGap = 6.dp
        val XSmall = 4.dp
        val Small = 8.dp
        val Medium = 12.dp
        val Large = 16.dp
        val Page = 20.dp
        val XLarge = 24.dp
        val Section = 28.dp
        val XXLarge = 32.dp
        val BottomInset = 92.dp
    }
    object Radius {
        val Tag = 4.dp
        val Poster = 6.dp
        val Control = 8.dp
        val Card = 20.dp
        val Navigation = 22.dp
        val Pill = 50.dp
        val StatusBattery = 2.dp
    }
    object Size {
        val Touch = 48.dp
        val Icon = 22.dp
        // 播放/下一集图标各向中间收 6.5dp：视觉间距减半，触控区仍是 48dp。
        val PlayerIconInset = (Touch - Icon) / 4
        // 整组再左移 10.5dp，让播放图标的左留白与全屏图标的右留白一致。
        val PlayerRowShift = 10.5.dp
        val SmallIcon = 16.dp
        val Badge = 30.dp
        val PosterWidth = 74.dp
        val HistoryPoster = 86.dp
        val HistoryThumb = 112.dp
        val NavHeight = 64.dp
        val ResumeHeight = 82.dp
        val ProgressHeight = 3.dp
        val SeekHeight = 36.dp
        val PlayerSeekHeight = 24.dp
        val PlayerSeekLowerOffset = 3.dp
        val PlayerControlsRow = 40.dp
        val PlayerControlsHeight = 72.dp
        val CenterPlayButton = 72.dp
        val CenterPlayIcon = 34.dp
        val PlayerStatusHeight = 36.dp
        val StatusBatteryWidth = 24.dp
        val StatusBatteryHeight = 12.dp
        val StatusBatteryTerminal = 2.dp
        val StatusBatteryStroke = 1.dp
        val StatusBatteryInset = 2.dp
        const val PlayerTitleFraction = .42f
        val SeekThumb = 5.dp
        val Episode = 44.dp
        val PlayerPanelWidth = 280.dp
        val PlayerPanelMaxHeight = 240.dp
        val PlayerPanelHeaderHeight = 36.dp
        val PlayerChipMin = 64.dp
        const val PlayerPanelFraction = .72f
        val CoverAspect = 2f / 3f
        val VideoAspect = 16f / 9f
        val MinPortraitVideoAspect = 1f
        val Weight = 1f
        const val ImportTextLines = 5
        const val SingleLine = 1
        const val EpisodeColumns = 5
        const val PlayerEpisodeColumns = 3
        const val NearEndOffset = 6
        const val FullFraction = 1f
        const val EmptyFraction = 0f
        const val CenterFraction = .5f
        const val FullscreenWeight = 1f
        const val Stroke = 2f
        const val IconGrid = 24f
    }
    /** 宽屏适配参数集中于此；分支只依据窗口尺寸类，不识别设备型号。 */
    object Layout {
        // 首页海报：按窗口可用宽度计算列数，150dp 步长在手机竖屏仍得到 3 列。
        val PosterColumnStep = 150.dp
        const val PosterColumnsMin = 3
        const val PosterColumnsMax = 8
        // 历史记录在宽屏下改多列，避免单条记录被拉成超宽横条。
        val HistoryCardMinWidth = 360.dp
        // 播放页横屏并排时视频区与详情区的权重。
        const val PlayerSplitMainWeight = 1.7f
        const val PlayerSplitSideWeight = 1f
        // 播放页剧集按钮参考宽度，用于按容器实际宽度计算列数。
        val EpisodeChipWidth = 80.dp
        const val EpisodeColumnsMax = 10
        // 宽屏播放器面板加宽，并多容纳一列剧集。
        val PlayerPanelWidthWide = 360.dp
        val PlayerPanelMaxHeightWide = 300.dp
        const val PlayerEpisodeColumnsWide = 4
        // 底部导航在宽屏下收敛为居中胶囊，不横跨整屏。
        val NavMaxWidth = 520.dp
        // 宽屏面板居中展示，并允许更高的内容区。
        val PanelMaxHeightWide = 760.dp
    }
    object Type {
        private val Base get() = TextStyle(fontFamily = FontFamily.SansSerif, color = Colors.Text)
        val Brand get() = Base.copy(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        val PageTitle get() = Base.copy(fontSize = 25.sp, fontWeight = FontWeight.Bold)
        val Title get() = Base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        val Section get() = Base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        val Body get() = Base.copy(fontSize = 14.sp, lineHeight = 22.sp)
        val Label get() = Base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
        val PlayerStatus get() = Base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val Caption get() = Base.copy(fontSize = 11.sp, lineHeight = 17.sp)
        val Micro get() = Base.copy(fontSize = 10.sp)
        val Hero get() = Base.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
    object PanelMotion {
        val MaxWidth = 640.dp
        const val Damping = .86f
        const val Stiffness = 360f
        const val SettleThreshold = .001f
        const val ContentStart = .24f
        const val GlyphEnd = .36f
        const val ContentReady = .995f
    }
    object Appearance {
        val PanelMaxHeight = 600.dp
        val PanelRadius = 28.dp
        val OptionHeight = 48.dp
        val BackgroundPreviewHeight = 72.dp
        val PanelScrim = Color(0x66000000)
        val PreviewSolidColor get() = Colors.Background
        val PreviewBlueColor get() = if (isDark) Color(0xFF304866) else Color(0xFFB5CBE4)
        val PreviewSandColor get() = if (isDark) Color(0xFF4C4135) else Color(0xFFE7D6BE)
    }
    object Background {
        val BlueBase get() = if (isDark) Color(0xFF111F32) else Color(0xFFE0EAF6)
        val BlueLight get() = if (isDark) Color(0x804C6D98) else Color(0xA3B5CEEB)
        val BlueShade get() = if (isDark) Color(0xFF0C1420) else Color(0xFFF5F8FC)
        val SandBase get() = if (isDark) Color(0xFF251F1B) else Color(0xFFF1E9DC)
        val SandLight get() = if (isDark) Color(0x736D5840) else Color(0x99DFC9AA)
        val SandShade get() = if (isDark) Color(0xFF15171D) else Color(0xFFFAF7F0)
        val ImageScrim get() = if (isDark) Color.Black else Color.White
        val ImageScrimTone get() = if (isDark) 0f else 1f
        val ImageTargetTone get() = if (isDark) .46f else .62f
        const val ImageMaxScrimAlpha = .18f
        val ImageHeaderTargetTone get() = if (isDark) .19f else .92f
        const val ImageHeaderMaxAlpha = .35f
        const val ImageHeaderMidAlpha = .35f
        const val ImageHeaderFraction = .30f
        const val ImageBrightPercentile = .85f
        const val ImageDarkPercentile = .15f
        const val ImageSampleColumns = 9
        const val ImageSampleRows = 13
        const val LightXFraction = .78f
        const val LightYFraction = .12f
        const val LightRadiusFraction = 1.05f
        const val ShadeYFraction = .92f
        const val ShadeXFraction = .16f
        const val ShadeRadiusFraction = .90f
        const val ImageMaxEdge = 2048
        const val ImageMaxPixels = 4L * 1024L * 1024L
        const val ImageSizeBucket = 64
    }
    object Motion {
        const val ButtonPressedScale = .96f
        const val ButtonSpringDamping = .52f
        const val ButtonSpringStiffness = 480f
        const val GlassReleaseMillis = 450
        const val NavigationSlideMillis = 200
        const val FadeMillis = 180
        const val PlayerPollMillis = 500L
        const val HistorySaveMillis = 5_000L
        const val ControlsHideMillis = 2_000L
        const val SearchDebounceMillis = 300L
        const val GestureFeedbackMillis = 1_000L
    }
    object IconPaths {
        val Appearance = "M12 3 A9 9 0 1 0 12 21 A3 3 0 0 0 15 18 C15 16 13 17 13 15 C13 13 18 15 20 12 C23 7 17 3 12 3 Z M7 9 L7 9.1 M11 6 L11 6.1 M16 7 L16 7.1 M6 14 L6 14.1"
        val Home = "M3 11 L12 3 L21 11 M5 9 L5 21 L10 21 L10 15 L14 15 L14 21 L19 21 L19 9"
        val History = "M3 5 L3 10 L8 10 M3 10 A9 9 0 1 1 4 17 M12 7 L12 12 L16 14"
        val Bookmark = "M6 4 L18 4 L18 21 L12 17 L6 21 Z"
        val Back = "M15 5 L8 12 L15 19"
        val Play = "M8 5 L19 12 L8 19 Z"
        val Next = "M4 5 L13 12 L4 19 Z M16 5 L18 5 L18 19 L16 19 Z M21 5 L23 5 L23 19 L21 19 Z"
        val Pause = "M8 5 L8 19 M16 5 L16 19"
        val ChargingBolt = "M13 2 L5 14 L11 14 L9 22 L19 9 L13 9 Z"
        val Search = "M10.5 3 A7.5 7.5 0 1 0 10.5 18 A7.5 7.5 0 0 0 10.5 3 M16 16 L22 22"
        val Close = "M6 6 L18 18 M18 6 L6 18"
        val Fullscreen = "M3 9 L3 3 L9 3 M15 3 L21 3 L21 9 M21 15 L21 21 L15 21 M9 21 L3 21 L3 15"
        val Trash = "M3 6 L21 6 M9 6 L9 3 L15 3 L15 6 M6 6 L7 21 L17 21 L18 6 M10 10 L10 17 M14 10 L14 17"
        val Chevron = "M9 5 L16 12 L9 19"
        val Download = "M12 3 L12 15 M7 10 L12 15 L17 10 M4 20 L20 20"
        val Check = "M4 12 L9 17 L20 6"
    }
}
