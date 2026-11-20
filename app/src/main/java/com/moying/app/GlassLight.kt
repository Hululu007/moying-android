package com.moying.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val SamplePeriodMicros = 40_000
private const val SamplePeriodNanos = SamplePeriodMicros * 1_000L
private const val MinimumTilt = SensorManager.GRAVITY_EARTH * .12f
private const val DirectionThreshold = .045f
private const val AccelerationWeight = .20f
private const val GravityWeight = .35f
private val DefaultLightDirection = Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY)
private val DefaultGlassLight = mutableStateOf(DefaultLightDirection)

internal val LocalGlassLight = staticCompositionLocalOf<State<Offset>> { DefaultGlassLight }

internal fun glassLightDirection(angle: Float): Offset {
    if (!angle.isFinite()) return DefaultLightDirection
    val radians = Math.toRadians(angle.toDouble())
    return Offset(cos(radians).toFloat(), sin(radians).toFloat())
}

/** The sensor updates a draw-only state while the activity is in the foreground. */
@Composable
internal fun rememberGlassLight(
    followTilt: Boolean,
    fixedAngle: Float,
    updatesPaused: () -> Boolean,
): State<Offset> {
    val view = LocalView.current
    val enabled by rememberUpdatedState(followTilt)
    val fixedDirection by rememberUpdatedState(glassLightDirection(fixedAngle))
    val paused by rememberUpdatedState(updatesPaused)
    val tiltDirection = remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(view, followTilt) {
        if (!followTilt) return@DisposableEffect onDispose { }
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
            ?: return@DisposableEffect onDispose { }
        val manager = view.context.getSystemService(SensorManager::class.java)
            ?: return@DisposableEffect onDispose { }
        val sensors = listOfNotNull(
            manager.getDefaultSensor(Sensor.TYPE_GRAVITY),
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        )
        var listening = false
        var lastSampleAt = Long.MIN_VALUE
        var smoothedGravity: Offset? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!listening || paused()) return
                if (lastSampleAt != Long.MIN_VALUE && event.timestamp - lastSampleAt < SamplePeriodNanos) return
                lastSampleAt = event.timestamp
                val x = event.values[0]
                val y = event.values[1]
                if (!x.isFinite() || !y.isFinite()) return
                val reading = Offset(x, y)
                val weight = if (event.sensor.type == Sensor.TYPE_GRAVITY) GravityWeight else AccelerationWeight
                val filtered = smoothedGravity?.let { it + (reading - it) * weight } ?: reading
                smoothedGravity = filtered
                if (filtered.getDistanceSquared() < MinimumTilt * MinimumTilt) return
                // Sensors use the device's natural axes, with y up. Compose uses display axes, with y down.
                val displayDirection = when (view.display?.rotation) {
                    Surface.ROTATION_90 -> Offset(filtered.y, filtered.x)
                    Surface.ROTATION_180 -> Offset(-filtered.x, filtered.y)
                    Surface.ROTATION_270 -> Offset(-filtered.y, -filtered.x)
                    else -> Offset(filtered.x, -filtered.y)
                }
                val direction = displayDirection / sqrt(displayDirection.getDistanceSquared())
                val previous = tiltDirection.value
                if (previous == null || (direction - previous).getDistanceSquared() >= DirectionThreshold * DirectionThreshold) {
                    tiltDirection.value = direction
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun stop() {
            if (listening) manager.unregisterListener(listener)
            listening = false
            smoothedGravity = null
            lastSampleAt = Long.MIN_VALUE
        }
        fun updateRegistration() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (!listening) listening = sensors.any { manager.registerListener(listener, it, SamplePeriodMicros) }
            } else stop()
        }
        val observer = LifecycleEventObserver { _, _ -> updateRegistration() }
        lifecycle.addObserver(observer)
        updateRegistration()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
            tiltDirection.value = null
        }
    }
    return remember {
        derivedStateOf { if (enabled) tiltDirection.value ?: fixedDirection else fixedDirection }
    }
}
