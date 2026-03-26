package com.astra.wakeup.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.alarm.AlarmScheduler

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val etApiUrl = findViewById<EditText>(R.id.etApiUrl)
        val cbRandomSfx = findViewById<CheckBox>(R.id.cbRandomSfx)
        val cbPunish = findViewById<CheckBox>(R.id.cbPunish)
        val cbAstraFm = findViewById<CheckBox>(R.id.cbAstraFm)
        val etGatewayToken = findViewById<EditText>(R.id.etGatewayToken)
        val etBootstrapToken = findViewById<EditText>(R.id.etBootstrapToken)
        val layoutGatewayAdvanced = findViewById<LinearLayout>(R.id.layoutGatewayAdvanced)
        val layoutGatewayDebug = findViewById<LinearLayout>(R.id.layoutGatewayDebug)
        val spWakeProfile = findViewById<Spinner>(R.id.spWakeProfile)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvApiStatus = findViewById<TextView>(R.id.tvApiStatus)
        val tvApiDetails = findViewById<TextView>(R.id.tvApiDetails)
        val tvConnectBanner = findViewById<TextView>(R.id.tvConnectBanner)
        val tvHealthChip = findViewById<TextView>(R.id.tvHealthChip)
        val tvLineChip = findViewById<TextView>(R.id.tvLineChip)
        val tvChatChip = findViewById<TextView>(R.id.tvChatChip)
        val tvGatewayDebug = findViewById<TextView>(R.id.tvGatewayDebug)
        val btnToggleAdvancedGateway = findViewById<Button>(R.id.btnToggleAdvancedGateway)
        val btnConnectGateway = findViewById<Button>(R.id.btnConnectGateway)

        val defaultExternalUrl = "http://72.60.29.204:8787/api/astra"
        val savedApiUrl = prefs.getString("api_url", null)?.takeIf { it.isNotBlank() }
        etApiUrl.setText(savedApiUrl ?: defaultExternalUrl)
        etGatewayToken.setText(prefs.getString("gateway_token", "") ?: "")
        etBootstrapToken.setText(prefs.getString("gateway_bootstrap_token", "") ?: "")

        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = "version ${pkgInfo.versionName}"

        cbRandomSfx.isChecked = prefs.getBoolean("random_sfx", true)
        cbPunish.isChecked = prefs.getBoolean("punish", true)
        cbAstraFm.isChecked = prefs.getBoolean("astra_fm", true)
        val profile = prefs.getString("wake_profile", "bully") ?: "bully"
        val idx = resources.getStringArray(R.array.wake_profiles).indexOf(profile).coerceAtLeast(0)
        spWakeProfile.setSelection(idx)

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
            val wakeProfile = spWakeProfile.selectedItem.toString()
            prefs.edit()
                .putString("api_url", apiUrl)
                .putString("gateway_token", gatewayToken)
                .putString("gateway_bootstrap_token", bootstrapToken)
                .putBoolean("random_sfx", cbRandomSfx.isChecked)
                .putBoolean("punish", cbPunish.isChecked)
                .putBoolean("astra_fm", cbAstraFm.isChecked)
                .putString("wake_profile", wakeProfile)
                .apply()
        }

        fun setConnectedState(connected: Boolean) {
            prefs.edit().putBoolean("gateway_connected", connected).apply()
        }

        fun isConnectedState(): Boolean = prefs.getBoolean("gateway_connected", false)

        fun showConnectBanner(message: String? = null, backgroundColor: String = "#3F1D3B", textColor: String = "#FCE7F3") {
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

        fun refreshOpenChatButton() {
            val button = findViewById<Button>(R.id.btnOpenChat)
            val connected = isConnectedState()
            button.isEnabled = connected
            button.alpha = if (connected) 1.0f else 0.65f
            button.text = if (connected) "Open Chat" else "Open Chat (connect first)"
        }

        fun setConnectBusy(isBusy: Boolean, buttonLabel: String = "Connect this phone") {
            btnConnectGateway.isEnabled = !isBusy
            btnConnectGateway.text = buttonLabel
            etApiUrl.isEnabled = !isBusy
        }

        fun runStatusCheck() {
            val apiUrl = etApiUrl.text.toString().trim()
            tvApiStatus.text = "Status: checking connection..."
            tvApiDetails.text = "Checking health, line, and chat so the UI has something intelligent to say for once."
            showConnectBanner("Checking OpenClaw connection…", backgroundColor = "#1E293B", textColor = "#E2E8F0")
            tvHealthChip.text = "health: ..."
            tvLineChip.text = "line: ..."
            tvChatChip.text = "chat: ..."
            Thread {
                val suite = ApiStatusClient.checkSuite(this, apiUrl)
                runOnUiThread {
                    setConnectedState(suite.chatOk)
                    refreshOpenChatButton()
                    tvApiStatus.text = if (suite.ok) "Status: ready ✅" else "Status: needs attention"
                    tvApiDetails.text = suite.details
                    tvHealthChip.text = "health: ${if (suite.healthOk) "✅" else "❌"}"
                    tvLineChip.text = "line: ${if (suite.lineOk) "✅" else "❌"}"
                    tvChatChip.text = "chat: ${if (suite.chatOk) "✅" else "❌"}"
                    if (suite.chatOk) {
                        showConnectBanner("This phone is connected. Chat is ready.", backgroundColor = "#14532D", textColor = "#DCFCE7")
                    } else {
                        showConnectBanner(null)
                    }
                    refreshGatewayDebug(if (suite.chatOk) null else suite.details)
                }
            }.start()
        }

        fun connectThisPhone() {
            saveMainSettings()
            val apiUrl = etApiUrl.text.toString().trim()
            if (apiUrl.isBlank()) {
                setConnectedState(false)
                refreshOpenChatButton()
                showConnectBanner("Add your OpenClaw URL first.", backgroundColor = "#7C2D12", textColor = "#FFEDD5")
                tvApiStatus.text = "Status: add your OpenClaw URL"
                tvApiDetails.text = "Paste your external OpenClaw URL, then tap Connect this phone again."
                return
            }

            setConnectBusy(true, "Connecting...")
            tvApiStatus.text = "Status: connecting this phone..."
            tvApiDetails.text = "Starting secure handshake with OpenClaw. This can take a few seconds on first connect."
            showConnectBanner("Connecting this phone to OpenClaw…", backgroundColor = "#1E293B", textColor = "#E2E8F0")
            tvHealthChip.text = "health: ..."
            tvLineChip.text = "line: ..."
            tvChatChip.text = "chat: ..."
            Thread {
                val result = OpenClawChatClient().probe(this)
                runOnUiThread {
                    setConnectBusy(false)
                    result.onSuccess { session ->
                        val server = session.helloPayload.optJSONObject("server")
                        val version = server?.optString("version").orEmpty().ifBlank { "unknown" }
                        val connId = server?.optString("connId").orEmpty().ifBlank { "?" }
                        setConnectedState(true)
                        refreshOpenChatButton()
                        showConnectBanner("Connected. Chat is unlocked and ready.", backgroundColor = "#14532D", textColor = "#DCFCE7")
                        tvApiStatus.text = "Status: phone connected ✅"
                        tvApiDetails.text = "Connected to OpenClaw. You can open chat now. serverVersion=$version, connId=$connId"
                        tvHealthChip.text = "health: ✅"
                        tvLineChip.text = "line: ready"
                        tvChatChip.text = "chat: ✅"
                        refreshGatewayDebug()
                    }.onFailure { err ->
                        val msg = err.message ?: "connect failed"
                        val issue = OpenClawGatewayDiagnostics.classify(msg)
                        setConnectedState(false)
                        refreshOpenChatButton()
                        if (issue?.code == "PAIRING_REQUIRED") {
                            showConnectBanner("Approval needed: ask Astra to approve the pending Android device, then tap Connect this phone again.", backgroundColor = "#7C2D12", textColor = "#FFEDD5")
                            tvApiStatus.text = "Status: waiting for approval ⏳"
                            tvApiDetails.text = "Approval is needed. Ask Astra to approve the pending Android device, then tap Connect this phone again."
                            tvChatChip.text = "chat: waiting"
                        } else {
                            showConnectBanner("Connection failed. Check the URL or gateway auth, then try again.", backgroundColor = "#7F1D1D", textColor = "#FEE2E2")
                            tvApiStatus.text = "Status: connection failed ❌"
                            tvApiDetails.text = OpenClawGatewayDiagnostics.describeStatus(this, msg)
                            tvChatChip.text = "chat: ❌"
                        }
                        refreshGatewayDebug(msg)
                    }
                }
            }.start()
        }

        setAdvancedVisible(false)
        refreshGatewayDebug()
        refreshOpenChatButton()
        runStatusCheck()

        btnToggleAdvancedGateway.setOnClickListener {
            setAdvancedVisible(layoutGatewayAdvanced.visibility != android.view.View.VISIBLE)
        }

        btnConnectGateway.setOnClickListener {
            connectThisPhone()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveMainSettings()
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
            OpenClawGatewayAuthStore.clearAllGatewayAuth(this)
            etGatewayToken.setText("")
            etBootstrapToken.setText("")
            refreshGatewayDebug("all gateway auth cleared")
            Toast.makeText(this, "Cleared shared/bootstrap/device auth", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSchedule).setOnClickListener {
            saveMainSettings()
            AlarmScheduler.scheduleDaily(this, 5, 50)
            Toast.makeText(this, "Scheduled for 5:50 AM ET", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            saveMainSettings()
            startActivity(Intent(this, WakeActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenChat).setOnClickListener {
            if (!isConnectedState()) {
                Toast.makeText(this, "Connect this phone first", Toast.LENGTH_SHORT).show()
                showConnectBanner("Chat stays locked until this phone finishes connecting.", backgroundColor = "#7C2D12", textColor = "#FFEDD5")
                tvApiStatus.text = "Status: connect before opening chat"
                tvApiDetails.text = "Finish the phone connection first so chat has a real gateway session to use."
                return@setOnClickListener
            }
            startActivity(Intent(this, ChatActivity::class.java))
        }
    }
}
