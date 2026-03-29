package com.astra.wakeup

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.astra.wakeup.alarm.AlarmNotifier
import com.astra.wakeup.alarm.WakeForegroundService
import com.astra.wakeup.brain.AstraBrainService
import com.astra.wakeup.ui.ApkUpdateInstaller
import com.astra.wakeup.ui.AstraOverlayController
import com.astra.wakeup.ui.AstraOverlayService
import com.astra.wakeup.ui.CallForegroundService
import com.astra.wakeup.ui.ContextOrchestratorService
import com.astra.wakeup.ui.OpenClawGatewayAuthStore
import com.astra.wakeup.ui.OpenClawNodeService
import com.astra.wakeup.ui.ReminderForegroundService
import com.astra.wakeup.ui.ReminderNotifier

object AppUpgradeManager {
    private const val PREFS = "astra"
    private const val KEY_LAST_LAUNCHED_VERSION = "last_launched_version"

    fun runStartupMaintenance(context: Context) {
        val prefs = prefs(context)
        val currentVersion = currentVersionName(context)
        val previousVersion = prefs.getString(KEY_LAST_LAUNCHED_VERSION, null)
        val installInProgress = prefs.getBoolean("update_install_in_progress", false)

        if (installInProgress || (previousVersion != null && previousVersion != currentVersion)) {
            Log.i("AppUpgradeManager", "Running upgrade cleanup from ${previousVersion ?: "(first-launch)"} to $currentVersion")
            repairRuntimeState(context, clearOverlayConversation = false, clearLegacyGatewayAuth = true)
        }

        prefs.edit()
            .putString(KEY_LAST_LAUNCHED_VERSION, currentVersion)
            .putBoolean("update_install_in_progress", false)
            .apply()
    }

    fun repairRuntimeState(
        context: Context,
        clearOverlayConversation: Boolean = false,
        clearLegacyGatewayAuth: Boolean = false
    ) {
        stopRuntimeServices(context)
        clearTransientPrefs(context, clearOverlayConversation, clearLegacyGatewayAuth)
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
    }

    private fun stopRuntimeServices(context: Context) {
        runCatching { AstraOverlayController.stopOverlay(context) }
        runCatching { context.stopService(Intent(context, AstraOverlayService::class.java)) }
        runCatching { WakeForegroundService.stop(context) }
        runCatching { ReminderForegroundService.stop(context) }
        runCatching { OpenClawNodeService.stop(context) }
        runCatching { context.stopService(Intent(context, ContextOrchestratorService::class.java)) }
        runCatching { context.stopService(Intent(context, AstraBrainService::class.java)) }
        runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        runCatching { AlarmNotifier.clearWakeAlarm(context) }
        runCatching { ReminderNotifier.clear(context) }
    }

    private fun clearTransientPrefs(context: Context, clearOverlayConversation: Boolean, clearLegacyGatewayAuth: Boolean) {
        val prefs = prefs(context)
        prefs.edit().apply {
            putBoolean("gateway_connected", false)
            putBoolean("gateway_auto_connect", false)
            putBoolean("update_install_in_progress", false)
            remove("update_download_id")
            remove("update_download_tag")
            if (clearOverlayConversation) {
                remove("astra_overlay_conversation")
            }
            if (clearLegacyGatewayAuth) {
                remove("gateway_token")
                remove("gateway_bootstrap_token")
            }
        }.apply()
        runCatching { ApkUpdateInstaller.clearDownloadState(context) }
        if (clearLegacyGatewayAuth) {
            runCatching { OpenClawGatewayAuthStore.clearAllGatewayAuth(context) }
        }
    }

    private fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
