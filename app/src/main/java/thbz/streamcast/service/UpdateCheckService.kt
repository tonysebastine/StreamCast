package thbz.streamcast.service

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
import thbz.streamcast.AppLogger as Log
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Service to periodically check for app updates by fetching a JSON manifest from a remote URL.
 * Supports manual checking, custom interval configuration, and an interactive simulation mode for QA/Testing.
 */
class UpdateCheckService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "UpdateCheckService created")
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
            }
            ACTION_CHECK_NOW -> {
                val inputData = androidx.work.Data.Builder().putBoolean("forceNotify", true).build()
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setInputData(inputData)
                    .build()
                androidx.work.WorkManager.getInstance(applicationContext).enqueue(workRequest)
            }
        }

        // Stop the service immediately to keep resources free
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startPeriodicCheck() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalMinutes = prefs.getLong(PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        val checkedInterval = intervalMinutes.coerceAtLeast(15L)

        Log.i(TAG, "Enqueuing periodic WorkManager update check every $checkedInterval minutes")

        val workRequest = androidx.work.PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            checkedInterval, java.util.concurrent.TimeUnit.MINUTES
        ).build()

        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "AppUpdateChecker",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    private fun stopPeriodicCheck() {
        Log.i(TAG, "Cancelling periodic WorkManager update check")
        androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork("AppUpdateChecker")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "UpdateCheckService destroyed")
    }

    companion object {
        const val TAG = "UpdateCheckService"

        // Actions
        const val ACTION_START = "thbz.streamcast.service.action.START"
        const val ACTION_STOP = "thbz.streamcast.service.action.STOP"
        const val ACTION_CHECK_NOW = "thbz.streamcast.service.action.CHECK_NOW"

        // Broadcast Results
        const val ACTION_UPDATE_RESULT = "thbz.streamcast.service.action.UPDATE_RESULT"
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
        const val FALLBACK_MASTER_URL = "https://raw.githubusercontent.com/tonysebastine/StreamCast/master/app-update-manifest.json"
    }
}
