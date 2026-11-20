package com.moying.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogBrowseHeader(state: HomeState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(state.sourceId) { mutableStateOf(false) }
    val facets = state.catalog.facets(state.category, state.filters)
    val subtype = facets.firstOrNull { it.key == CatalogSubtype }
    val refinements = facets.filterNot { it.key == CatalogSubtype }
    val applied = refinements.mapNotNull { facet ->
        val value = state.filters[facet.key] ?: return@mapNotNull null
        facet.options.firstOrNull { it.value == value }?.label
    }
    val showFilters = expanded && refinements.isNotEmpty()

    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
        ) {
            AmbientGlassSurface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Theme.Radius.Card),
                lightResponse = false,
                style = CatalogGlassStyle.OSHIN_CONTROL,
                refractContent = true,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    key(state.sourceId) {
                        CategoryTabs(state.catalog.channels, state.category, state::selectCategory)
                    }
                    key(state.sourceId, state.category) {
                        subtype?.let { facet ->
                            val tabs = remember(facet) { facet.displayOptions().map { CatalogChannel(it.value, it.label) } }
                            CategoryTabs(tabs, state.filters[facet.key] ?: facet.defaultValue,
                                onSelect = { state.setFilter(facet.key, it) }, textStyle = Theme.Type.Label)
                        }
                    }
                }
            }
            if (refinements.isNotEmpty()) {
                val active = showFilters || applied.isNotEmpty()
                Row(
                    Modifier.heightIn(min = Theme.Size.Touch)
                        .clip(RoundedCornerShape(Theme.Radius.Pill))
                        .background(if (active) Theme.Colors.AccentSurface else Theme.Glass.Fill)
                        .hapticClickable { expanded = !expanded }
                        .semantics { stateDescription = if (showFilters) "已展开" else "已收起" }
                        .padding(horizontal = Theme.Space.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.XSmall),
                ) {
                    val color = if (active) Theme.Colors.Accent else Theme.Colors.Secondary
                    AppText("筛选", style = Theme.Type.Label, color = color)
                    if (applied.isNotEmpty()) AppText(applied.size.toString(), style = Theme.Type.Micro, color = color)
                    AppIcon(
                        Theme.IconPaths.Chevron,
                        Modifier.size(16.dp).rotate(if (showFilters) -90f else 90f),
                        tint = color,
                    )
                }
            }
        }

        key(state.sourceId, state.category) {
            if (showFilters) {
                Column(
                    Modifier.fillMaxWidth().padding(top = Theme.Space.Small)
                        .clip(RoundedCornerShape(Theme.Radius.Card))
                        .background(Theme.Glass.Fill)
                        .padding(Theme.Space.Small),
                    verticalArrangement = Arrangement.spacedBy(Theme.Space.XSmall),
                ) {
                    for (facet in refinements) key(facet.key) {
                        CatalogFacetRow(facet, state.filters[facet.key], state::setFilter)
                    }
                    if (state.filters.isNotEmpty()) {
                        CatalogTextAction("重置筛选", state::resetFilters, Modifier.align(Alignment.End))
                    }
                }
            } else if (applied.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = Theme.Space.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                ) {
                    AppText(
                        applied.joinToString(" · "),
                        Modifier.weight(1f).hapticClickable { expanded = true },
                        style = Theme.Type.Caption,
                        color = Theme.Colors.Accent,
                        maxLines = 1,
                    )
                    CatalogTextAction("重置筛选", state::resetFilters)
                }
            }
        }

        when {
            state.catalogLoading -> AppText(
                "正在读取片源分类…",
                Modifier.padding(top = Theme.Space.Small),
                style = Theme.Type.Caption,
                color = Theme.Colors.Muted,
            )
            state.catalogError != null -> Row(
                Modifier.fillMaxWidth().padding(top = Theme.Space.XSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
            ) {
                AppText(
                    state.catalogError.orEmpty(),
                    Modifier.weight(1f),
                    style = Theme.Type.Caption,
                    color = Theme.Colors.Secondary,
                    maxLines = 2,
                )
                CatalogTextAction("重试分类", state::retryCatalog)
            }
        }
    }
}

@Composable
private fun CatalogFacetRow(
    facet: CatalogFacet,
    filterValue: String?,
    onSelect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedValue = filterValue ?: facet.defaultValue
    val choices = facet.displayOptions()
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.XSmall),
    ) {
        AppText(
            facet.label,
            Modifier.widthIn(min = 42.dp, max = 76.dp),
            style = Theme.Type.Caption,
            color = Theme.Colors.Secondary,
            maxLines = 2,
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.XSmall),
        ) {
            for (option in choices) {
                val active = option.value == selectedValue
                val shape = RoundedCornerShape(Theme.Radius.Pill)
                Box(
                    Modifier.heightIn(min = Theme.Size.Touch).widthIn(max = 168.dp)
                        .clip(shape)
                        .background(if (active) Theme.Colors.AccentSurface else Theme.Colors.Transparent)
                        .hapticClickable { onSelect(facet.key, option.value) }
                        .semantics { selected = active; role = Role.RadioButton }
                        .padding(horizontal = Theme.Space.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        option.label,
                        style = Theme.Type.Label,
                        color = if (active) Theme.Colors.Accent else Theme.Colors.Secondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun CatalogFacet.displayOptions(): List<CatalogOption> =
    if (defaultValue.isEmpty() && options.none { it.value.isEmpty() }) {
        listOf(CatalogOption("", if (label == "排序") "默认" else "全部")) + options
    } else options

@Composable
private fun CatalogTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = Theme.Size.Touch).clip(RoundedCornerShape(Theme.Radius.Pill))
            .hapticClickable(onClick = onClick).padding(horizontal = Theme.Space.Small),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, style = Theme.Type.Label, color = Theme.Colors.Accent, maxLines = 1)
    }
}

@Composable
internal fun CatalogEmptyState(state: HomeState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        AppText(
            when {
                state.filters.isNotEmpty() -> "没有符合筛选条件的影片"
                state.query.isNotBlank() -> "没有找到相关影片"
                else -> "当前频道暂无影片"
            },
            style = Theme.Type.Caption,
            color = Theme.Colors.Muted,
        )
        if (state.filters.isNotEmpty()) {
            ActionButton("重置筛选", state::resetFilters, primary = false)
        } else if (state.query.isNotBlank()) {
            ActionButton("清空搜索", { state.setQuery("") }, primary = false)
        }
    }
}
