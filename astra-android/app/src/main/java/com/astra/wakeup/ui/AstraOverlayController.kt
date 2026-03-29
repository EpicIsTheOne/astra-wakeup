package com.astra.wakeup.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object AstraOverlayController {
    private const val PREFS_NAME = "astra"
    private const val PREF_OVERLAY_ENABLED = "overlay_enabled"

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_OVERLAY_ENABLED, true)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_OVERLAY_ENABLED, enabled)
            .apply()
        if (!enabled) {
            stopOverlay(context)
        }
    }

    fun overlayPermissionIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun startOverlay(context: Context) {
        if (!isOverlayEnabled(context)) return
        ContextCompat.startForegroundService(context, Intent(context, AstraOverlayService::class.java))
    }

    fun stopOverlay(context: Context) {
        val stopIntent = Intent(context, AstraOverlayService::class.java).apply {
            action = AstraOverlayService.ACTION_STOP
        }
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, AstraOverlayService::class.java))
    }
}
