package com.farhan.wristops.macrodroid

import android.content.Context
import android.util.Log
import com.farhan.wristops.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pokes MacroDroid's "HTTP Server Request" trigger on the phone, so phone-side
 * automations can be added without touching this app.
 *
 * Set the base URL to the trigger MacroDroid shows you, for example
 * http://192.168.1.20:8080/wristops — this appends ?event=<name>.
 * MacroDroid's server is local network only, so the watch and phone have to be
 * on the same Wi-Fi.
 */
object MacroDroidClient {

    private const val TAG = "WristOps/MacroDroid"

    /** Fire and forget: never let a missing phone hold up the real action. */
    fun notify(context: Context, event: String) {
        val base = Prefs.macroDroidUrl(context).trim()
        if (base.isEmpty()) return

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val separator = if (base.contains('?')) "&" else "?"
                val encoded = URLEncoder.encode(event, "UTF-8")
                val url = URL("$base${separator}event=$encoded")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val status = connection.responseCode
                Log.i(TAG, "Sent '$event', phone replied $status")
            } catch (e: Exception) {
                Log.w(TAG, "Could not reach MacroDroid", e)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
