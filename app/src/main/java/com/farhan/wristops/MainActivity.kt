package com.farhan.wristops

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.farhan.wristops.tv.TvController

/** Settings and manual controls. Everything fits in one scrolling column. */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private lateinit var toggleButton: Button
    private lateinit var tvIpInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var hrInput: EditText
    private lateinit var stillInput: EditText
    private lateinit var shakeInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            liveText.text = WristOpsService.status
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        liveText = findViewById(R.id.liveText)
        toggleButton = findViewById(R.id.toggleButton)
        tvIpInput = findViewById(R.id.tvIpInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelInput = findViewById(R.id.modelInput)
        hrInput = findViewById(R.id.hrInput)
        stillInput = findViewById(R.id.stillInput)
        shakeInput = findViewById(R.id.shakeInput)

        loadValues()
        requestNeededPermissions()

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveValues() }

        toggleButton.setOnClickListener {
            if (Prefs.enabled(this)) {
                Prefs.setEnabled(this, false)
                WristOpsService.stop(this)
            } else {
                if (!hasSensorPermission()) {
                    toast("Allow the sensor permission first")
                    requestNeededPermissions()
                } else {
                    saveValues()
                    Prefs.setEnabled(this, true)
                    WristOpsService.start(this)
                }
            }
            updateToggleLabel()
        }

        findViewById<Button>(R.id.testTvButton).setOnClickListener {
            saveValues()
            liveText.text = "Reaching the TV..."
            Thread {
                val outcome = TvController.powerOff(applicationContext)
                handler.post { liveText.text = outcome.message }
            }.start()
        }

        findViewById<Button>(R.id.testAskButton).setOnClickListener {
            saveValues()
            startActivity(android.content.Intent(this, AssistantActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateToggleLabel()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun loadValues() {
        tvIpInput.setText(Prefs.tvIp(this))
        apiKeyInput.setText(Prefs.apiKey(this))
        modelInput.setText(Prefs.model(this))
        hrInput.setText(Prefs.hrThreshold(this).toString())
        stillInput.setText(Prefs.stillMinutes(this).toString())
        shakeInput.setText(Prefs.shakeForce(this).toString())
    }

    private fun saveValues() {
        val hr = hrInput.text.toString().trim().toIntOrNull() ?: Prefs.hrThreshold(this)
        val still = stillInput.text.toString().trim().toIntOrNull() ?: Prefs.stillMinutes(this)
        val shake = shakeInput.text.toString().trim().toFloatOrNull() ?: Prefs.shakeForce(this)

        Prefs.get(this).edit()
            .putString(Prefs.KEY_TV_IP, tvIpInput.text.toString().trim())
            .putString(Prefs.KEY_API_KEY, apiKeyInput.text.toString().trim())
            .putString(Prefs.KEY_MODEL, modelInput.text.toString().trim())
            .putInt(Prefs.KEY_HR_THRESHOLD, hr.coerceIn(35, 120))
            .putInt(Prefs.KEY_STILL_MINUTES, still.coerceIn(1, 120))
            .putFloat(Prefs.KEY_SHAKE_FORCE, shake.coerceIn(11f, 40f))
            .apply()

        statusText.text = "Saved"
    }

    private fun updateToggleLabel() {
        val running = Prefs.enabled(this)
        toggleButton.text = if (running) "Stop" else "Start"
        statusText.text = if (running) "Wrist Ops is on" else "Wrist Ops is off"
    }

    private fun hasSensorPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = ArrayList<String>()
        if (!hasSensorPermission()) {
            wanted.add(Manifest.permission.BODY_SENSORS)
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 42)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
