package com.farhan.wristops.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Android reports the outcome of an install session here. The important case is
 * STATUS_PENDING_USER_ACTION: the system hands back an intent that shows the
 * "update this app?" screen, and it has to be launched for anything to happen.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "Pending user action with no intent to launch")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show the install prompt", e)
                    toast(context, "Could not show the install prompt")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed")
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed ($status): $message")
                toast(context, "Update failed: ${message ?: status.toString()}")
            }
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // A receiver without a visible app cannot always toast; the log has it.
        }
    }

    companion object {
        private const val TAG = "WristOps/Install"
        const val ACTION = "com.farhan.wristops.INSTALL_RESULT"
    }
}
