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
        val spWakeProfile = findViewById<Spinner>(R.id.spWakeProfile)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvApiStatus = findViewById<TextView>(R.id.tvApiStatus)
        val tvApiDetails = findViewById<TextView>(R.id.tvApiDetails)
        val tvHealthChip = findViewById<TextView>(R.id.tvHealthChip)
        val tvLineChip = findViewById<TextView>(R.id.tvLineChip)
        val tvChatChip = findViewById<TextView>(R.id.tvChatChip)

        etApiUrl.setText(prefs.getString("api_url", "http://72.60.29.204:8787/api/astra"))
        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = "version: ${pkgInfo.versionName}"
        cbRandomSfx.isChecked = prefs.getBoolean("random_sfx", true)
        cbPunish.isChecked = prefs.getBoolean("punish", true)
        cbAstraFm.isChecked = prefs.getBoolean("astra_fm", true)
        val profile = prefs.getString("wake_profile", "bully") ?: "bully"
        val idx = resources.getStringArray(R.array.wake_profiles).indexOf(profile).coerceAtLeast(0)
        spWakeProfile.setSelection(idx)

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
            val wakeProfile = spWakeProfile.selectedItem.toString()
            prefs.edit()
                .putString("api_url", apiUrl)
                .putBoolean("random_sfx", cbRandomSfx.isChecked)
                .putBoolean("punish", cbPunish.isChecked)
                .putBoolean("astra_fm", cbAstraFm.isChecked)
                .putString("wake_profile", wakeProfile)
                .apply()

            Thread {
                val (ok, msg) = ApiProfileClient.setProfile(apiUrl, wakeProfile)
                runOnUiThread {
                    val m = if (ok) "Saved (${wakeProfile})" else "Saved local, server profile failed: $msg"
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
                val suite = ApiStatusClient.checkSuite(apiUrl)
                runOnUiThread {
                    tvApiStatus.text = "API status: ${suite.summary}"
                    tvApiDetails.text = suite.details
                    tvHealthChip.text = "health: ${if (suite.healthOk) "✅" else "❌"}"
                    tvLineChip.text = "line: ${if (suite.lineOk) "✅" else "❌"}"
                    tvChatChip.text = "chat: ${if (suite.chatOk) "✅" else "❌"}"
                }
            }.start()
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
