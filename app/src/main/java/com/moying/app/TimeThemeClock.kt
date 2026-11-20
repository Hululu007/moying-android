package com.moying.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalTime

private fun localMinuteOfDay(): Int = LocalTime.now().run { hour * 60 + minute }

/** Refresh at minute boundaries, on time changes, and when the app becomes visible. */
@Composable
internal fun rememberLocalThemeMinute(activity: ComponentActivity): Int {
    var minute by remember(activity) { mutableIntStateOf(localMinuteOfDay()) }
    DisposableEffect(activity) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                minute = localMinuteOfDay()
            }
        }
        var registered = false
        fun start() {
            if (!registered) {
                ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            minute = localMinuteOfDay()
        }
        fun stop() {
            if (!registered) return
            activity.unregisterReceiver(receiver)
            registered = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return minute
}
