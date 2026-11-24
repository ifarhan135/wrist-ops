package com.farhan.wristops.claude

import android.content.Context
import android.util.Log
import com.farhan.wristops.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Anthropic Messages API. Call off the main thread. */
object ClaudeClient {

    private const val TAG = "WristOps/Claude"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"

    /** Marker Claude emits when the spoken request was a command, not a question. */
    const val TV_OFF_MARKER = "[[TV_OFF]]"

    private val SYSTEM_PROMPT = """
        You are answering on a smartwatch screen, so keep replies under 40 words,
        plain text, no markdown, no lists. Be direct and useful.

        The wearer can also control a Google TV through you. If their request is to
        turn the TV off, stop it, or kill the screen, reply with exactly:
        $TV_OFF_MARKER
        followed by a short confirmation of under 8 words. Only use that marker for
        turning the TV off, never for anything else.
    """.trimIndent()

    data class Reply(
        val ok: Boolean,
        val text: String,
        val turnTvOff: Boolean
    )

    fun ask(context: Context, question: String): Reply {
        val apiKey = Prefs.apiKey(context).trim()
        if (apiKey.isEmpty()) {
            return Reply(false, "No API key set. Add it in the app.", false)
        }

        val body = JSONObject().apply {
            put("model", Prefs.model(context))
            put("max_tokens", 300)
            put("system", SYSTEM_PROMPT)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", question)
                }
            ))
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", API_VERSION)
            }

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = stream?.let { readAll(it) } ?: ""

            if (status !in 200..299) {
                Reply(false, describeError(status, raw), false)
            } else {
                parse(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
            Reply(false, "Could not reach Claude: ${e.message ?: "network error"}", false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(raw: String): Reply {
        return try {
            val blocks = JSONObject(raw).optJSONArray("content")
            val builder = StringBuilder()
            if (blocks != null) {
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        builder.append(block.optString("text"))
                    }
                }
            }
            var text = builder.toString().trim()
            val tvOff = text.contains(TV_OFF_MARKER)
            if (tvOff) {
                text = text.replace(TV_OFF_MARKER, "").trim()
                if (text.isEmpty()) text = "Turning the TV off."
            }
            if (text.isEmpty()) {
                Reply(false, "Claude sent an empty reply.", false)
            } else {
                Reply(true, text, tvOff)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse reply", e)
            Reply(false, "Could not read Claude's reply.", false)
        }
    }

    private fun describeError(status: Int, raw: String): String {
        val apiMessage = try {
            JSONObject(raw).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        val prefix = when (status) {
            401 -> "API key rejected"
            404 -> "Model not found. Check the model name."
            429 -> "Rate limited, try again"
            in 500..599 -> "Anthropic server error"
            else -> "Error $status"
        }
        return if (apiMessage.isBlank()) prefix else "$prefix: $apiMessage"
    }

    private fun readAll(stream: java.io.InputStream): String {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            return reader.readText()
        }
    }
}
