package com.farhan.wristops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.farhan.wristops.tv.TvController
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One foreground service does both jobs, because both ride on the same sensors:
 *
 *  - Sleep: heart rate settles below a threshold and the wrist stops moving for
 *    a while, so buzz once as a chance to cancel, then turn the TV off.
 *  - Shake: a few sharp acceleration peaks in quick succession open the
 *    assistant screen.
 */
class WristOpsService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // Sleep detection state
    private var lastMotionMs = System.currentTimeMillis()
    private var latestHr = 0
    private var lastSleepCheckMs = 0L
    private var cooldownUntilMs = 0L
    private var confirming = false

    // Shake detection state
    private val peakTimestamps = ArrayList<Long>()
    private var shakeBlockedUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Watching"))
        } catch (e: Exception) {
            Log.e(TAG, "Could not go foreground", e)
            status = "Needs sensor permission"
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        registerSensors()

        lastMotionMs = System.currentTimeMillis()
        status = "Watching"
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        status = "Stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- sensors

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        val hr = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hr != null) {
            sensorManager.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Log.w(TAG, "No heart rate sensor available")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAcceleration(event)
            Sensor.TYPE_HEART_RATE -> onHeartRate(event)
        }
    }

    private fun onAcceleration(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // Any real wrist movement resets the stillness clock.
        if (abs(magnitude - GRAVITY) > MOTION_THRESHOLD) {
            lastMotionMs = now
        }

        checkForShake(magnitude, now)
        maybeCheckSleep(now)
    }

    private fun onHeartRate(event: SensorEvent) {
        if (event.values.isEmpty()) return
        val bpm = event.values[0].toInt()
        // The sensor reports 0 and other nonsense while it is settling.
        if (bpm in 30..220) {
            latestHr = bpm
            status = "HR $bpm"
        }
    }

    // ------------------------------------------------------------------ shake

    private fun checkForShake(magnitude: Float, now: Long) {
        if (now < shakeBlockedUntilMs) return
        if (magnitude < Prefs.shakeForce(this)) return

        peakTimestamps.add(now)
        while (peakTimestamps.isNotEmpty() && now - peakTimestamps[0] > SHAKE_WINDOW_MS) {
            peakTimestamps.removeAt(0)
        }

        if (peakTimestamps.size >= SHAKE_PEAKS) {
            peakTimestamps.clear()
            shakeBlockedUntilMs = now + SHAKE_COOLDOWN_MS
            launchAssistant()
        }
    }

    private fun launchAssistant() {
        buzz(longArrayOf(0, 40))
        val intent = Intent(this, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open assistant", e)
        }
    }

    // ------------------------------------------------------------------ sleep

    private fun maybeCheckSleep(now: Long) {
        if (confirming) return
        if (now - lastSleepCheckMs < SLEEP_CHECK_INTERVAL_MS) return
        lastSleepCheckMs = now

        if (now < cooldownUntilMs) return
        if (Prefs.tvIp(this).isEmpty()) return

        val hr = latestHr
        if (hr !in 30..Prefs.hrThreshold(this)) return

        val stillMs = Prefs.stillMinutes(this) * 60_000L
        if (now - lastMotionMs < stillMs) return

        beginConfirmation()
    }

    /**
     * Buzz and wait. If the wrist moves during the window the wearer is awake,
     * so back off; moving is the natural reaction to a buzz.
     */
    private fun beginConfirmation() {
        confirming = true
        status = "Asleep? buzzing"
        val motionBefore = lastMotionMs

        buzz(longArrayOf(0, 300, 400, 300, 400, 300))

        handler.postDelayed({
            confirming = false
            val now = System.currentTimeMillis()
            if (lastMotionMs > motionBefore) {
                status = "Cancelled, you moved"
                cooldownUntilMs = now + CANCEL_COOLDOWN_MS
            } else {
                cooldownUntilMs = now + FIRE_COOLDOWN_MS
                turnTvOff()
            }
        }, CONFIRM_WINDOW_MS)
    }

    private fun turnTvOff() {
        status = "Turning TV off"
        Thread {
            val outcome = TvController.powerOff(applicationContext)
            status = outcome.message
            handler.post { updateNotification(outcome.message) }
        }.start()
    }

    // ------------------------------------------------------------- housekeeping

    private fun buzz(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristOps::sensors")
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "Could not take wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release wake lock", e)
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wrist Ops",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wrist Ops")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification", e)
        }
    }

    companion object {
        private const val TAG = "WristOps/Service"
        private const val CHANNEL_ID = "wristops"
        private const val NOTIFICATION_ID = 1

        private const val GRAVITY = 9.81f
        private const val MOTION_THRESHOLD = 1.5f

        private const val SHAKE_WINDOW_MS = 1200L
        private const val SHAKE_PEAKS = 3
        private const val SHAKE_COOLDOWN_MS = 4000L

        private const val SLEEP_CHECK_INTERVAL_MS = 30_000L
        private const val CONFIRM_WINDOW_MS = 20_000L
        private const val CANCEL_COOLDOWN_MS = 5 * 60_000L
        private const val FIRE_COOLDOWN_MS = 45 * 60_000L

        /** Last thing that happened, so the main screen has something to show. */
        @Volatile
        var status: String = "Stopped"

        fun start(context: Context) {
            val intent = Intent(context, WristOpsService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WristOpsService::class.java))
        }
    }
}
