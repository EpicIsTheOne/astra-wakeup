package com.astra.wakeup.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        val spWakeProfile = findViewById<Spinner>(R.id.spWakeProfile)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvApiStatus = findViewById<TextView>(R.id.tvApiStatus)
        val tvApiDetails = findViewById<TextView>(R.id.tvApiDetails)
        val tvHealthChip = findViewById<TextView>(R.id.tvHealthChip)
        val tvLineChip = findViewById<TextView>(R.id.tvLineChip)
        val tvChatChip = findViewById<TextView>(R.id.tvChatChip)
        val tvGatewayDebug = findViewById<TextView>(R.id.tvGatewayDebug)

        etApiUrl.setText(prefs.getString("api_url", "http://72.60.29.204:8787/api/astra"))
        etGatewayToken.setText(prefs.getString("gateway_token", "") ?: "")
        etBootstrapToken.setText(prefs.getString("gateway_bootstrap_token", "") ?: "")
        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = "version: ${pkgInfo.versionName}"
        cbRandomSfx.isChecked = prefs.getBoolean("random_sfx", true)
        cbPunish.isChecked = prefs.getBoolean("punish", true)
        cbAstraFm.isChecked = prefs.getBoolean("astra_fm", true)
        val profile = prefs.getString("wake_profile", "bully") ?: "bully"
        val idx = resources.getStringArray(R.array.wake_profiles).indexOf(profile).coerceAtLeast(0)
        spWakeProfile.setSelection(idx)

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

        refreshGatewayDebug()

        findViewById<Button>(R.id.btnOpenChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenMemory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenCalendar).setOnClickListener {
            startActivity(Intent(this, CalendarActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenAnalytics).setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenContext).setOnClickListener {
            startActivity(Intent(this, ContextActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenDashboard).setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenTasks).setOnClickListener {
            startActivity(Intent(this, TaskEditorActivity::class.java))
        }

        findViewById<Button>(R.id.btnReleaseNotes).setOnClickListener {
            val apiUrl = etApiUrl.text.toString().trim()
            Thread {
                val notes = ApiOpsClient.releaseNotes(apiUrl)
                val metrics = ApiOpsClient.metrics(apiUrl)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Astra Release Notes")
                        .setMessage("$notes\n\nMetrics: $metrics")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
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

            Thread {
                val (ok, msg) = ApiProfileClient.setProfile(apiUrl, wakeProfile)
                runOnUiThread {
                    val authSaved = buildList {
                        if (gatewayToken.isNotBlank()) add("shared token")
                        if (bootstrapToken.isNotBlank()) add("bootstrap token")
                    }.joinToString()
                    val suffix = if (authSaved.isBlank()) "" else " | auth: $authSaved"
                    val m = if (ok) "Saved (${wakeProfile})$suffix" else "Saved local, server profile failed: $msg$suffix"
                    refreshGatewayDebug()
                    Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCheckApi).setOnClickListener {
            val apiUrl = etApiUrl.text.toString().trim()
            tvApiStatus.text = "API status: checking..."
            tvApiDetails.text = ""
            tvHealthChip.text = "health: ..."
            tvLineChip.text = "line: ..."
            tvChatChip.text = "chat: ..."
            Thread {
                val suite = ApiStatusClient.checkSuite(this, apiUrl)
                runOnUiThread {
                    tvApiStatus.text = "API status: ${suite.summary}"
                    tvApiDetails.text = suite.details
                    tvHealthChip.text = "health: ${if (suite.healthOk) "✅" else "❌"}"
                    tvLineChip.text = "line: ${if (suite.lineOk) "✅" else "❌"}"
                    tvChatChip.text = "chat: ${if (suite.chatOk) "✅" else "❌"}"
                    refreshGatewayDebug(if (suite.chatOk) null else suite.details)
                }
            }.start()
        }

        findViewById<Button>(R.id.btnProbeGateway).setOnClickListener {
            tvApiStatus.text = "API status: probing gateway..."
            Thread {
                val result = OpenClawChatClient().probe(this)
                runOnUiThread {
                    result.onSuccess { session ->
                        val server = session.helloPayload.optJSONObject("server")
                        val version = server?.optString("version").orEmpty().ifBlank { "unknown" }
                        val connId = server?.optString("connId").orEmpty().ifBlank { "?" }
                        tvApiStatus.text = "API status: gateway probe ok ✅"
                        tvApiDetails.text = "serverVersion=$version, connId=$connId"
                        refreshGatewayDebug()
                    }.onFailure { err ->
                        val msg = err.message ?: "probe failed"
                        tvApiStatus.text = "API status: gateway probe failed ❌"
                        tvApiDetails.text = OpenClawGatewayDiagnostics.describeStatus(this, msg)
                        refreshGatewayDebug(msg)
                    }
                }
            }.start()
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
            AlarmScheduler.scheduleDaily(this, 5, 50)
            Toast.makeText(this, "Scheduled for 5:50 AM ET", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            startActivity(Intent(this, WakeActivity::class.java))
        }
    }
}
