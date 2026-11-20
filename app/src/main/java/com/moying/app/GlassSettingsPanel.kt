package com.moying.app

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToLong

private const val Percent = 100L
private val GlassAreaChoices = (listOf<GlassArea?>(null) + GlassArea.entries).chunked(4)

@Composable
internal fun GlassSettingsContent(appearance: AppAppearanceState) {
    var area by rememberSaveable { mutableStateOf<GlassArea?>(null) }
    var compareOriginal by rememberSaveable { mutableStateOf(false) }
    val current = area
    val saved = appearance.glassTuningState(current)
    // Only the preview and active percentage read the draft; the app updates on release.
    val draft = remember(current) { mutableStateOf<GlassTuning?>(null) }
    val lightDraft = remember { mutableStateOf<Long?>(null) }
    val liveLight = LocalGlassLight.current
    val previewLight = remember(appearance, liveLight) {
        derivedStateOf {
            val angle = lightDraft.value
            if (angle != null && !appearance.glassFollowsTilt) glassLightDirection(angle.toFloat())
            else liveLight.value
        }
    }
    val custom = current != null && appearance.hasGlassOverride(current)
    val previewAreas = remember(current) { current?.let(::listOf) ?: GlassArea.entries }
    val preview = remember(appearance, current, draft) {
        previewAreas.associateWith { sample ->
            derivedStateOf {
                when {
                    sample == current -> draft.value ?: saved.value
                    current == null && !appearance.hasGlassOverride(sample) -> sample.inheritedTuning(draft.value ?: saved.value)
                    else -> appearance.glassTuningState(sample).value
                }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Theme.Space.Large),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
    ) {
        AppearanceSection("调节范围") {
            val pill = RoundedCornerShape(Theme.Radius.Pill)
            Column(Modifier.fillMaxWidth().selectableGroup().padding(Theme.Space.XSmall)) {
                for (options in GlassAreaChoices) {
                    Row(Modifier.fillMaxWidth()) {
                        for (option in options) {
                            val selected = option == current
                            Box(
                                Modifier.weight(1f).heightIn(min = Theme.Size.Touch).clip(pill)
                                    .background(if (selected) Theme.Colors.AccentSurface else Color.Transparent)
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        area = option
                                        compareOriginal = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AppText(option?.label ?: "全局", style = Theme.Type.Label, maxLines = 1,
                                    color = if (selected) Theme.Colors.Accent else Theme.Colors.Text)
                            }
                        }
                    }
                }
            }
        }
        AppText(
            when {
                current == null -> "统一调整跟随全局的区域 · 海报模糊单独调节"
                custom -> "独立参数 · 全局调整不影响这里"
                current == GlassArea.POSTER -> "模糊默认为 0% · 其他参数跟随全局"
                else -> "跟随全局 · 调整任一参数后独立"
            },
            Modifier.padding(horizontal = Theme.Space.Small),
            style = Theme.Type.Caption, color = Theme.Colors.Secondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.XSmall)) {
            Row(
                Modifier.fillMaxWidth().padding(start = Theme.Space.Small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(if (compareOriginal) "原始背景" else "实时预览", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                GlassTextButton(if (compareOriginal) "显示玻璃" else "对比原图") {
                    compareOriginal = !compareOriginal
                }
            }
            GlassPreview(preview, compareOriginal, appearance.navigationGlassEnabled, previewLight)
        }
        AppearanceSection("材质") {
            GlassTuningSlider("通透度", current, appearance, saved, draft, GlassTuning::transparency) { copy(transparency = it) }
            SettingsDivider()
            GlassTuningSlider("模糊", current, appearance, saved, draft, GlassTuning::blur) { copy(blur = it) }
            SettingsDivider()
            GlassTuningSlider("折射", current, appearance, saved, draft, GlassTuning::refraction) { copy(refraction = it) }
            SettingsDivider()
            GlassTuningSlider("边缘高光", current, appearance, saved, draft, GlassTuning::highlight) { copy(highlight = it) }
            SettingsDivider()
            GlassTuningSlider("背景饱和度", current, appearance, saved, draft, GlassTuning::saturation) { copy(saturation = it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AppearanceSection("光源") {
            GlassSettingsSwitch(
                "高光跟随手机倾斜", "应用于所有区域", appearance.glassFollowsTilt,
                onCheckedChange = {
                    lightDraft.value = null
                    appearance.setGlassFollowsTilt(it)
                },
            )
            if (!appearance.glassFollowsTilt) {
                SettingsDivider()
                GlassLightAngleSlider(appearance, lightDraft)
            }
        }
        AppearanceSection("预设") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Theme.Space.Small),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextButton("清透") {
                    draft.value = null
                    appearance.setGlassTuning(GlassTuning(transparency = .91f, blur = .08f, refraction = .76f, highlight = .36f), current)
                }
                SettingsTextButton("均衡") {
                    draft.value = null
                    appearance.setGlassTuning(current?.inheritedTuning(GlassTuning.Default) ?: GlassTuning.Default, current)
                }
                SettingsTextButton("雾面") {
                    draft.value = null
                    appearance.setGlassTuning(GlassTuning(transparency = .45f, blur = .65f, refraction = .34f,
                        highlight = .28f, saturation = .40f), current)
                }
            }
        }
        if (current == null || current == GlassArea.NAVIGATION) {
            AppearanceSection {
                GlassSettingsSwitch(
                    "底部导航栏", "启用底栏玻璃折射",
                    appearance.navigationGlassEnabled, appearance::setNavigationGlassEnabled,
                )
            }
        }
        if (current == null && GlassArea.entries.any(appearance::hasGlassOverride)) {
            Box(Modifier.align(Alignment.End)) {
                GlassTextButton("所有区域跟随全局") {
                    draft.value = null
                    appearance.clearGlassOverrides()
                }
            }
        }
        if (current == null || custom) {
            Box(Modifier.align(Alignment.End)) {
                GlassTextButton(if (current == null) "恢复全局默认" else "恢复跟随全局") {
                    draft.value = null
                    if (current == null) {
                        lightDraft.value = null
                        appearance.resetGlobalGlassTuning()
                    } else appearance.followGlobalGlass(current)
                }
            }
        }
    }
}

@Composable
private fun GlassTextButton(label: String, onClick: () -> Unit) {
    AmbientGlassSurface(
        shape = RoundedCornerShape(Theme.Radius.Pill),
        style = CatalogGlassStyle.OSHIN_CONTROL,
    ) {
        SettingsTextButton(label, onClick)
    }
}

@Composable
private fun SettingsTextButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = Theme.Size.Touch).hapticClickable(onClick = onClick)
            .padding(horizontal = Theme.Space.Small),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, style = Theme.Type.Label, color = Theme.Colors.Accent)
    }
}

@Composable
private fun GlassTuningSlider(
    label: String,
    area: GlassArea?,
    appearance: AppAppearanceState,
    saved: State<GlassTuning>,
    draft: MutableState<GlassTuning?>,
    select: (GlassTuning) -> Float,
    update: GlassTuning.(Float) -> GlassTuning,
) {
    val valueState = remember(saved, draft, select) {
        derivedStateOf { (select(draft.value ?: saved.value) * Percent).roundToLong() }
    }
    Column(Modifier.fillMaxWidth().padding(start = Theme.Space.Large, top = Theme.Space.Small, end = Theme.Space.Large)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppText(label, style = Theme.Type.Body)
            AppText("${valueState.value}%", style = Theme.Type.Label, color = Theme.Colors.Accent)
        }
        LiquidSlider(
            position = (select(saved.value) * Percent).roundToLong(),
            duration = Percent,
            onSeek = { value ->
                appearance.setGlassTuning(saved.value.update(value.toFloat() / Percent), area)
                draft.value = null
            },
            contentLabel = label,
            describeValue = { "$it%" },
            onPreviewChange = { value ->
                draft.value = value?.let { saved.value.update(it.toFloat() / Percent) }
            },
        )
    }
}

@Composable
private fun GlassLightAngleSlider(appearance: AppAppearanceState, draft: MutableState<Long?>) {
    val saved = appearance.glassLightAngle.roundToLong()
    val label = "固定高光角度"
    Column(Modifier.fillMaxWidth().padding(start = Theme.Space.Large, top = Theme.Space.Small, end = Theme.Space.Large)) {
        GlassLightAngleReadout(label, saved, draft)
        LiquidSlider(
            position = saved,
            duration = MaxGlassLightAngle,
            onSeek = {
                appearance.setGlassLightAngle(it.toFloat())
                draft.value = null
            },
            contentLabel = label,
            describeValue = { "$it°" },
            onPreviewChange = { draft.value = it },
        )
    }
}

@Composable
private fun GlassLightAngleReadout(label: String, saved: Long, draft: State<Long?>) {
    val value = draft.value ?: saved
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AppText(label, style = Theme.Type.Body)
        AppText("$value°", style = Theme.Type.Label, color = Theme.Colors.Accent)
    }
}

@Composable
private fun GlassPreview(
    tuning: Map<GlassArea, State<GlassTuning>>,
    original: Boolean,
    navGlass: Boolean,
    light: State<Offset>,
) {
    val backdrop = rememberAmbientBackdrop()
    val shape = RoundedCornerShape(Theme.Radius.Card)
    val dark = Theme.isDark
    val base = if (dark) listOf(Color(0xFF152744), Color(0xFF204E6C)) else listOf(Color(0xFFB3C6EA), Color(0xFFC7E9E8))
    val bands = if (dark) listOf(Color(0xFF7056A7), Color(0xFF248B9D)) else listOf(Color(0xFF8E9EDD), Color(0xFF77B9CE))

    Box(Modifier.fillMaxWidth().height(124.dp).clip(shape)
        .clearAndSetSemantics { contentDescription = if (original) "玻璃预览原始背景" else "液态玻璃实时预览" }) {
        AmbientBackdropHost(backdrop, Modifier.matchParentSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(base))
                drawLine(
                    bands[0], Offset(-size.width * .12f, size.height * .88f),
                    Offset(size.width * .56f, size.height * .22f), size.height * .48f, StrokeCap.Round,
                )
                drawLine(
                    bands[1], Offset(size.width * .51f, size.height * .80f),
                    Offset(size.width * 1.06f, size.height * .17f), size.height * .42f, StrokeCap.Round,
                )
                repeat(12) { index ->
                    val x = size.width * (index + .5f) / 12f
                    drawLine(Color.White.copy(alpha = .14f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
        }
        if (!original) CompositionLocalProvider(
            LocalAmbientBackdrop provides backdrop,
            LocalGlassLight provides light,
        ) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth(.88f),
                verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
            ) {
                val tileHeight = when {
                    tuning.size > 1 -> 45.dp
                    GlassArea.NAVIGATION in tuning -> 40.dp
                    else -> 64.dp
                }
                for (tiles in tuning.entries.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                        for ((area, state) in tiles) {
                            GlassPreviewTile(area, state, Modifier.weight(1f).height(tileHeight),
                                glassEnabled = area != GlassArea.NAVIGATION || navGlass)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassPreviewTile(area: GlassArea, state: State<GlassTuning>, modifier: Modifier, glassEnabled: Boolean = true) {
    val navigation = area == GlassArea.NAVIGATION
    val shape = RoundedCornerShape(if (navigation) Theme.Radius.Pill else Theme.Radius.Card)
    val style = when (area) {
        GlassArea.NAVIGATION -> CatalogGlassStyle.BOTTOM_TABS
        GlassArea.CONTINUE, GlassArea.HISTORY -> CatalogGlassStyle.SCROLL
        GlassArea.POSTER -> CatalogGlassStyle.OSHIN_POSTER
        GlassArea.PLAYER, GlassArea.PANELS -> CatalogGlassStyle.OSHIN_PANEL
    }
    val tileContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (navigation) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Theme.IconPaths.Home, Modifier.size(17.dp))
                    AppIcon(Theme.IconPaths.Play, Modifier.size(17.dp), filled = true)
                    AppIcon(Theme.IconPaths.History, Modifier.size(17.dp))
                }
            } else AppText(area.label, style = Theme.Type.Label, maxLines = 1)
        }
    }
    if (glassEnabled) CompositionLocalProvider(LocalGlassTuning provides state) {
        AmbientGlassSurface(modifier, shape = shape, lightResponse = false, style = style) {
            tileContent()
        }
    } else {
        Box(modifier.clip(shape).background(Theme.NavGlass.FallbackFill)) { tileContent() }
    }
}

@Composable
private fun GlassSettingsSwitch(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val feedback = LocalInteractionHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val railColor by animateColorAsState(
        if (checked) Theme.Colors.Accent else Theme.Colors.Elevated, label = "navigation-glass-track",
    )
    val thumbOffset = animateDpAsState(
        if (checked) 27.dp else 0.dp, spring(dampingRatio = .82f, stiffness = 440f),
        label = "navigation-glass-thumb",
    )
    val thumbPressure = animateFloatAsState(
        if (pressed) 1f else 0f, spring(dampingRatio = .8f, stiffness = 420f),
        label = "navigation-glass-press",
    )
    val shape = RoundedCornerShape(Theme.Radius.Pill)
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked, interactionSource = interactionSource, indication = null, role = Role.Switch,
            onValueChange = {
                feedback.tap()
                onCheckedChange(it)
            },
        ).padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Medium),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(label, style = Theme.Type.Body)
            AppText(subtitle, style = Theme.Type.Caption, color = Theme.Colors.Secondary)
        }
        Box(
            Modifier.size(58.dp, 32.dp).clip(shape).background(railColor).padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AmbientGlassSurface(
                modifier = Modifier.offset { IntOffset(thumbOffset.value.roundToPx(), 0) }
                    .size(25.dp, 26.dp)
                    .graphicsLayer {
                        scaleX = 1f + .18f * thumbPressure.value
                        scaleY = 1f - .08f * thumbPressure.value
                    },
                shape = shape, lightResponse = false, style = CatalogGlassStyle.SLIDER_THUMB,
                materialProgressProvider = { thumbPressure.value },
            ) {
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .96f), Color(0xD9E4EDFA))),
                ))
            }
        }
    }
}
