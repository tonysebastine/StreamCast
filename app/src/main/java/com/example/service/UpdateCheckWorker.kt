package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import com.example.AppLogger as Log

class UpdateCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val okHttpClient = OkHttpClient()
    private val TAG = "UpdateCheckWorker"

    override suspend fun doWork(): Result {
        val forceNotify = inputData.getBoolean("forceNotify", false)
        runUpdateCheck(forceNotify)
        return Result.success()
    }

    private suspend fun runUpdateCheck(forceNotify: Boolean) {
        val prefs = applicationContext.getSharedPreferences(UpdateCheckService.PREFS_NAME, Context.MODE_PRIVATE)
        val isSimulationMode = prefs.getBoolean(UpdateCheckService.PREF_SIMULATION_MODE, false)
        val manifestUrl = prefs.getString(UpdateCheckService.PREF_MANIFEST_URL, UpdateCheckService.DEFAULT_MANIFEST_URL) ?: UpdateCheckService.DEFAULT_MANIFEST_URL

        Log.d(TAG, "Running update check worker. SimulationMode=$isSimulationMode, URL=$manifestUrl")

        if (isSimulationMode) {
            Log.i(TAG, "Simulating app update available!")
            showUpdateNotification("1.5-Simulated", "Simulated updates available!")
            broadcastUpdateResult(true, "1.5-Simulated", "Simulated updates available!")
            return
        }

        try {
            val sanitizedUrl = manifestUrl.trim()
            val targetUrl = sanitizedUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Malformed update URL syntax: $sanitizedUrl")

            var request = Request.Builder().url(targetUrl).build()
            var response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful && (sanitizedUrl == UpdateCheckService.DEFAULT_MANIFEST_URL || sanitizedUrl == UpdateCheckService.DEFAULT_MANIFEST_URL.trim())) {
                val responseCode = response.code
                response.close()
                val fallbackUrl = UpdateCheckService.FALLBACK_MASTER_URL.trim()
                Log.w(TAG, "Manifest request returned status $responseCode on main. Retrying with master fallback URL: $fallbackUrl")
                val parsedFallbackUrl = fallbackUrl.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Malformed fallback update URL syntax: $fallbackUrl")
                request = Request.Builder().url(parsedFallbackUrl).build()
                response = okHttpClient.newCall(request).execute()
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorMsg = "Update server returned HTTP ${resp.code}. Expected if manifest is not pushed."
                    Log.w(TAG, errorMsg)
                    broadcastUpdateResult(false, "", "Update check unavailable (HTTP ${resp.code})")
                    return
                }

                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    val errorMsg = "Empty response body from manifest server"
                    Log.w(TAG, errorMsg)
                    broadcastUpdateResult(false, "", errorMsg)
                    return
                }

                Log.d(TAG, "Successfully fetched manifest: $body")
                val json = JSONObject(body)
                val serverVersionCode = json.optInt("versionCode", 0)
                val serverVersionName = json.optString("versionName", "1.0")
                val updateUrl = json.optString("updateUrl", "")
                val releaseNotes = json.optString("releaseNotes", "New performance enhancements and bug fixes.")

                val currentVersionCode = getCurrentVersionCode()
                Log.d(TAG, "Version comparison: ServerCode=$serverVersionCode, CurrentCode=$currentVersionCode")

                if (serverVersionCode > currentVersionCode) {
                    Log.i(TAG, "Newer version detected on server: $serverVersionName ($serverVersionCode)")
                    showUpdateNotification(serverVersionName, releaseNotes)
                    broadcastUpdateResult(true, serverVersionName, "Version $serverVersionName is available!")
                } else {
                    Log.d(TAG, "App is up to date.")
                    broadcastUpdateResult(false, serverVersionName, "App is already up to date.")
                }
            }
        } catch (e: IOException) {
            val errorMsg = "Failed to connect to manifest server: ${e.message}"
            Log.e(TAG, errorMsg)
            broadcastUpdateResult(false, "", errorMsg)
        } catch (e: Exception) {
            val errorMsg = "Error during update check: ${e.message}"
            Log.e(TAG, errorMsg, e)
            broadcastUpdateResult(false, "", errorMsg)
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (packageInfo.longVersionCode and 0xFFFFFFFFL).toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching current version code: ${e.message}")
            1
        }
    }

    private fun showUpdateNotification(newVersionName: String, releaseNotes: String) {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, "app_update_channel")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("StreamCast Update Available (v$newVersionName)")
            .setContentText("A new update is available. Tap to upgrade now.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Version $newVersionName introduces key updates:\n$releaseNotes\n\nTap this notification to download and install."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(9001, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification: missing POST_NOTIFICATIONS permission. ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates"
            val descriptionText = "Notifications when a newer app update is available."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("app_update_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun broadcastUpdateResult(updateAvailable: Boolean, serverVersion: String, statusMessage: String) {
        val intent = Intent(UpdateCheckService.ACTION_UPDATE_RESULT).apply {
            putExtra(UpdateCheckService.EXTRA_UPDATE_AVAILABLE, updateAvailable)
            putExtra(UpdateCheckService.EXTRA_SERVER_VERSION, serverVersion)
            putExtra(UpdateCheckService.EXTRA_STATUS_MESSAGE, statusMessage)
            setPackage(applicationContext.packageName)
        }
        applicationContext.sendBroadcast(intent)
    }
}
