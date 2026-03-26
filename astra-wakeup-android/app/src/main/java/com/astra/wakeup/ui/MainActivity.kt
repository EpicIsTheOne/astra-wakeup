package com.astra.wakeup.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.alarm.AlarmScheduler
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val etApiUrl = findViewById<EditText>(R.id.etApiUrl)
        val etGatewayToken = findViewById<EditText>(R.id.etGatewayToken)
        val etBootstrapToken = findViewById<EditText>(R.id.etBootstrapToken)
        val etMediaCenterBaseUrl = findViewById<EditText>(R.id.etMediaCenterBaseUrl)
        val cbPunish = findViewById<CheckBox>(R.id.cbPunish)
        val layoutGatewayAdvanced = findViewById<LinearLayout>(R.id.layoutGatewayAdvanced)
        val layoutGatewayDebug = findViewById<LinearLayout>(R.id.layoutGatewayDebug)
        val layoutWakeCard = findViewById<LinearLayout>(R.id.layoutWakeCard)
        val layoutChatCard = findViewById<LinearLayout>(R.id.layoutChatCard)
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
        val btnToggleAdvancedGateway = findViewById<Button>(R.id.btnToggleAdvancedGateway)
        val btnConnectGateway = findViewById<Button>(R.id.btnConnectGateway)
        val btnOpenChat = findViewById<Button>(R.id.btnOpenChat)
        val btnPickWakeTime = findViewById<Button>(R.id.btnPickWakeTime)
        val btnSchedule = findViewById<Button>(R.id.btnSchedule)
        val btnTest = findViewById<Button>(R.id.btnTest)

        val defaultExternalUrl = "http://72.60.29.204:18789"
        val savedApiUrl = prefs.getString("api_url", null)?.takeIf { it.isNotBlank() }
        etApiUrl.setText(savedApiUrl ?: defaultExternalUrl)
        etGatewayToken.setText(prefs.getString("gateway_token", "") ?: "")
        etBootstrapToken.setText(prefs.getString("gateway_bootstrap_token", "") ?: "")
        etMediaCenterBaseUrl.setText(prefs.getString("media_center_base_url", MediaCenterClient.baseUrl(this)) ?: MediaCenterClient.baseUrl(this))

        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = "version ${pkgInfo.versionName}"

        cbPunish.isChecked = prefs.getBoolean("punish", true)

        var wakeHour = prefs.getInt("wake_hour", 5)
        var wakeMinute = prefs.getInt("wake_minute", 50)

        fun formatWakeTime(hour: Int, minute: Int): String {
            val time = LocalTime.of(hour, minute)
            return time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        }

        fun updateWakeTimeUi() {
            val formatted = formatWakeTime(wakeHour, wakeMinute)
            tvWakeTime.text = "Wake time: $formatted (America/New_York)"
            btnSchedule.text = "Schedule $formatted wake"
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
                .remove("wake_profile")
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
            layoutWakeCard.alpha = wakeAlpha
            layoutChatCard.alpha = chatAlpha

            cbPunish.isEnabled = connected
            btnPickWakeTime.isEnabled = connected
            btnSchedule.isEnabled = connected
            btnTest.isEnabled = connected
            btnOpenChat.isEnabled = connected
            btnOpenChat.text = if (connected) "Open Chat" else "Open Chat (connect first)"
            tvWakeHint.text = if (connected) {
                "Pick any wake time you want. Astra will keep trying to wake you up until you tap I'm awake, Talk back only listens when you press it, and wake-ready Media Center assets can be used for audio choices."
            } else {
                "Connect this phone to enable wake controls."
            }
            tvChatHint.text = if (connected) {
                "Open your Astra chat with a louder personality and voice controls."
            } else {
                "Connect this phone first, then open your Astra chat."
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
        setAdvancedVisible(false)
        refreshGatewayDebug()
        refreshSecondaryCards()
        refreshWakeMediaStatus()
        if (isConnectedState()) OpenClawNodeService.start(this)
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

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveMainSettings()
            refreshWakeMediaStatus()
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
            AlarmScheduler.scheduleDaily(this, wakeHour, wakeMinute)
            Toast.makeText(this, "Scheduled for ${formatWakeTime(wakeHour, wakeMinute)} ET", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveMainSettings()
            startActivity(Intent(this, WakeActivity::class.java))
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
    }
}
