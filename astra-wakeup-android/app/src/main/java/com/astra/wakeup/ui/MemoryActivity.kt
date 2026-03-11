package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class MemoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        val tv = findViewById<TextView>(R.id.tvMemory)
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""

        fun refresh() {
            tv.text = "Loading..."
            Thread {
                val notes = ApiMemoryClient.list(apiUrl)
                runOnUiThread {
                    tv.text = if (notes.isEmpty()) "(No saved memory notes)" else notes.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRefreshMemory).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            Thread {
                ApiMemoryClient.clearAll(apiUrl)
                runOnUiThread { refresh() }
            }.start()
        }

        refresh()
    }
}
