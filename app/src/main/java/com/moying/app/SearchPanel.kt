package com.moying.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun SearchPanelContent(state: HomeState, ready: Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(ready) {
        if (ready) {
            focus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Large)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("搜索", style = Theme.Type.PageTitle, modifier = Modifier.weight(Theme.Size.Weight))
            IconAction("关闭搜索", Theme.IconPaths.Close, onDismiss)
        }
        AmbientGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Theme.Radius.Card),
            tint = Theme.Glass.FieldFill, style = CatalogGlassStyle.OSHIN_CONTROL) {
            Row(Modifier.fillMaxWidth().padding(Theme.Space.Large), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium)) {
                AppIcon(Theme.IconPaths.Search, tint = Theme.Colors.Secondary)
                Box(Modifier.weight(Theme.Size.Weight)) {
                    if (state.query.isEmpty()) AppText("输入片名", color = Theme.Colors.Muted)
                    BasicTextField(
                        value = state.query, onValueChange = state::setQuery,
                        textStyle = Theme.Type.Body, singleLine = true,
                        cursorBrush = SolidColor(Theme.Colors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "搜索片名" },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
            ActionButton("清空", { state.setQuery("") }, Modifier.weight(Theme.Size.Weight), primary = false, enabled = state.query.isNotEmpty())
            ActionButton("查看结果", onDismiss, Modifier.weight(Theme.Size.Weight))
        }
    }
}
