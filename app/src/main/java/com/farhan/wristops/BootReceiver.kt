package com.farhan.wristops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the service back after a reboot, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.enabled(context)) {
            WristOpsService.start(context)
        }
    }
}
