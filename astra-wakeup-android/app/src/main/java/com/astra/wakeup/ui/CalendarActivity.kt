package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class CalendarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        val tv = findViewById<TextView>(R.id.tvCalendar)
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""

        fun refresh() {
            tv.text = "Loading..."
            Thread {
                val (now, jobs) = ApiCalendarClient.fetch(apiUrl)
                runOnUiThread {
                    if (jobs.isEmpty()) {
                        tv.text = "Now: $now\n\nNo cron jobs found (or fetch failed)."
                    } else {
                        val body = jobs.joinToString("\n\n") { j ->
                            "• ${j.name}\n  cron: ${j.schedule} (${j.tz})\n  enabled: ${j.enabled}\n  next: ${j.nextRun}\n  status: ${j.status}"
                        }
                        tv.text = "Now: $now\n\n$body"
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRefreshCalendar).setOnClickListener { refresh() }
        refresh()
    }
}
