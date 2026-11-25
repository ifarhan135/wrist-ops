package com.farhan.wristops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets settings be set over adb, because typing an API key on a watch is
 * miserable. Every field is optional:
 *
 *   adb shell am broadcast -a com.farhan.wristops.SET_CONFIG \
 *     -n com.farhan.wristops/.ConfigReceiver \
 *     --es tv_ip 192.168.1.50 --es api_key sk-ant-xxx
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val editor = Prefs.get(context).edit()
        var changed = false

        intent.getStringExtra("tv_ip")?.let {
            editor.putString(Prefs.KEY_TV_IP, it.trim()); changed = true
        }
        intent.getStringExtra("api_key")?.let {
            editor.putString(Prefs.KEY_API_KEY, it.trim()); changed = true
        }
        intent.getStringExtra("model")?.let {
            editor.putString(Prefs.KEY_MODEL, it.trim()); changed = true
        }
        intent.getStringExtra("hr")?.toIntOrNull()?.let {
            editor.putInt(Prefs.KEY_HR_THRESHOLD, it.coerceIn(35, 120)); changed = true
        }
        intent.getStringExtra("still")?.toIntOrNull()?.let {
            editor.putInt(Prefs.KEY_STILL_MINUTES, it.coerceIn(1, 120)); changed = true
        }
        intent.getStringExtra("shake")?.toFloatOrNull()?.let {
            editor.putFloat(Prefs.KEY_SHAKE_FORCE, it.coerceIn(11f, 40f)); changed = true
        }
        intent.getStringExtra("keyevent")?.toIntOrNull()?.let {
            editor.putInt(Prefs.KEY_KEYEVENT, it); changed = true
        }
        intent.getStringExtra("macrodroid_url")?.let {
            editor.putString(Prefs.KEY_MACRODROID_URL, it.trim()); changed = true
        }
        intent.getStringExtra("repo")?.let {
            editor.putString(Prefs.KEY_REPO, it.trim()); changed = true
        }

        if (changed) editor.apply()
    }
}
