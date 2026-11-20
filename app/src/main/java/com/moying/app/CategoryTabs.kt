package com.moying.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One continuous underline follows the measured text widths, including larger font sizes. */
@Composable
internal fun CategoryTabs(
    categories: List<CatalogChannel>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = Theme.Type.Section,
) {
    val widths = remember(categories) { mutableStateMapOf<String, Int>() }
    val underline = remember { Animatable(0f) }
    var positioned by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val index = categories.indexOfFirst { it.id == selected }
    val ready = index >= 0 && categories.take(index + 1).all { (widths[it.id] ?: 0) > 0 }
    val gap = with(density) { Theme.Space.Medium.toPx() }
    val lineWidth = with(density) { Theme.Size.SmallIcon.toPx() }
    val target = if (ready) categories.take(index).sumOf { widths[it.id] ?: 0 } + gap * index +
        (widths[selected] ?: 0) / 2f - lineWidth / 2f else 0f
    LaunchedEffect(target, ready) {
        if (ready) {
            if (!positioned) { underline.snapTo(target); positioned = true }
            else underline.animateTo(target, spring(dampingRatio = Theme.PanelMotion.Damping, stiffness = Theme.PanelMotion.Stiffness))
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = Theme.Space.XSmall).selectableGroup().drawBehind {
            if (ready) {
                val height = Theme.Size.ProgressHeight.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - underline.value - lineWidth else underline.value
                drawRoundRect(Theme.Colors.Accent, Offset(x, size.height - height), Size(lineWidth, height), CornerRadius(height / 2f))
            }
        },
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
    ) {
        categories.forEach { category ->
            val emphasis = animateFloatAsState(if (category.id == selected) 1f else 0f, tween(Theme.Motion.FadeMillis), label = "category-emphasis")
            Box(
                Modifier.heightIn(min = Theme.Size.Touch).widthIn(min = Theme.Size.Touch, max = 168.dp)
                    .onSizeChanged { widths[category.id] = it.width }
                    .hapticClickable { onSelect(category.id) }
                    .semantics { role = Role.Tab; this.selected = category.id == selected }
                    .padding(horizontal = Theme.Space.Small, vertical = Theme.Space.Small),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    category.label,
                    style = textStyle,
                    color = lerp(Theme.Colors.Secondary, Theme.Colors.Text, emphasis.value),
                    maxLines = 1,
                )
            }
        }
    }
}
