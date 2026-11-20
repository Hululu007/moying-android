package com.moying.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Appearance preferences share the capsule's backdrop and the app's persisted state. */
@Composable
fun AppearancePanelContent(
    appearance: AppAppearanceState,
    message: String? = null,
    onDismiss: () -> Unit,
    onChooseImage: () -> Unit,
    onReset: () -> Unit,
    ready: Boolean = true,
) {
    var showGlassSettings by rememberSaveable { mutableStateOf(false) }
    var showBuiltInWallpapers by rememberSaveable { mutableStateOf(false) }
    val appearanceScroll = rememberScrollState()
    BackHandler(enabled = ready && showGlassSettings) { showGlassSettings = false }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Theme.Space.Page, end = Theme.Space.Medium, top = Theme.Space.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showGlassSettings) {
                IconAction("返回外观设置", Theme.IconPaths.Back, { showGlassSettings = false })
            }
            AppText(
                if (showGlassSettings) "液态玻璃设置" else "外观设置",
                style = Theme.Type.PageTitle, modifier = Modifier.weight(1f),
            )
            AmbientGlassSurface(shape = RoundedCornerShape(Theme.Radius.Pill), style = CatalogGlassStyle.OSHIN_CONTROL) {
                IconAction("关闭外观设置", Theme.IconPaths.Close, onDismiss)
            }
        }
        if (showGlassSettings) {
            GlassSettingsContent(appearance)
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(appearanceScroll).padding(Theme.Space.Large),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.Large),
        ) {
            AppearanceSection("主题套装") {
                StylePackChoices(appearance.stylePack, appearance::setStylePack)
            }
            AppearanceSection("显示模式") {
                ThemeSelector(appearance.themeMode, appearance::setThemeMode)
            }
            AppearanceSection("背景") {
                BackgroundChoices(appearance.background, onSelect = { style ->
                    if (style == AppBackgroundStyle.CUSTOM && appearance.customImageUri.isBlank()) onChooseImage()
                    else appearance.setBackground(style)
                })
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Theme.Size.Touch)
                        .hapticClickable { showBuiltInWallpapers = !showBuiltInWallpapers }
                        .semantics { stateDescription = if (showBuiltInWallpapers) "已展开" else "已收起" }
                        .padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                ) {
                    AppText("内置壁纸", style = Theme.Type.Body, modifier = Modifier.weight(1f))
                    appearance.builtInWallpaper?.let {
                        AppText(it.label, style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                    }
                    AppIcon(Theme.IconPaths.Chevron, Modifier.size(16.dp).rotate(if (showBuiltInWallpapers) -90f else 90f),
                        tint = Theme.Colors.Secondary)
                }
                if (showBuiltInWallpapers) {
                    BuiltInWallpaperChoices(appearance.builtInWallpaper, appearance::setBuiltInWallpaper)
                }
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Theme.Size.Touch).hapticClickable(onClick = onChooseImage)
                        .padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        if (appearance.customImageUri.isBlank()) "选择背景图片" else "更换背景图片",
                        style = Theme.Type.Body, modifier = Modifier.weight(1f),
                    )
                    AppIcon(Theme.IconPaths.Chevron, Modifier.size(16.dp), tint = Theme.Colors.Secondary)
                }
            }
            AppearanceSection {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Theme.Size.Touch)
                        .hapticClickable { showGlassSettings = true }
                        .padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Medium),
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        AppText("液态玻璃", style = Theme.Type.Body)
                        AppText("全局材质与分区微调", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                    }
                    AppIcon(Theme.IconPaths.Chevron, Modifier.size(16.dp), tint = Theme.Colors.Secondary)
                }
            }
            message?.let { AppText(it, style = Theme.Type.Caption, color = Theme.Colors.Accent) }
            AmbientGlassSurface(
                Modifier.align(Alignment.End), shape = RoundedCornerShape(Theme.Radius.Pill),
                style = CatalogGlassStyle.OSHIN_CONTROL,
            ) {
                Box(
                    Modifier.heightIn(min = Theme.Size.Touch).hapticClickable(onClick = onReset)
                        .padding(horizontal = Theme.Space.Large),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText("恢复默认", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
        if (title != null) {
            AppText(
                title, modifier = Modifier.padding(start = Theme.Space.Small),
                style = Theme.Type.Label, color = Theme.Colors.Secondary,
            )
        }
        AmbientGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Theme.Radius.Card),
            lightResponse = false,
            style = CatalogGlassStyle.OSHIN_CONTROL,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun StylePackChoices(selected: AppStylePack, onSelect: (AppStylePack) -> Unit) {
    AppearancePreviewGrid(AppStylePack.entries, Modifier.padding(Theme.Space.Medium)) { pack, previewHeight, modifier ->
        AppearancePreview(pack.label, pack.wallpaperAsset, selected == pack, previewHeight, modifier,
            timePreview = pack == AppStylePack.TIME) { onSelect(pack) }
    }
}

@Composable
private fun BuiltInWallpaperChoices(selected: BuiltInWallpaper?, onSelect: (BuiltInWallpaper) -> Unit) {
    AppearancePreviewGrid(
        BuiltInWallpaper.entries,
        Modifier.padding(start = Theme.Space.Medium, end = Theme.Space.Medium,
            bottom = Theme.Space.Medium),
    ) { wallpaper, previewHeight, modifier ->
        AppearancePreview(wallpaper.label, wallpaper.asset, selected == wallpaper, previewHeight, modifier) {
            onSelect(wallpaper)
        }
    }
}

@Composable
private fun <T> AppearancePreviewGrid(
    choices: List<T>,
    modifier: Modifier,
    preview: @Composable (T, Dp, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        val textWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = when {
            textWidth >= 340.dp -> 4
            textWidth >= 232.dp -> 3
            else -> 2
        }
        val spacing = Theme.Space.Small
        val previewWidth = (maxWidth - spacing * (columns - 1)) / columns
        val previewHeight = (previewWidth * 1.2f).coerceIn(80.dp, 112.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.Medium)) {
            for (row in choices.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (choice in row) preview(choice, previewHeight, Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AppearancePreview(
    label: String,
    asset: String?,
    selected: Boolean,
    previewHeight: Dp,
    modifier: Modifier,
    timePreview: Boolean = false,
    onSelect: () -> Unit,
) {
    val feedback = LocalInteractionHaptics.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.selectable(selected, role = Role.RadioButton, interactionSource = null, indication = null) {
            if (!selected) feedback.detent()
            onSelect()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Space.StatusGap),
    ) {
        Box(
            Modifier.fillMaxWidth().height(previewHeight).clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            if (timePreview) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    listOf(Color(0xFF242449), Color(0xFF926986), Color(0xFFF3B896), Color(0xFFC4E2EE)),
                )))
            } else if (asset == null) {
                Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF252D3A), Color(0xFF0D1016)))))
                BrandIcon(Modifier.size(30.dp))
            } else {
                AssetImage(asset, label, Modifier.matchParentSize().clearAndSetSemantics { })
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(Theme.Space.XSmall).size(22.dp)
                        .background(Theme.Colors.Surface, RoundedCornerShape(Theme.Radius.Pill)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(Theme.IconPaths.Check, Modifier.size(13.dp), tint = Theme.Colors.Accent)
                }
            }
        }
        AppText(label, style = Theme.Type.Caption.copy(textAlign = TextAlign.Center),
            color = if (selected) Theme.Colors.Accent else Theme.Colors.Text)
    }
}

@Composable
private fun ThemeSelector(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    val modes = AppThemeMode.entries
    val feedback = LocalInteractionHaptics.current
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().padding(Theme.Space.XSmall)) {
        val tabWidth = maxWidth / modes.size
        val indicatorOffset = animateDpAsState(
            tabWidth * selected.ordinal, spring(dampingRatio = .86f, stiffness = 440f),
            label = "theme-selection",
        )
        Box(
            Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            AmbientGlassSurface(
                modifier = Modifier.offset { IntOffset(indicatorOffset.value.roundToPx(), 0) }
                    .width(tabWidth).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp), lightResponse = false,
            ) {
                Box(Modifier.fillMaxSize().background(Theme.Colors.AccentSurface.copy(alpha = .88f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (mode in modes) {
                val active = mode == selected
                Box(
                    Modifier.weight(1f).heightIn(min = Theme.Appearance.OptionHeight)
                        .selectable(active, role = Role.RadioButton, interactionSource = null, indication = null) {
                            if (!active) feedback.detent()
                            onSelect(mode)
                        }.padding(horizontal = Theme.Space.XSmall, vertical = Theme.Space.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(mode.label, style = Theme.Type.Label, color = if (active) Theme.Colors.Accent else Theme.Colors.Text)
                }
            }
        }
    }
}

@Composable
private fun BackgroundChoices(selected: AppBackgroundStyle, onSelect: (AppBackgroundStyle) -> Unit) {
    val feedback = LocalInteractionHaptics.current
    Row(
        Modifier.fillMaxWidth().selectableGroup()
            .padding(horizontal = Theme.Space.Medium, vertical = Theme.Space.Medium),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        for (style in AppBackgroundStyle.entries.filter { it != AppBackgroundStyle.BUILT_IN }) {
            val active = selected == style
            BackgroundSwatch(
                style, active,
                Modifier.weight(1f)
                    .selectable(active, role = Role.RadioButton, interactionSource = null, indication = null) {
                        if (!active) feedback.detent()
                        onSelect(style)
                    },
            )
        }
    }
}

@Composable
private fun BackgroundSwatch(style: AppBackgroundStyle, selected: Boolean, modifier: Modifier) {
    val colors = when (style) {
        AppBackgroundStyle.BLUE -> listOf(Theme.Appearance.PreviewBlueColor, Theme.Background.BlueBase)
        AppBackgroundStyle.SAND -> listOf(Theme.Appearance.PreviewSandColor, Theme.Background.SandBase)
        AppBackgroundStyle.CUSTOM -> listOf(Theme.Colors.AccentSurface, Theme.Colors.Elevated)
        else -> listOf(Theme.Colors.Elevated, Theme.Appearance.PreviewSolidColor)
    }
    Column(
        modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Space.StatusGap),
    ) {
        Box(
            Modifier.fillMaxWidth().height(Theme.Appearance.BackgroundPreviewHeight)
                .clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Box(
                    Modifier.size(25.dp).background(Theme.Colors.AccentSurface, RoundedCornerShape(Theme.Radius.Pill)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(Theme.IconPaths.Check, Modifier.size(16.dp), tint = Theme.Colors.Accent)
                }
                style == AppBackgroundStyle.CUSTOM -> AppText("＋", style = Theme.Type.Section, color = Theme.Colors.Secondary)
            }
        }
        AppText(style.label, style = Theme.Type.Caption, color = if (selected) Theme.Colors.Accent else Theme.Colors.Secondary)
    }
}

@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.fillMaxWidth().padding(horizontal = Theme.Space.Large).height(Theme.Space.Hairline)
        .background(Theme.Colors.Border.copy(alpha = .55f)))
}
