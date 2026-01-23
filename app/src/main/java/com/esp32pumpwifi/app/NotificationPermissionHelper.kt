package com.esp32pumpwifi.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {
    const val REQUEST_CODE = 1001

    fun isNotificationPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun hasPermission(context: Context): Boolean {
        return !isNotificationPermissionRequired() ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissionIfNeeded(activity: Activity): Boolean {
        if (!isNotificationPermissionRequired()) {
            return true
        }

        if (hasPermission(activity)) {
            return true
        }

        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE
        )
        return false
    }
}
