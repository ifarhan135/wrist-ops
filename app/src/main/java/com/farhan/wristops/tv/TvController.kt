package com.farhan.wristops.tv

import android.content.Context
import android.util.Log
import com.farhan.wristops.Prefs
import com.farhan.wristops.adb.AdbClient
import com.farhan.wristops.adb.AdbCrypto

/** Turns the Google TV / projector off over the network. Call off the main thread. */
object TvController {

    private const val TAG = "WristOps/TV"

    /** Human-readable outcome, suitable for showing on the watch. */
    data class Outcome(val ok: Boolean, val message: String)

    fun powerOff(context: Context): Outcome {
        val ip = Prefs.tvIp(context).trim()
        if (ip.isEmpty()) {
            return Outcome(false, "No TV IP set")
        }

        val host: String
        val port: Int
        val colon = ip.lastIndexOf(':')
        if (colon > 0) {
            host = ip.substring(0, colon)
            port = ip.substring(colon + 1).toIntOrNull() ?: AdbClient.DEFAULT_PORT
        } else {
            host = ip
            port = AdbClient.DEFAULT_PORT
        }

        val keyevent = Prefs.keyevent(context)

        return try {
            val client = AdbClient(host, port, AdbCrypto.load(context))
            client.runShellCommand("input keyevent $keyevent")
            Outcome(true, "TV off")
        } catch (e: Exception) {
            Log.w(TAG, "Power off failed", e)
            Outcome(false, friendlyError(e))
        }
    }

    private fun friendlyError(e: Exception): String {
        val detail = e.message ?: e.javaClass.simpleName
        return when {
            detail.contains("ECONNREFUSED", true) ||
                detail.contains("refused", true) ->
                "TV refused the connection. Turn on Wireless debugging."

            detail.contains("ETIMEDOUT", true) ||
                detail.contains("timed out", true) ->
                "No answer from the TV. Check the IP and that it is awake."

            detail.contains("rejected our key", true) ->
                "TV rejected the key. Accept the debugging prompt on the TV."

            detail.contains("unreachable", true) ->
                "Network unreachable. Is the watch on the same Wi-Fi?"

            else -> detail
        }
    }
}
