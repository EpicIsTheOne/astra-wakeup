package com.astra.wakeup.ui

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.astra.wakeup.alarm.AlarmNotifier
import com.astra.wakeup.alarm.WakeForegroundService
import com.astra.wakeup.brain.AstraBrainService
import java.io.File

object ApkUpdateInstaller {
    private const val PREFS = "astra"
    private const val KEY_DOWNLOAD_ID = "update_download_id"
    private const val KEY_DOWNLOAD_TAG = "update_download_tag"
    private const val FILE_NAME = "astra-update.apk"

    fun enqueueDownload(context: Context, asset: UpdateClient.ReleaseAsset): Long {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val target = File(downloadsDir, FILE_NAME)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("Astra update ${asset.tagName}")
            .setDescription("Downloading latest signed Astra build")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_DOWNLOAD_TAG, asset.tagName)
            .apply()
        return id
    }

    fun downloadedFile(context: Context): File {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(downloadsDir, FILE_NAME)
    }

    fun currentDownloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    fun currentDownloadedTag(context: Context): String = prefs(context).getString(KEY_DOWNLOAD_TAG, "") ?: ""

    fun clearDownloadState(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_TAG).apply()
    }

    fun installDownloadedApk(context: Context): Boolean {
        val file = downloadedFile(context)
        if (!file.exists()) return false
        prepareForInPlaceUpdate(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            if (context is Activity) {
                context.moveTaskToBack(true)
                context.finishAffinity()
            }
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun installPermissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun prepareForInPlaceUpdate(context: Context) {
        runCatching { AstraOverlayController.stopOverlay(context) }
        runCatching { WakeForegroundService.stop(context) }
        runCatching { ReminderForegroundService.stop(context) }
        runCatching { OpenClawNodeService.stop(context) }
        runCatching { context.stopService(Intent(context, ContextOrchestratorService::class.java)) }
        runCatching { context.stopService(Intent(context, AstraBrainService::class.java)) }
        runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        runCatching { AlarmNotifier.clearWakeAlarm(context) }
        runCatching { ReminderNotifier.clear(context) }
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
        prefs(context).edit().putBoolean("update_install_in_progress", true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
