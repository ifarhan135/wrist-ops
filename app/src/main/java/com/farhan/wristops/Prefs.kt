package com.farhan.wristops

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place, backed by SharedPreferences. */
object Prefs {

    private const val FILE = "wristops"

    const val KEY_TV_IP = "tv_ip"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_HR_THRESHOLD = "hr_threshold"
    const val KEY_STILL_MINUTES = "still_minutes"
    const val KEY_SHAKE_FORCE = "shake_force"
    const val KEY_KEYEVENT = "keyevent"
    const val KEY_ENABLED = "enabled"
    const val KEY_ADB_PRIVATE = "adb_private"
    const val KEY_ADB_PUBLIC = "adb_public"

    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun tvIp(context: Context): String = get(context).getString(KEY_TV_IP, "") ?: ""

    fun apiKey(context: Context): String = get(context).getString(KEY_API_KEY, "") ?: ""

    fun model(context: Context): String {
        val m = get(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        return if (m.isBlank()) DEFAULT_MODEL else m
    }

    /** Heart rate at or below which we consider the wearer asleep. */
    fun hrThreshold(context: Context): Int = get(context).getInt(KEY_HR_THRESHOLD, 62)

    /** How many minutes of no wrist movement before we call it sleep. */
    fun stillMinutes(context: Context): Int = get(context).getInt(KEY_STILL_MINUTES, 10)

    /** Total acceleration, in m/s^2, that counts as a shake peak (gravity is 9.8). */
    fun shakeForce(context: Context): Float = get(context).getFloat(KEY_SHAKE_FORCE, 19f)

    /** 223 = KEYCODE_SLEEP (off, stays off). 26 = KEYCODE_POWER (toggle). */
    fun keyevent(context: Context): Int = get(context).getInt(KEY_KEYEVENT, 223)

    fun enabled(context: Context): Boolean = get(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        get(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
