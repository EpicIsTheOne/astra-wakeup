package com.astra.wakeup.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
        val tvApiStatus = findViewById<TextView>(R.id.tvApiStatus)
        val tvApiDetails = findViewById<TextView>(R.id.tvApiDetails)

        etApiUrl.setText(prefs.getString("api_url", "http://72.60.29.204:8787/api/astra"))
        cbRandomSfx.isChecked = prefs.getBoolean("random_sfx", true)
        cbPunish.isChecked = prefs.getBoolean("punish", true)

        findViewById<Button>(R.id.btnOpenChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit()
                .putString("api_url", etApiUrl.text.toString().trim())
                .putBoolean("random_sfx", cbRandomSfx.isChecked)
                .putBoolean("punish", cbPunish.isChecked)
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCheckApi).setOnClickListener {
            val apiUrl = etApiUrl.text.toString().trim()
            tvApiStatus.text = "API status: checking..."
            tvApiDetails.text = ""
            Thread {
                val suite = ApiStatusClient.checkSuite(apiUrl)
                runOnUiThread {
                    tvApiStatus.text = "API status: ${suite.summary}"
                    tvApiDetails.text = suite.details
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
