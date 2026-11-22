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
    manualEndMillis: Long?,
    detected: DetectedIntro?,
    autoSkip: Boolean,
    autoDetectNext: Boolean,
    canAutoDetectNext: Boolean,
    currentMillis: Long,
    canSetEnd: Boolean,
    canDetect: Boolean,
    detecting: Boolean,
    playing: Boolean,
    detectionMessage: String?,
    nextDetectionMessage: String?,
    onDetect: () -> Unit,
    onSetEnd: () -> Unit,
    onAutoSkipChange: (Boolean) -> Unit,
    onAutoDetectNextChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val hasIntro = manualEndMillis != null || detected != null
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Small),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        AppText(
            when {
                manualEndMillis != null -> "当前线路的手动片头结束点为 ${formatTime(manualEndMillis)}"
                detected != null -> "本集识别到片头 ${formatTime(detected.startMillis)}–${formatTime(detected.endMillis)}"
                canDetect -> "识别片头会比较同一线路的相邻两集。也可以手动设置结束点。"
                else -> "当前线路没有可比对的相邻集，可以手动设置片头结束点。"
            },
            style = Theme.Type.Caption,
            color = Theme.Colors.Secondary,
        )
        if (canDetect || detecting) {
            ActionButton(
                when {
                    detecting -> "停止识别"
                    playing -> "暂停并识别片头"
                    else -> "识别片头"
                },
                onDetect,
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }
        if (detecting || detectionMessage != null) {
            AppText(
                if (detecting) "正在比较两集开场的音频…" else detectionMessage.orEmpty(),
                style = Theme.Type.Caption,
                color = Theme.Colors.Secondary,
            )
        }
        ActionButton(
            "将 ${formatTime(currentMillis)} 设为片头结束",
            onSetEnd,
            modifier = Modifier.fillMaxWidth(),
            primary = false,
            enabled = canSetEnd && !detecting,
        )
        IntroSkipToggle("自动跳过片头", hasIntro && autoSkip, hasIntro, onAutoSkipChange)
        IntroSkipToggle("临近看完时识别下一集", autoDetectNext, canAutoDetectNext, onAutoDetectNextChange)
        AppText("仅在非计费网络播放时识别。", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
        if (nextDetectionMessage != null) {
            AppText(
                nextDetectionMessage,
                style = Theme.Type.Caption,
                color = Theme.Colors.Secondary,
            )
        }
        if (hasIntro) {
            ActionButton(
                if (manualEndMillis != null) "清除手动结束点" else "清除本集识别结果",
                onClear,
                modifier = Modifier.fillMaxWidth(),
                primary = false,
                enabled = !detecting,
            )
        }
    }
}

@Composable
private fun IntroSkipToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val feedback = LocalInteractionHaptics.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Theme.Size.Touch)
            .clip(RoundedCornerShape(Theme.Radius.Control))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    feedback.tap()
                    onChange(it)
                },
            )
            .padding(horizontal = Theme.Space.Small),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            label,
            modifier = Modifier.weight(1f),
            style = Theme.Type.Label,
            color = if (enabled) Theme.Colors.Text else Theme.Colors.Muted,
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
}
