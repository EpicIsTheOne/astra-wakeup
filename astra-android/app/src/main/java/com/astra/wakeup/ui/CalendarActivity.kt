package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class CalendarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        val tv = findViewById<TextView>(R.id.tvCalendar)
        val etId = findViewById<EditText>(R.id.etCronId)
        val etName = findViewById<EditText>(R.id.etCronName)
        val etExpr = findViewById<EditText>(R.id.etCronExpr)
        val etTz = findViewById<EditText>(R.id.etCronTz)
        val etMessage = findViewById<EditText>(R.id.etCronMessage)

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
                            "• ${j.name}\n  id: ${j.id}\n  cron: ${j.schedule} (${j.tz})\n  enabled: ${j.enabled}\n  next: ${j.nextRun}\n  status: ${j.status}"
                        }
                        tv.text = "Now: $now\n\n$body"
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronCreate).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.create(
                    apiUrl,
                    etName.text.toString().trim(),
                    etExpr.text.toString().trim(),
                    etTz.text.toString().trim(),
                    etMessage.text.toString().trim()
                )
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron created" else "Create failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronEdit).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.edit(
                    apiUrl,
                    etId.text.toString().trim(),
                    etName.text.toString().trim().ifBlank { null },
                    etExpr.text.toString().trim().ifBlank { null },
                    etTz.text.toString().trim().ifBlank { null },
                    etMessage.text.toString().trim().ifBlank { null }
                )
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron edited" else "Edit failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronDelete).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.delete(apiUrl, etId.text.toString().trim())
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron deleted" else "Delete failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronEnable).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.toggle(apiUrl, etId.text.toString().trim(), enabled = true)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron enabled" else "Enable failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronDisable).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.toggle(apiUrl, etId.text.toString().trim(), enabled = false)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron disabled" else "Disable failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCronRunNow).setOnClickListener {
            Thread {
                val (ok, msg) = ApiCalendarClient.runNow(apiUrl, etId.text.toString().trim())
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Cron run triggered" else "Run failed: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRefreshCalendar).setOnClickListener { refresh() }
        refresh()
    }
}
