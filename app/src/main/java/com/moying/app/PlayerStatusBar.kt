package com.moying.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.roundToInt

/** 输入外部布局尺寸；输出居中系统时间及右侧实时电量，不负责横屏显隐。 */
@Composable
fun PlayerStatusBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var time by remember { mutableStateOf(currentClockText()) }
    var battery by remember { mutableStateOf(BatteryReading()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val received = intent ?: return
                when (received.action) {
                    Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> time = currentClockText()
                    Intent.ACTION_BATTERY_CHANGED -> battery = readBattery(received)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // 仅接收系统广播；注册返回 sticky 电量，因此展开时不必等待下一次变化。
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        sticky?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }?.let { battery = readBattery(it) }
        time = currentClockText()
        onDispose { context.unregisterReceiver(receiver) }
    }

    Box(modifier.fillMaxWidth()) {
        AppText(time, Modifier.align(Alignment.Center), style = Theme.Type.PlayerStatus, color = Theme.Colors.OnMedia, maxLines = Theme.Size.SingleLine)
        val percentage = battery.percent?.let { "$it%" } ?: "--%"
        val description = "电量 $percentage${if (battery.charging) "，正在充电" else ""}"
        Row(
            Modifier.align(Alignment.CenterEnd).clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.StatusGap),
        ) {
            StatusBatteryIcon(battery)
            AppText(percentage, style = Theme.Type.PlayerStatus, color = Theme.Colors.OnMedia, maxLines = Theme.Size.SingleLine)
        }
    }
}

private data class BatteryReading(val percent: Int? = null, val charging: Boolean = false)

private fun readBattery(intent: Intent): BatteryReading {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) (level.toFloat() / scale * BatteryValues.PercentMaximum).roundToInt().coerceIn(0, BatteryValues.PercentMaximum) else null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    return BatteryReading(percent, status == BatteryManager.BATTERY_STATUS_CHARGING || (status == BatteryManager.BATTERY_STATUS_FULL && plugged))
}

/** 每次都重新取得 Calendar，修改系统时区后立即按当地时间显示。 */
private fun currentClockText(): String = DateFormat.format("HH:mm", Calendar.getInstance()).toString()

private object BatteryValues {
    const val PercentMaximum = 100
    const val LowPercent = 20
}

@Composable
private fun StatusBatteryIcon(battery: BatteryReading) {
    val fill = when {
        battery.charging -> Theme.Colors.BatteryCharging
        battery.percent != null && battery.percent <= BatteryValues.LowPercent -> Theme.Colors.BatteryLow
        else -> Theme.Colors.OnMedia
    }
    Box(Modifier.size(Theme.Size.StatusBatteryWidth, Theme.Size.StatusBatteryHeight)) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Theme.Size.StatusBatteryStroke.toPx()
            val terminal = Theme.Size.StatusBatteryTerminal.toPx()
            val gap = Theme.Space.Hairline.toPx()
            val inset = Theme.Size.StatusBatteryInset.toPx()
            val radius = Theme.Radius.StatusBattery.toPx()
            val bodyWidth = size.width - terminal - gap
            val bodyHeight = size.height
            drawRoundRect(
                color = Theme.Colors.OnMedia,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size((bodyWidth - stroke).coerceAtLeast(0f), (bodyHeight - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(radius),
                style = Stroke(stroke),
            )
            val fraction = (battery.percent ?: 0).toFloat() / BatteryValues.PercentMaximum
            if (fraction > 0f) drawRoundRect(
                color = fill,
                topLeft = Offset(inset, inset),
                size = Size(((bodyWidth - inset * 2f) * fraction).coerceAtLeast(0f), (bodyHeight - inset * 2f).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((radius - stroke).coerceAtLeast(0f)),
            )
            drawRoundRect(
                color = Theme.Colors.OnMedia,
                topLeft = Offset(bodyWidth + gap, bodyHeight / 3f),
                size = Size(terminal, bodyHeight / 3f),
                cornerRadius = CornerRadius(stroke),
            )
        }
        if (battery.charging) AppIcon(
            Theme.IconPaths.ChargingBolt,
            modifier = Modifier.size(Theme.Size.StatusBatteryHeight).align(Alignment.Center),
            tint = Theme.Colors.OnMedia,
            filled = true,
        )
    }
}
