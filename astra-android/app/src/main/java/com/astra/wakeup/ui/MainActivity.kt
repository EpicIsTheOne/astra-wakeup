package com.astra.wakeup.ui

import android.app.TimePickerDialog
import android.app.AppOpsManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.alarm.AlarmDiagnostics
import com.astra.wakeup.alarm.AlarmNotifier
import com.astra.wakeup.alarm.AlarmScheduler
import com.astra.wakeup.alarm.WakeForegroundService
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private fun currentVersionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

    private fun queryDownloadStatus(downloadId: Long): Pair<Int?, Int?> {
        if (downloadId <= 0L) return null to null
        val manager = getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null to null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return status to reason
        }
    }

    private lateinit var downloadCompleteReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val etApiUrl = findViewById<EditText>(R.id.etApiUrl)
        val etGatewayToken = findViewById<EditText>(R.id.etGatewayToken)
        val etBootstrapToken = findViewById<EditText>(R.id.etBootstrapToken)
        val etMediaCenterBaseUrl = findViewById<EditText>(R.id.etMediaCenterBaseUrl)
        val cbPunish = findViewById<CheckBox>(R.id.cbPunish)
        val cbInterventionsEnabled = findViewById<CheckBox>(R.id.cbInterventionsEnabled)
        val layoutGatewayAdvanced = findViewById<LinearLayout>(R.id.layoutGatewayAdvanced)
        val layoutGatewayDebug = findViewById<LinearLayout>(R.id.layoutGatewayDebug)
        val layoutWakeCard = findViewById<LinearLayout>(R.id.layoutWakeCard)
        val layoutChatCard = findViewById<LinearLayout>(R.id.layoutChatCard)
        val layoutInterventionCard = findViewById<LinearLayout>(R.id.layoutInterventionCard)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvApiStatus = findViewById<TextView>(R.id.tvApiStatus)
        val tvApiDetails = findViewById<TextView>(R.id.tvApiDetails)
        val tvConnectBanner = findViewById<TextView>(R.id.tvConnectBanner)
        val tvWakeHint = findViewById<TextView>(R.id.tvWakeHint)
        val tvChatHint = findViewById<TextView>(R.id.tvChatHint)
        val tvHealthChip = findViewById<TextView>(R.id.tvHealthChip)
        val tvLineChip = findViewById<TextView>(R.id.tvLineChip)
        val tvChatChip = findViewById<TextView>(R.id.tvChatChip)
        val tvGatewayDebug = findViewById<TextView>(R.id.tvGatewayDebug)
        val tvWakeTime = findViewById<TextView>(R.id.tvWakeTime)
        val tvNodeIdentity = findViewById<TextView>(R.id.tvNodeIdentity)
        val tvWakeMediaStatus = findViewById<TextView>(R.id.tvWakeMediaStatus)
        val tvWakeAlarmStatus = findViewById<TextView>(R.id.tvWakeAlarmStatus)
        val tvInterventionStatus = findViewById<TextView>(R.id.tvInterventionStatus)
        val tvVoiceVolume = findViewById<TextView>(R.id.tvVoiceVolume)
        val tvMusicVolume = findViewById<TextView>(R.id.tvMusicVolume)
        val tvSfxVolume = findViewById<TextView>(R.id.tvSfxVolume)
        val tvWakePlan = findViewById<TextView>(R.id.tvWakePlan)
        val tvTomorrowOverride = findViewById<TextView>(R.id.tvTomorrowOverride)
        val btnToggleAdvancedGateway = findViewById<Button>(R.id.btnToggleAdvancedGateway)
        val btnConnectGateway = findViewById<Button>(R.id.btnConnectGateway)
        val btnOpenChat = findViewById<Button>(R.id.btnOpenChat)
        val btnOpenAstraPanel = findViewById<Button>(R.id.btnOpenAstraPanel)
        val btnOpenReminders = findViewById<Button>(R.id.btnOpenReminders)
        val btnPickWakeTime = findViewById<Button>(R.id.btnPickWakeTime)
        val seekVoiceVolume = findViewById<SeekBar>(R.id.seekVoiceVolume)
        val seekMusicVolume = findViewById<SeekBar>(R.id.seekMusicVolume)
        val seekSfxVolume = findViewById<SeekBar>(R.id.seekSfxVolume)
        val btnCycleWakePlan = findViewById<Button>(R.id.btnCycleWakePlan)
        val btnTomorrowOverride = findViewById<Button>(R.id.btnTomorrowOverride)
        val btnSchedule = findViewById<Button>(R.id.btnSchedule)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnTestFullScreenAlarm = findViewById<Button>(R.id.btnTestFullScreenAlarm)
        val btnNotificationSettings = findViewById<Button>(R.id.btnNotificationSettings)
        val btnUsageAccess = findViewById<Button>(R.id.btnUsageAccess)
        val btnInterventionSettings = findViewById<Button>(R.id.btnInterventionSettings)
        val tvUpdateStatus = findViewById<TextView>(R.id.tvUpdateStatus)
        val tvUpdateHint = findViewById<TextView>(R.id.tvUpdateHint)
        val tvUpdateVersions = findViewById<TextView>(R.id.tvUpdateVersions)
        val cbAutoUpdate = findViewById<CheckBox>(R.id.cbAutoUpdate)
        val btnCheckUpdates = findViewById<Button>(R.id.btnCheckUpdates)
        val btnDownloadUpdate = findViewById<Button>(R.id.btnDownloadUpdate)
        val btnInstallUpdate = findViewById<Button>(R.id.btnInstallUpdate)

        val defaultExternalUrl = "http://72.60.29.204:18789"
        val savedApiUrl = prefs.getString("api_url", null)?.takeIf { it.isNotBlank() }
        etApiUrl.setText(savedApiUrl ?: defaultExternalUrl)
        etGatewayToken.setText(prefs.getString("gateway_token", "") ?: "")
        etBootstrapToken.setText(prefs.getString("gateway_bootstrap_token", "") ?: "")
        etMediaCenterBaseUrl.setText(prefs.getString("media_center_base_url", MediaCenterClient.baseUrl(this)) ?: MediaCenterClient.baseUrl(this))

        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = "version ${pkgInfo.versionName}"

        cbPunish.isChecked = prefs.getBoolean("punish", true)
        cbInterventionsEnabled.isChecked = InterventionRepository(this).getState().enabled
        cbAutoUpdate.isChecked = prefs.getBoolean("updater_auto_check", true)

        var wakeHour = prefs.getInt("wake_hour", 5)
        var wakeMinute = prefs.getInt("wake_minute", 50)
        var voiceVolumeProgress = prefs.getInt("wake_voice_volume", 70)
        var musicVolumeProgress = prefs.getInt("wake_music_volume", 35)
        var sfxVolumeProgress = prefs.getInt("wake_sfx_volume", 90)
        var selectedWakePlanId = prefs.getString("wake_default_plan", "workday") ?: "workday"

        fun formatWakeTime(hour: Int, minute: Int): String {
            val time = LocalTime.of(hour, minute)
            return time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        }

        var latestRelease: UpdateClient.ReleaseAsset? = null

        fun refreshUpdateVersionLine() {
            val installed = currentVersionName()
            val latest = latestRelease?.tagName ?: "checking…"
            tvUpdateVersions.text = "Installed: $installed | Latest: $latest"
        }

        fun setUpdateButtonsEnabled(enabled: Boolean) {
            btnCheckUpdates.isEnabled = enabled
            btnDownloadUpdate.isEnabled = enabled
        }

        fun refreshDownloadedUpdateState() {
            val file = ApkUpdateInstaller.downloadedFile(this)
            val tag = ApkUpdateInstaller.currentDownloadedTag(this)
            btnInstallUpdate.visibility = if (file.exists()) View.VISIBLE else View.GONE
            btnInstallUpdate.isEnabled = file.exists()
            if (file.exists() && tag.isNotBlank() && tvUpdateStatus.text.toString() == "Updater idle") {
                tvUpdateStatus.text = "Downloaded update ready"
                tvUpdateHint.text = "Astra $tag is already downloaded. Tap Install downloaded update when you're ready."
            }
        }

        fun beginInstallDownloadedUpdate() {
            val file = ApkUpdateInstaller.downloadedFile(this)
            if (!file.exists()) {
                Toast.makeText(this, "No downloaded update yet", Toast.LENGTH_SHORT).show()
                refreshDownloadedUpdateState()
                return
            }
            if (!ApkUpdateInstaller.canRequestInstalls(this)) {
                tvUpdateStatus.text = "Installer permission needed"
                tvUpdateHint.text = "Allow Astra to install unknown apps, then tap Install downloaded update again."
                startActivity(ApkUpdateInstaller.installPermissionIntent(this))
                return
            }
            val ok = ApkUpdateInstaller.installDownloadedApk(this)
            if (!ok) {
                Toast.makeText(this, "Couldn't open the Android installer", Toast.LENGTH_SHORT).show()
            }
        }

        fun downloadRelease(asset: UpdateClient.ReleaseAsset, autoTriggered: Boolean = false) {
            latestRelease = asset
            refreshUpdateVersionLine()
            val existing = ApkUpdateInstaller.downloadedFile(this)
            if (existing.exists()) existing.delete()
            val id = ApkUpdateInstaller.enqueueDownload(this, asset)
            btnInstallUpdate.visibility = View.GONE
            tvUpdateStatus.text = if (autoTriggered) "Auto-downloading ${asset.tagName}" else "Downloading ${asset.tagName}"
            tvUpdateHint.text = "Astra is downloading the signed APK now. Android will still ask you to confirm install after download."
            Toast.makeText(this, "Downloading ${asset.tagName}", Toast.LENGTH_SHORT).show()
        }

        fun checkForUpdates(autoTriggered: Boolean = false) {
            setUpdateButtonsEnabled(false)
            if (!autoTriggered) {
                tvUpdateStatus.text = "Checking for updates…"
                tvUpdateHint.text = "Looking for the newest signed Astra release on GitHub."
            }
            Thread {
                val result = UpdateClient.fetchLatestSignedRelease()
                runOnUiThread {
                    setUpdateButtonsEnabled(true)
                    result.onSuccess { asset ->
                        latestRelease = asset
                        refreshUpdateVersionLine()
                        val newer = UpdateClient.isNewerRelease(currentVersionName(), asset.tagName)
                        val downloadedTag = ApkUpdateInstaller.currentDownloadedTag(this)
                        val downloadedFileExists = ApkUpdateInstaller.downloadedFile(this).exists()
                        when {
                            newer && cbAutoUpdate.isChecked && (!downloadedFileExists || downloadedTag != asset.tagName) -> {
                                tvUpdateStatus.text = "New version found"
                                tvUpdateHint.text = "Astra ${asset.tagName} is newer than ${currentVersionName()}. Auto-download is on, so I'm grabbing it now."
                                downloadRelease(asset, autoTriggered = true)
                            }
                            newer -> {
                                tvUpdateStatus.text = "Update available"
                                tvUpdateHint.text = "Astra ${asset.tagName} is ready. Tap Download latest signed build, then install it."
                            }
                            downloadedFileExists -> {
                                tvUpdateStatus.text = "Downloaded update ready"
                                tvUpdateHint.text = "You already downloaded ${downloadedTag.ifBlank { asset.tagName }}. Tap Install downloaded update when you're ready."
                            }
                            else -> {
                                tvUpdateStatus.text = "You're up to date"
                                tvUpdateHint.text = "Current app version ${currentVersionName()} already matches the newest signed Astra release I found (${asset.tagName})."
                                if (!autoTriggered) Toast.makeText(this, "Astra is already up to date", Toast.LENGTH_SHORT).show()
                            }
                        }
                        refreshDownloadedUpdateState()
                    }.onFailure { err ->
                        if (!autoTriggered) {
                            tvUpdateStatus.text = "Update check failed"
                            tvUpdateHint.text = err.message ?: "Couldn't contact GitHub releases right now."
                            Toast.makeText(this, "Update check failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }

        downloadCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val expectedId = ApkUpdateInstaller.currentDownloadId(this@MainActivity)
                if (completedId != expectedId || completedId <= 0L) return
                val (status, reason) = queryDownloadStatus(completedId)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val tag = ApkUpdateInstaller.currentDownloadedTag(this@MainActivity)
                    tvUpdateStatus.text = "Update downloaded"
                    tvUpdateHint.text = "Astra ${tag.ifBlank { "update" }} is ready. Tap Install downloaded update to let Android finish the upgrade."
                    refreshDownloadedUpdateState()
                    beginInstallDownloadedUpdate()
                } else {
                    tvUpdateStatus.text = "Download failed"
                    tvUpdateHint.text = "Android couldn't finish downloading the update (reason $reason). Try again on a steadier connection."
                    ApkUpdateInstaller.clearDownloadState(this@MainActivity)
                    refreshDownloadedUpdateState()
                }
            }
        }

        fun updateWakeTimeUi() {
            val formatted = formatWakeTime(wakeHour, wakeMinute)
            tvWakeTime.text = "Wake time: $formatted (America/New_York)"
            btnSchedule.text = "Schedule $formatted wake"
        }

        fun updateVolumeUi() {
            tvVoiceVolume.text = "Astra voice volume: ${voiceVolumeProgress}%"
            tvMusicVolume.text = "Wake music volume: ${musicVolumeProgress}%"
            tvSfxVolume.text = "Sound effects volume: ${sfxVolumeProgress}%"
            seekVoiceVolume.progress = voiceVolumeProgress
            seekMusicVolume.progress = musicVolumeProgress
            seekSfxVolume.progress = sfxVolumeProgress
        }

        fun refreshWakePlanUi() {
            val profile = WakeProfiles.byId(selectedWakePlanId)
            tvWakePlan.text = "Wake plan: ${profile.title} — ${profile.description}"
            tvTomorrowOverride.text = WakeProfiles.tomorrowOverrideLabel(this)
            btnCycleWakePlan.text = "Change wake plan (${profile.title})"
        }

        fun refreshWakeAlarmStatus() {
            val wakeEnabled = prefs.getBoolean("wake_enabled", false)
            val canExact = AlarmScheduler.canScheduleExactAlarms(this)
            val lastFiredAt = prefs.getLong("last_alarm_receiver_fired_at", 0L)
            val notificationsEnabled = AlarmDiagnostics.notificationsEnabled(this)
            val fullScreenAllowed = AlarmDiagnostics.fullScreenIntentLikelyAllowed(this)
            val wakeImportance = AlarmDiagnostics.wakeChannelImportance(this)
            val wakeSessionImportance = AlarmDiagnostics.wakeSessionChannelImportance(this)
            tvWakeAlarmStatus.text = buildString {
                append("Wake alarm: ")
                append(if (wakeEnabled) "scheduled" else "not scheduled")
                append(" | exact alarms=")
                append(if (canExact) "allowed" else "needs permission")
                append(" | notifications=")
                append(if (notificationsEnabled) "on" else "off")
                append(" | fullscreen=")
                append(if (fullScreenAllowed) "allowed" else "blocked")
                if (wakeImportance != null) {
                    append(" | wakeChannel=")
                    append(wakeImportance)
                }
                if (wakeSessionImportance != null) {
                    append(" | sessionChannel=")
                    append(wakeSessionImportance)
                }
                if (lastFiredAt > 0L) {
                    append(" | last trigger=")
                    append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastFiredAt)))
                }
            }
        }

        fun setAdvancedVisible(visible: Boolean) {
            layoutGatewayAdvanced.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            layoutGatewayDebug.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            btnToggleAdvancedGateway.text = if (visible) {
                "Hide advanced gateway options"
            } else {
                "Show advanced gateway options"
            }
        }

        fun refreshGatewayDebug(lastError: String? = null) {
            val config = OpenClawGatewayConfig.fromContext(this)
            val identity = OpenClawGatewayCrypto.identityDebugJson(this)
            val summary = OpenClawGatewayAuthStore.authDebugSummary(this)
            val issue = OpenClawGatewayDiagnostics.classify(lastError)
            val issueText = issue?.let { " | issue=${it.summary}" }.orEmpty()
            tvNodeIdentity.text = buildString {
                append("nodeId=")
                append(NodeIdentity.getStableNodeId(this@MainActivity))
                append("\ninstanceId=")
                append(NodeIdentity.getNodeInstanceId(this@MainActivity))
            }
            tvGatewayDebug.text = buildString {
                append("gateway: ws=")
                append(if (config.wsUrl.isBlank()) "(unset)" else config.wsUrl)
                append("\n")
                append("auth: ")
                append(summary)
                append("\n")
                append("deviceId=")
                append(identity.optString("deviceId").ifBlank { "(missing)" })
                append(" alg=")
                append(identity.optString("algorithm").ifBlank { "?" })
                append(issueText)
                if (!lastError.isNullOrBlank()) {
                    append("\nlastError=")
                    append(lastError.take(180))
                }
            }
        }

        fun saveMainSettings() {
            val apiUrl = etApiUrl.text.toString().trim()
            val gatewayToken = etGatewayToken.text.toString().trim()
            val bootstrapToken = etBootstrapToken.text.toString().trim()
            val mediaCenterBaseUrl = etMediaCenterBaseUrl.text.toString().trim()
            prefs.edit()
                .putString("api_url", apiUrl)
                .putString("gateway_token", gatewayToken)
                .putString("gateway_bootstrap_token", bootstrapToken)
                .putString("media_center_base_url", mediaCenterBaseUrl)
                .putBoolean("punish", cbPunish.isChecked)
                .putInt("wake_hour", wakeHour)
                .putInt("wake_minute", wakeMinute)
                .putInt("wake_voice_volume", voiceVolumeProgress)
                .putInt("wake_music_volume", musicVolumeProgress)
                .putInt("wake_sfx_volume", sfxVolumeProgress)
                .putString("wake_default_plan", selectedWakePlanId)
                .apply()
        }

        fun refreshWakeMediaStatus() {
            tvWakeMediaStatus.text = "Wake media: checking Media Center…"
            Thread {
                val result = runCatching { MediaCenterClient.fetchWakeAssets(this) }
                runOnUiThread {
                    val assets = result.getOrNull()
                    tvWakeMediaStatus.text = when {
                        assets == null -> "Wake media: couldn't load Media Center assets"
                        assets.isEmpty() -> "Wake media: no wake-ready sound effects or music yet"
                        else -> "Wake media: ${assets.size} wake-ready assets available"
                    }
                }
            }.start()
        }

        fun showConnectBanner(message: String? = null, backgroundColor: String = "#1E293B", textColor: String = "#E2E8F0") {
            if (message.isNullOrBlank()) {
                tvConnectBanner.visibility = android.view.View.GONE
                tvConnectBanner.text = ""
                return
            }
            tvConnectBanner.visibility = android.view.View.VISIBLE
            tvConnectBanner.text = message
            tvConnectBanner.setBackgroundColor(android.graphics.Color.parseColor(backgroundColor))
            tvConnectBanner.setTextColor(android.graphics.Color.parseColor(textColor))
        }

        fun setConnectedState(connected: Boolean) {
            prefs.edit().putBoolean("gateway_connected", connected).apply()
        }

        fun isConnectedState(): Boolean = prefs.getBoolean("gateway_connected", false)

        fun refreshSecondaryCards() {
            val connected = isConnectedState()
            val wakeAlpha = if (connected) 1.0f else 0.62f
            val chatAlpha = if (connected) 1.0f else 0.62f
            val interventionAlpha = if (connected) 1.0f else 0.62f
            layoutWakeCard.alpha = wakeAlpha
            layoutChatCard.alpha = chatAlpha
            layoutInterventionCard.alpha = interventionAlpha

            cbPunish.isEnabled = connected
            cbInterventionsEnabled.isEnabled = connected
            btnPickWakeTime.isEnabled = connected
            seekVoiceVolume.isEnabled = connected
            seekMusicVolume.isEnabled = connected
            seekSfxVolume.isEnabled = connected
            btnCycleWakePlan.isEnabled = connected
            btnTomorrowOverride.isEnabled = connected
            btnSchedule.isEnabled = connected
            btnTest.isEnabled = connected
            btnTestFullScreenAlarm.isEnabled = connected
            btnNotificationSettings.isEnabled = connected
            btnOpenChat.isEnabled = connected
            btnOpenAstraPanel.isEnabled = connected
            btnOpenReminders.isEnabled = connected
            btnUsageAccess.isEnabled = connected
            btnInterventionSettings.isEnabled = connected
            btnOpenChat.text = if (connected) "Open Chat" else "Open Chat (connect first)"
            btnOpenAstraPanel.text = if (connected) "Open Astra Panel" else "Open Astra Panel (connect first)"
            btnOpenReminders.text = if (connected) "Open Reminders + Task Board" else "Open Reminders + Task Board (connect first)"
            tvWakeHint.text = if (connected) {
                "Pick any wake time you want. Astra will keep trying to wake you up until you tap I'm awake, Talk back only listens when you press it, and wake-ready Media Center assets can be used for audio choices."
            } else {
                "Connect this phone to enable wake controls."
            }
            tvChatHint.text = if (connected) {
                "Open chat, or launch the new Astra panel for a Gemini-style bottom sheet with auto-listen and TTS replies."
            } else {
                "Connect this phone first, then open your Astra chat."
            }
        }

        fun hasUsageAccess(): Boolean {
            val appOps = getSystemService(AppOpsManager::class.java)
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun refreshInterventionStatus() {
            val state = InterventionRepository(this).getState()
            val tracked = state.trackedApps.filter { it.enabled }
            tvInterventionStatus.text = buildString {
                append("Intervention status: ")
                append(if (state.enabled) "enabled" else "disabled")
                append(" | usage access=")
                append(if (hasUsageAccess()) "granted" else "missing")
                append(" | tracked=")
                append(if (tracked.isEmpty()) "none" else tracked.joinToString { it.label })
            }
        }

        fun setConnectBusy(isBusy: Boolean, buttonLabel: String = "Connect this phone") {
            btnConnectGateway.isEnabled = !isBusy
            btnConnectGateway.text = buttonLabel
            etApiUrl.isEnabled = !isBusy
            etGatewayToken.isEnabled = !isBusy
        }

        fun applyConnectionVisualState(
            title: String,
            details: String,
            banner: String? = null,
            bannerBackground: String = "#1E293B",
            bannerText: String = "#E2E8F0"
        ) {
            tvApiStatus.text = title
            tvApiDetails.text = details
            showConnectBanner(banner, bannerBackground, bannerText)
        }

        fun runStatusCheck() {
            val apiUrl = etApiUrl.text.toString().trim()
            if (apiUrl.isBlank()) {
                setConnectedState(false)
                refreshSecondaryCards()
                applyConnectionVisualState(
                    title = "This phone is not connected yet",
                    details = "Enter your OpenClaw URL and shared gateway token, then tap Connect this phone.",
                    banner = null
                )
                return
            }
            applyConnectionVisualState(
                title = "Checking connection status…",
                details = "Making sure OpenClaw is reachable before we unlock the rest of the app.",
                banner = "Checking OpenClaw connection…",
                bannerBackground = "#1E293B",
                bannerText = "#E2E8F0"
            )
            tvHealthChip.text = "Gateway status: checking"
            tvLineChip.text = "Connection state: checking"
            tvChatChip.text = "Chat state: checking"
            Thread {
                val suite = ApiStatusClient.checkSuite(this, apiUrl)
                runOnUiThread {
                    setConnectedState(suite.chatOk)
                    refreshSecondaryCards()
                    tvHealthChip.text = "Gateway status: ${if (suite.healthOk) "reachable" else "needs attention"}"
                    tvLineChip.text = "Connection state: ${if (suite.lineOk) "ready" else "not ready"}"
                    tvChatChip.text = "Chat state: ${if (suite.chatOk) "ready" else "locked"}"
                    if (suite.chatOk) {
                        applyConnectionVisualState(
                            title = "Phone connected",
                            details = "Chat and wake controls are ready.",
                            banner = "Connected. This phone is ready to use.",
                            bannerBackground = "#14532D",
                            bannerText = "#DCFCE7"
                        )
                    } else {
                        applyConnectionVisualState(
                            title = "This phone needs attention",
                            details = suite.details,
                            banner = null
                        )
                    }
                    refreshGatewayDebug(if (suite.chatOk) null else suite.details)
                }
            }.start()
        }

        fun connectThisPhone() {
            saveMainSettings()
            val apiUrl = etApiUrl.text.toString().trim()
            val gatewayToken = etGatewayToken.text.toString().trim()

            if (apiUrl.isBlank()) {
                setConnectedState(false)
                refreshSecondaryCards()
                applyConnectionVisualState(
                    title = "Add your OpenClaw URL",
                    details = "Enter your OpenClaw URL, then tap Connect this phone.",
                    banner = "OpenClaw URL is required.",
                    bannerBackground = "#7C2D12",
                    bannerText = "#FFEDD5"
                )
                return
            }

            if (gatewayToken.isBlank()) {
                setConnectedState(false)
                refreshSecondaryCards()
                applyConnectionVisualState(
                    title = "Add your gateway token",
                    details = "Enter the shared gateway token for this OpenClaw instance, then tap Connect this phone.",
                    banner = "Gateway token is required for the supported flow right now.",
                    bannerBackground = "#7C2D12",
                    bannerText = "#FFEDD5"
                )
                return
            }

            setConnectBusy(true, "Connecting…")
            applyConnectionVisualState(
                title = "Connecting this phone…",
                details = "Starting secure handshake with OpenClaw.",
                banner = "Connecting this phone to OpenClaw…",
                bannerBackground = "#1E293B",
                bannerText = "#E2E8F0"
            )
            tvHealthChip.text = "Gateway status: checking"
            tvLineChip.text = "Connection state: connecting"
            tvChatChip.text = "Chat state: locked"

            Thread {
                val result = OpenClawChatClient().probe(this)
                runOnUiThread {
                    setConnectBusy(false)
                    result.onSuccess { session ->
                        val server = session.helloPayload.optJSONObject("server")
                        val version = server?.optString("version").orEmpty().ifBlank { "unknown" }
                        val connId = server?.optString("connId").orEmpty().ifBlank { "?" }
                        saveMainSettings()
                        setConnectedState(true)
                        refreshSecondaryCards()
                        applyConnectionVisualState(
                            title = "Phone connected",
                            details = "Chat and wake controls are ready. serverVersion=$version, connId=$connId",
                            banner = "Connected. Chat and wake controls are unlocked.",
                            bannerBackground = "#14532D",
                            bannerText = "#DCFCE7"
                        )
                        tvHealthChip.text = "Gateway status: reachable"
                        tvLineChip.text = "Connection state: ready"
                        tvChatChip.text = "Chat state: ready"
                        OpenClawNodeService.start(this@MainActivity)
                        refreshWakeMediaStatus()
                        refreshGatewayDebug()
                    }.onFailure { err ->
                        val msg = err.message ?: "connect failed"
                        val issue = OpenClawGatewayDiagnostics.classify(msg)
                        setConnectedState(false)
                        refreshSecondaryCards()
                        applyConnectionVisualState(
                            title = when (issue?.code) {
                                "AUTH_TOKEN_MISSING" -> "Gateway token missing"
                                "AUTH_TOKEN_MISMATCH" -> "Gateway token mismatch"
                                else -> "Connection failed"
                            },
                            details = OpenClawGatewayDiagnostics.describeStatus(this, msg),
                            banner = when (issue?.code) {
                                "AUTH_TOKEN_MISSING" -> "Add the shared gateway token, then try again."
                                "AUTH_TOKEN_MISMATCH" -> "The saved gateway token does not match OpenClaw."
                                else -> "Check the URL or gateway token and try again."
                            },
                            bannerBackground = "#7F1D1D",
                            bannerText = "#FEE2E2"
                        )
                        tvHealthChip.text = "Gateway status: needs attention"
                        tvLineChip.text = "Connection state: failed"
                        tvChatChip.text = "Chat state: locked"
                        refreshGatewayDebug(msg)
                    }
                }
            }.start()
        }

        updateWakeTimeUi()
        updateVolumeUi()
        refreshWakePlanUi()
        setAdvancedVisible(false)
        refreshGatewayDebug()
        refreshSecondaryCards()
        refreshWakeMediaStatus()
        refreshWakeAlarmStatus()
        refreshInterventionStatus()
        if (isConnectedState()) OpenClawNodeService.start(this)
        if (isConnectedState() && InterventionRepository(this).getState().enabled) {
            startService(Intent(this, ContextOrchestratorService::class.java))
        }
        AlarmNotifier.showWakeAlarm(this)
        AlarmNotifier.clearWakeAlarm(this)
        refreshUpdateVersionLine()
        refreshDownloadedUpdateState()
        registerReceiver(downloadCompleteReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        if (cbAutoUpdate.isChecked) checkForUpdates(autoTriggered = true)
        runStatusCheck()

        btnToggleAdvancedGateway.setOnClickListener {
            setAdvancedVisible(layoutGatewayAdvanced.visibility != android.view.View.VISIBLE)
        }

        btnConnectGateway.setOnClickListener {
            connectThisPhone()
        }

        btnPickWakeTime.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TimePickerDialog(this, { _, hourOfDay, minute ->
                wakeHour = hourOfDay
                wakeMinute = minute
                saveMainSettings()
                updateWakeTimeUi()
            }, wakeHour, wakeMinute, false).show()
        }

        val volumeSliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                when (seekBar?.id) {
                    R.id.seekVoiceVolume -> voiceVolumeProgress = progress
                    R.id.seekMusicVolume -> musicVolumeProgress = progress
                    R.id.seekSfxVolume -> sfxVolumeProgress = progress
                }
                updateVolumeUi()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveMainSettings()
            }
        }
        seekVoiceVolume.setOnSeekBarChangeListener(volumeSliderListener)
        seekMusicVolume.setOnSeekBarChangeListener(volumeSliderListener)
        seekSfxVolume.setOnSeekBarChangeListener(volumeSliderListener)

        btnCycleWakePlan.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedWakePlanId = WakeProfiles.nextProfileId(selectedWakePlanId)
            WakeProfiles.applyProfileDefaults(this, selectedWakePlanId)
            voiceVolumeProgress = prefs.getInt("wake_voice_volume", voiceVolumeProgress)
            musicVolumeProgress = prefs.getInt("wake_music_volume", musicVolumeProgress)
            sfxVolumeProgress = prefs.getInt("wake_sfx_volume", sfxVolumeProgress)
            cbPunish.isChecked = prefs.getBoolean("punish", cbPunish.isChecked)
            updateVolumeUi()
            refreshWakePlanUi()
            refreshWakeAlarmStatus()
            Toast.makeText(this, "Wake plan: ${WakeProfiles.byId(selectedWakePlanId).title}", Toast.LENGTH_SHORT).show()
        }

        btnTomorrowOverride.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = prefs.getString("wake_tomorrow_override_plan", null)
            val next = if (current == selectedWakePlanId) null else selectedWakePlanId
            WakeProfiles.setTomorrowOverride(this, next)
            refreshWakePlanUi()
            Toast.makeText(this, if (next == null) "Tomorrow override cleared" else "Tomorrow override set to ${WakeProfiles.byId(next).title}", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveMainSettings()
            refreshWakeMediaStatus()
            refreshWakePlanUi()
            refreshWakeAlarmStatus()
            refreshInterventionStatus()
            refreshGatewayDebug()
            Toast.makeText(this, "Saved settings", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnProbeGateway).setOnClickListener {
            connectThisPhone()
        }

        findViewById<Button>(R.id.btnClearDeviceToken).setOnClickListener {
            OpenClawGatewayAuthStore.clearDeviceToken(this)
            refreshGatewayDebug("device token cleared")
            Toast.makeText(this, "Cleared cached device token", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearGatewayAuth).setOnClickListener {
            OpenClawNodeService.stop(this)
            OpenClawGatewayAuthStore.clearAllGatewayAuth(this)
            etGatewayToken.setText("")
            etBootstrapToken.setText("")
            setConnectedState(false)
            refreshSecondaryCards()
            refreshGatewayDebug("all gateway auth cleared")
            applyConnectionVisualState(
                title = "This phone is not connected yet",
                details = "Enter your OpenClaw URL and shared gateway token, then tap Connect this phone.",
                banner = "Cleared saved gateway auth.",
                bannerBackground = "#1E293B",
                bannerText = "#E2E8F0"
            )
            Toast.makeText(this, "Cleared shared/bootstrap/device auth", Toast.LENGTH_SHORT).show()
        }

        btnSchedule.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveMainSettings()
            if (!AlarmScheduler.canScheduleExactAlarms(this)) {
                prefs.edit().putBoolean("wake_enabled", false).apply()
                refreshWakeAlarmStatus()
                Toast.makeText(this, "Allow exact alarms for Astra, then schedule again", Toast.LENGTH_LONG).show()
                startActivity(AlarmScheduler.exactAlarmSettingsIntent(this))
                return@setOnClickListener
            }
            val scheduled = AlarmScheduler.scheduleDaily(this, wakeHour, wakeMinute)
            prefs.edit().putBoolean("wake_enabled", scheduled).apply()
            refreshWakeAlarmStatus()
            if (scheduled) {
                Toast.makeText(this, "Scheduled for ${formatWakeTime(wakeHour, wakeMinute)} ET", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Alarm permission missing", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveMainSettings()
            startActivity(Intent(this, WakeActivity::class.java))
        }

        btnTestFullScreenAlarm.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveMainSettings()
            prefs.edit().putBoolean("wake_enabled", true).apply()
            refreshWakeAlarmStatus()
            WakeForegroundService.start(this)
            Toast.makeText(this, "Triggered full-screen alarm test", Toast.LENGTH_SHORT).show()
        }

        btnNotificationSettings.setOnClickListener {
            startActivity(AlarmDiagnostics.fullScreenIntentSettingsIntent(this))
        }

        btnOpenChat.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                applyConnectionVisualState(
                    title = "Connect before opening chat",
                    details = "Finish connecting this phone so chat has a real OpenClaw session to use.",
                    banner = "Chat stays locked until this phone connects.",
                    bannerBackground = "#7C2D12",
                    bannerText = "#FFEDD5"
                )
                return@setOnClickListener
            }
            startActivity(Intent(this, ChatActivity::class.java))
        }

        btnOpenAstraPanel.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                applyConnectionVisualState(
                    title = "Connect before opening Astra panel",
                    details = "Finish connecting this phone so the Astra panel has a real OpenClaw session to use.",
                    banner = "The Astra panel stays locked until this phone connects.",
                    bannerBackground = "#7C2D12",
                    bannerText = "#FFEDD5"
                )
                return@setOnClickListener
            }
            startActivity(Intent(this, AstraOverlayActivity::class.java))
        }

        btnOpenReminders.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, RemindersActivity::class.java))
        }

        cbInterventionsEnabled.setOnCheckedChangeListener { _, isChecked ->
            val repo = InterventionRepository(this)
            val state = repo.getState()
            repo.saveState(state.copy(enabled = isChecked))
            refreshInterventionStatus()
            if (isChecked) {
                startService(Intent(this, ContextOrchestratorService::class.java))
            }
        }

        btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnInterventionSettings.setOnClickListener {
            startActivity(Intent(this, ContextActivity::class.java))
        }

        cbAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("updater_auto_check", isChecked).apply()
        }

        btnCheckUpdates.setOnClickListener {
            checkForUpdates(autoTriggered = false)
        }

        btnDownloadUpdate.setOnClickListener {
            val asset = latestRelease
            if (asset == null) {
                checkForUpdates(autoTriggered = false)
                Toast.makeText(this, "Checking for the newest Astra build first", Toast.LENGTH_SHORT).show()
            } else {
                downloadRelease(asset)
            }
        }

        btnInstallUpdate.setOnClickListener {
            beginInstallDownloadedUpdate()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
        super.onDestroy()
    }
}
