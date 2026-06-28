package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.AppLogger as Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Service to periodically check for app updates by fetching a JSON manifest from a remote URL.
 * Supports manual checking, custom interval configuration, and an interactive simulation mode for QA/Testing.
 */
class UpdateCheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicJob: Job? = null
    private val okHttpClient = OkHttpClient()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "UpdateCheckService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_START -> {
                startPeriodicCheck()
            }
            ACTION_STOP -> {
                stopPeriodicCheck()
                stopSelf()
            }
            ACTION_CHECK_NOW -> {
                serviceScope.launch {
                    runUpdateCheck(forceNotify = true)
                }
            }
        }

        return START_STICKY
    }

    private fun startPeriodicCheck() {
        if (periodicJob != null && periodicJob!!.isActive) {
            Log.d(TAG, "Periodic check is already running")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalMinutes = prefs.getLong(PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        val intervalMs = intervalMinutes * 60 * 1000

        Log.i(TAG, "Starting periodic update check every $intervalMinutes minutes")
        periodicJob = serviceScope.launch {
            while (isActive) {
                runUpdateCheck(forceNotify = false)
                delay(intervalMs)
            }
        }
    }

    private fun stopPeriodicCheck() {
        Log.i(TAG, "Stopping periodic update check")
        periodicJob?.cancel()
        periodicJob = null
    }

    private suspend fun runUpdateCheck(forceNotify: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSimulationMode = prefs.getBoolean(PREF_SIMULATION_MODE, false)
        val manifestUrl = prefs.getString(PREF_MANIFEST_URL, DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL

        Log.d(TAG, "Running update check. SimulationMode=$isSimulationMode, URL=$manifestUrl")

        if (isSimulationMode) {
            // Trigger a simulated update notification immediately for demonstration/QA
            Log.i(TAG, "Simulating app update available!")
            showUpdateNotification(
                newVersionName = "1.5-Simulated",
                newVersionCode = 5,
                releaseNotes = "This is a simulated update demonstrating notification delivery and background checking.",
                updateUrl = "https://github.com/tonysebastine/StreamCast"
            )
            // Broadcast result to UI if listening
            broadcastUpdateResult(true, "1.5-Simulated", "Simulated updates available!")
            return
        }

        try {
            val request = Request.Builder().url(manifestUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP error code: ${response.code}"
                    Log.e(TAG, errorMsg)
                    broadcastUpdateResult(false, "", errorMsg)
                    return
                }

                val body = response.body?.string()
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
                    showUpdateNotification(serverVersionName, serverVersionCode, releaseNotes, updateUrl)
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
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
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

    private fun showUpdateNotification(
        newVersionName: String,
        newVersionCode: Int,
        releaseNotes: String,
        updateUrl: String
    ) {
        val intent = if (updateUrl.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("StreamCast Update Available (v$newVersionName)")
            .setContentText("A new update is available. Tap to upgrade now.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Version $newVersionName introduces key updates:\n$releaseNotes\n\nTap this notification to download and install."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification posted successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification: missing POST_NOTIFICATIONS permission. ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates"
            val descriptionText = "Notifications when a newer app update is available."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    private fun broadcastUpdateResult(updateAvailable: Boolean, serverVersion: String, statusMessage: String) {
        val intent = Intent(ACTION_UPDATE_RESULT).apply {
            putExtra(EXTRA_UPDATE_AVAILABLE, updateAvailable)
            putExtra(EXTRA_SERVER_VERSION, serverVersion)
            putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "UpdateCheckService destroyed")
    }

    companion object {
        const val TAG = "UpdateCheckService"

        // Actions
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_CHECK_NOW = "com.example.service.action.CHECK_NOW"

        // Broadcast Results
        const val ACTION_UPDATE_RESULT = "com.example.service.action.UPDATE_RESULT"
        const val EXTRA_UPDATE_AVAILABLE = "update_available"
        const val EXTRA_SERVER_VERSION = "server_version"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        // Notification Setup
        private const val CHANNEL_ID = "app_update_channel"
        private const val NOTIFICATION_ID = 9001

        // SharedPreferences Keys
        const val PREFS_NAME = "streamcast_updates_prefs"
        const val PREF_INTERVAL_MINUTES = "pref_check_interval_minutes"
        const val PREF_SIMULATION_MODE = "pref_simulation_mode"
        const val PREF_MANIFEST_URL = "pref_manifest_url"

        // Defaults
        const val DEFAULT_INTERVAL_MINUTES = 60L // 1 Hour
        const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/tonysebastine/StreamCast/main/app-update-manifest.json"
    }
}
