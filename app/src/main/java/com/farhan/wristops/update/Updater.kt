package com.farhan.wristops.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.farhan.wristops.Prefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from GitHub Releases, so the watch never needs a PC again
 * after the first install.
 *
 * The release tag carries the version: a tag of "v12" means versionCode 12.
 * Anything higher than what is installed is an update.
 *
 * Wear OS has no Settings screen for "install unknown apps", so the install
 * permission has to be granted once over adb:
 *
 *   adb shell appops set com.farhan.wristops REQUEST_INSTALL_PACKAGES allow
 */
object Updater {

    private const val TAG = "WristOps/Updater"
    private const val USER_AGENT = "wrist-ops-updater"

    data class Available(
        val versionCode: Long,
        val tag: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    sealed class Result {
        object UpToDate : Result()
        data class Found(val update: Available) : Result()
        data class Failed(val message: String) : Result()
    }

    fun installedVersionCode(context: Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) {
            0L
        }
    }

    /** Asks GitHub what the newest release is. Call off the main thread. */
    fun check(context: Context): Result {
        val repo = Prefs.repo(context).trim().trim('/')
        if (repo.isEmpty()) return Result.Failed("No repo set")

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }

            val status = connection.responseCode
            if (status == 404) {
                return Result.Failed("No releases yet")
            }
            if (status !in 200..299) {
                return Result.Failed("GitHub returned $status")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val remoteVersion = parseVersion(tag)
                ?: return Result.Failed("Release tag '$tag' has no version")

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        size = asset.optLong("size")
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) return Result.Failed("Release has no APK attached")

            if (remoteVersion <= installedVersionCode(context)) {
                Result.UpToDate
            } else {
                Result.Found(Available(remoteVersion, tag, apkUrl, size))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.Failed(e.message ?: "Could not reach GitHub")
        } finally {
            connection?.disconnect()
        }
    }

    /** "v12" or "12" -> 12. Anything else -> null. */
    private fun parseVersion(tag: String): Long? =
        tag.trim().removePrefix("v").removePrefix("V").toLongOrNull()

    /**
     * Downloads and hands the APK to Android's installer. The watch shows a
     * confirmation screen; after that the app restarts itself.
     *
     * Returns an error string, or null once the installer has taken over.
     */
    fun downloadAndInstall(context: Context, update: Available): String? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return "Install permission missing. Run the appops command once over adb."
        }

        val apk = File(context.cacheDir, "update.apk")
        try {
            download(update.apkUrl, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            return "Download failed: ${e.message ?: "network error"}"
        }

        if (apk.length() == 0L) return "Downloaded file was empty"

        return try {
            install(context, apk)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            "Install failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun download(url: String, target: File) {
        var attempt = 0
        var lastError: Exception? = null
        // Bluetooth-proxied connections drop often; a couple of retries covers it.
        while (attempt < 3) {
            attempt++
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output -> copy(input, output) }
                }
                return
            } catch (e: Exception) {
                lastError = e
                target.delete()
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite("wrist-ops", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> copy(input, output) }
                session.fsync(output)
            }

            val callback = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION
            }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pending.intentSender)
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }
}
