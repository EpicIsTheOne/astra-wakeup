package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.brain.BrainEventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val tv = findViewById<TextView>(R.id.tvDashboard)
        val sp = findViewById<Spinner>(R.id.spSeverity)

        fun refresh() {
            val brain = getSharedPreferences("astra_brain", MODE_PRIVATE)
            val astra = getSharedPreferences("astra", MODE_PRIVATE)
            val level = sp.selectedItem?.toString() ?: "all"
            val logs = BrainEventLog.list(this, level)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeline = if (logs.isEmpty()) "(no events)" else logs.joinToString("\n") {
                "${fmt.format(Date(it.atMs))} [${it.level}] ${it.message}"
            }

            fun bar(n: Int, scale: Int = 2): String {
                val blocks = (n * scale).coerceAtMost(30)
                return "█".repeat(blocks.coerceAtLeast(0))
            }

            val totalRules = brain.getInt("total_rules", 0)
            val contextRules = brain.getInt("context_rules", 0)
            val taskRules = brain.getInt("task_rules", 0)
            val cronRules = brain.getInt("cron_rules", 0)

            val txt = buildString {
                appendLine("Brain status")
                appendLine("- last event: ${brain.getString("last_event", "-")}")
                appendLine("- last decision: ${brain.getString("last_decision", "-")}")
                appendLine("- total rules: $totalRules")
                appendLine("- context rules: $contextRules")
                appendLine("- task rules: $taskRules")
                appendLine("- cron rules: $cronRules")
                appendLine()
                appendLine("Rule chart")
                appendLine("context  ${bar(contextRules)} $contextRules")
                appendLine("tasks    ${bar(taskRules)} $taskRules")
                appendLine("cron     ${bar(cronRules)} $cronRules")
                appendLine("total    ${bar(totalRules, 1)} $totalRules")
                appendLine()
                appendLine("Runtime")
                val wakeHour = astra.getInt("wake_hour", 5)
                val wakeMinute = astra.getInt("wake_minute", 50)
                appendLine("- personality: ${astra.getString("personality_mode", "coach")}")
                appendLine("- wake time: ${String.format(Locale.US, "%02d:%02d", wakeHour, wakeMinute)} ET")
                appendLine()
                appendLine("Timeline ($level)")
                appendLine(timeline)
            }
            tv.text = txt
        }

        findViewById<Button>(R.id.btnRefreshDashboard).setOnClickListener { refresh() }
        refresh()
    }
}
