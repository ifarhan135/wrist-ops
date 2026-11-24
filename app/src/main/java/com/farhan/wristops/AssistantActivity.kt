package com.farhan.wristops

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import com.farhan.wristops.claude.ClaudeClient
import com.farhan.wristops.tv.TvController
import java.util.Locale

/**
 * Opens on a shake: listens, sends what it heard to Claude, shows and speaks the
 * reply, and carries out the one command Claude is allowed to issue.
 */
class AssistantActivity : Activity() {

    private lateinit var heardText: TextView
    private lateinit var replyText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        heardText = findViewById(R.id.heardText)
        replyText = findViewById(R.id.replyText)

        tts = TextToSpeech(this) { statusCode ->
            ttsReady = statusCode == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }

        startListening()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (e: ActivityNotFoundException) {
            heardText.text = "No voice input"
            replyText.text = "This watch has no speech recognizer available."
        }
    }

    @Deprecated("Fine for a single known request code")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPEECH) return

        if (resultCode != RESULT_OK || data == null) {
            finish()
            return
        }

        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = results?.firstOrNull()?.trim()
        if (spoken.isNullOrEmpty()) {
            heardText.text = "Did not catch that"
            handler.postDelayed({ finish() }, 2000)
            return
        }

        heardText.text = spoken
        replyText.text = "Thinking..."
        ask(spoken)
    }

    private fun ask(question: String) {
        Thread {
            val reply = ClaudeClient.ask(applicationContext, question)
            handler.post { show(reply) }

            if (reply.turnTvOff) {
                val outcome = TvController.powerOff(applicationContext)
                if (!outcome.ok) {
                    handler.post {
                        replyText.text = "${reply.text}\n\n${outcome.message}"
                    }
                }
            }
        }.start()
    }

    private fun show(reply: ClaudeClient.Reply) {
        replyText.text = reply.text
        if (reply.ok && ttsReady) {
            try {
                tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "wristops")
            } catch (e: Exception) {
                Log.w(TAG, "Could not speak", e)
            }
        }
    }

    companion object {
        private const val TAG = "WristOps/Assistant"
        private const val REQUEST_SPEECH = 7
    }
}
