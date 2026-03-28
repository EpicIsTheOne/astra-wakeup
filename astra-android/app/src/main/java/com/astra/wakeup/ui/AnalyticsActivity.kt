package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class AnalyticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)

        val tv = findViewById<TextView>(R.id.tvAnalytics)
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""

        fun refresh() {
            tv.text = "Loading..."
            Thread {
                val report = ApiAnalyticsClient.fetch(apiUrl)
                runOnUiThread { tv.text = report }
            }.start()
        }

        findViewById<Button>(R.id.btnRefreshAnalytics).setOnClickListener { refresh() }
        refresh()
    }
}
