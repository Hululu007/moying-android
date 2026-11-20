package com.moying.app

import android.content.Context

internal data class IntroSkipRule(val endMillis: Long = 0L, val automatic: Boolean = false) {
    fun canSkip(position: Long, duration: Long): Boolean = position < endMillis && endMillis in 1L until duration
}

/** Different playback routes can have different edits of the same series. */
internal class IntroSkipPreferences(context: Context, movieId: String, routeId: String) {
    private val preferences = context.getSharedPreferences("moying-intro-skip", Context.MODE_PRIVATE)
    private val key = "${movieId.length}:$movieId$routeId"
    private val endKey = "$key:end"
    private val autoKey = "$key:auto"

    fun read(): IntroSkipRule = IntroSkipRule(preferences.getLong(endKey, 0L), preferences.getBoolean(autoKey, false))

    fun write(rule: IntroSkipRule) {
        val editor = preferences.edit()
        if (rule.endMillis > 0L) editor.putLong(endKey, rule.endMillis).putBoolean(autoKey, rule.automatic)
        else editor.remove(endKey).remove(autoKey)
        editor.apply()
    }
}
