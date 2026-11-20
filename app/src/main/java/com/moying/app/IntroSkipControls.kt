package com.moying.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun IntroSkipButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionButton("跳过片头", onClick, modifier = modifier, primary = false)
}

@Composable
internal fun IntroSkipSettings(
    endMillis: Long?,
    autoSkip: Boolean,
    currentMillis: Long,
    canSetEnd: Boolean,
    onSetEnd: () -> Unit,
    onAutoSkipChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Small),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        AppText(
            if (endMillis == null) "把进度拖到正片开头，再设为片头结束点。"
            else "这部剧当前线路的片头结束于 ${formatTime(endMillis)}",
            style = Theme.Type.Caption,
            color = Theme.Colors.Secondary,
        )
        ActionButton(
            "将 ${formatTime(currentMillis)} 设为片头结束",
            onSetEnd,
            modifier = Modifier.fillMaxWidth(),
            primary = false,
            enabled = canSetEnd,
        )
        val checked = endMillis != null && autoSkip
        val feedback = LocalInteractionHaptics.current
        Row(
            Modifier.fillMaxWidth().heightIn(min = Theme.Size.Touch)
                .clip(RoundedCornerShape(Theme.Radius.Control))
                .toggleable(
                    value = checked,
                    enabled = endMillis != null,
                    role = Role.Switch,
                    onValueChange = {
                        feedback.tap()
                        onAutoSkipChange(it)
                    },
                )
                .padding(horizontal = Theme.Space.Small),
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                "自动跳过片头",
                modifier = Modifier.weight(1f),
                style = Theme.Type.Label,
                color = if (endMillis != null) Theme.Colors.Text else Theme.Colors.Muted,
            )
            Box(
                Modifier.size(width = 44.dp, height = 26.dp)
                    .background(
                        if (checked) Theme.Colors.Accent else Theme.Colors.Border,
                        RoundedCornerShape(Theme.Radius.Pill),
                    )
                    .padding(3.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier.size(20.dp)
                        .background(Theme.Colors.OnMedia, RoundedCornerShape(Theme.Radius.Pill)),
                )
            }
        }
        if (endMillis != null) {
            ActionButton("清除片头设置", onClear, modifier = Modifier.fillMaxWidth(), primary = false)
        }
    }
}
