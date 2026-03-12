package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val tv = findViewById<TextView>(R.id.tvDashboard)

        fun refresh() {
            val brain = getSharedPreferences("astra_brain", MODE_PRIVATE)
            val astra = getSharedPreferences("astra", MODE_PRIVATE)
            val txt = buildString {
                appendLine("Brain status")
                appendLine("- last event: ${brain.getString("last_event", "-")}")
                appendLine("- last decision: ${brain.getString("last_decision", "-")}")
                appendLine("- total rules: ${brain.getInt("total_rules", 0)}")
                appendLine("- context rules: ${brain.getInt("context_rules", 0)}")
                appendLine("- task rules: ${brain.getInt("task_rules", 0)}")
                appendLine("- cron rules(cache): ${brain.getInt("cron_rules", 0)}")
                appendLine()
                appendLine("Runtime")
                appendLine("- personality: ${astra.getString("personality_mode", "coach")}")
                appendLine("- wake profile: ${astra.getString("wake_profile", "bully")}")
                appendLine("- astra fm: ${astra.getBoolean("astra_fm", true)}")
            }
            tv.text = txt
        }

        findViewById<Button>(R.id.btnRefreshDashboard).setOnClickListener { refresh() }
        refresh()
    }
}
