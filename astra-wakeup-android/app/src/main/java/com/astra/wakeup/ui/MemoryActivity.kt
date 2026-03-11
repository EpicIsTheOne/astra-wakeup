package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R

class MemoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        val tv = findViewById<TextView>(R.id.tvMemory)
        val spCategory = findViewById<Spinner>(R.id.spMemoryCategory)
        val etDeleteIndex = findViewById<EditText>(R.id.etDeleteIndex)
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""

        fun refresh() {
            tv.text = "Loading..."
            Thread {
                val selected = spCategory.selectedItem?.toString()?.lowercase() ?: "all"
                val category = if (selected == "all") null else selected
                val notes = ApiMemoryClient.list(apiUrl, category)
                runOnUiThread {
                    tv.text = if (notes.isEmpty()) {
                        "(No saved memory notes)"
                    } else {
                        notes.mapIndexed { i, n -> "${i + 1}. [${n.category}] ${n.text}" }.joinToString("\n")
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRefreshMemory).setOnClickListener { refresh() }

        findViewById<Button>(R.id.btnDeleteIndex).setOnClickListener {
            val idx = (etDeleteIndex.text.toString().toIntOrNull() ?: 0) - 1
            if (idx < 0) return@setOnClickListener
            Thread {
                ApiMemoryClient.deleteIndex(apiUrl, idx)
                runOnUiThread {
                    etDeleteIndex.setText("")
                    refresh()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            Thread {
                ApiMemoryClient.clearAll(apiUrl)
                runOnUiThread { refresh() }
            }.start()
        }

        refresh()
    }
}
