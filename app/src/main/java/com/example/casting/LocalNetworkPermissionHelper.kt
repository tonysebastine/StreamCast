package com.example.casting

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.AppLogger as Log

/**
 * Helper class to encapsulate local network and nearby wifi devices permissions,
 * with special handling for Android 13+ (Tiramisu, API 33) up to SDK 36 (Android 16).
 * 
 * Handles 'neverForLocation' flag compliance by bypassing location checks/requests 
 * on supported SDK levels.
 */
object LocalNetworkPermissionHelper {
    private const val TAG = "LocalNetworkPermission"

    /**
     * Determines the primary permission required based on current system API level.
     */
    val primaryPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /**
     * Checks whether the necessary permissions are granted for discovering casting devices on local network.
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Under SDK 36 + neverForLocation, NEARBY_WIFI_DEVICES is the absolute requirement.
            // No location permission is needed or checked, honoring 'neverForLocation'.
            val status = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            val granted = status == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Checking NEARBY_WIFI_DEVICES on SDK ${Build.VERSION.SDK_INT}: granted = $granted")
            granted
        } else {
            // For older SDKs, we fallback to checking coarse/fine location, which was required for SSID/BSSID and wifi scanning.
            val status = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val granted = status == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Checking ACCESS_FINE_LOCATION on legacy SDK ${Build.VERSION.SDK_INT}: granted = $granted")
            granted
        }
    }

    /**
     * Request the appropriate permission programmatically.
     * Ensures we only request NEARBY_WIFI_DEVICES on Android 13+ to satisfy the 'neverForLocation' contract,
     * protecting user privacy without prompting for intrusive location permissions.
     */
    fun requestPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "Requesting NEARBY_WIFI_DEVICES (Never For Location) for SDK ${Build.VERSION.SDK_INT}")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                requestCode
            )
        } else {
            Log.i(TAG, "Requesting ACCESS_FINE_LOCATION fallback for legacy SDK ${Build.VERSION.SDK_INT}")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                requestCode
            )
        }
    }

    /**
     * Helper to process the permission result in Activity onRequestPermissionsResult.
     */
    fun handleRequestResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        expectedRequestCode: Int,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (requestCode == expectedRequestCode) {
            val isGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Permission request result: granted = $isGranted")
            if (isGranted) {
                onGranted()
            } else {
                onDenied()
            }
        }
    }
}
