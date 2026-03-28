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
import com.astra.wakeup.brain.AstraBrainService

class ContextActivity : AppCompatActivity() {
    private lateinit var repo: InterventionRepository
    private lateinit var cbEnabled: CheckBox
    private lateinit var cbTts: CheckBox
    private lateinit var cbVoice: CheckBox
    private lateinit var etRollingWindow: EditText
    private lateinit var etCooldown: EditText
    private lateinit var etTrackedPackage: EditText
    private lateinit var etTrackedLabel: EditText
    private lateinit var etTrackedThreshold: EditText
    private lateinit var tvSummary: TextView
    private lateinit var tvTrackedApps: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_context)

        repo = InterventionRepository(this)
        cbEnabled = findViewById(R.id.cbInterventionEnabled)
        cbTts = findViewById(R.id.cbInterventionTts)
        cbVoice = findViewById(R.id.cbInterventionVoice)
        etRollingWindow = findViewById(R.id.etRollingWindowMinutes)
        etCooldown = findViewById(R.id.etCooldownMinutes)
        etTrackedPackage = findViewById(R.id.etTrackedPackage)
        etTrackedLabel = findViewById(R.id.etTrackedLabel)
        etTrackedThreshold = findViewById(R.id.etTrackedThreshold)
        tvSummary = findViewById(R.id.tvInterventionSummary)
        tvTrackedApps = findViewById(R.id.tvTrackedApps)

        fun render() {
            val state = repo.getState()
            cbEnabled.isChecked = state.enabled
            cbTts.isChecked = state.ttsEnabled
            cbVoice.isChecked = state.voiceEnabled
            etRollingWindow.setText(state.rollingWindowMinutes.toString())
            etCooldown.setText(state.cooldownMinutes.toString())
            tvSummary.text = "Per-app rolling tracking with reusable Astra popups. Defaults target YouTube and TikTok, but you can add more package names here if you want more of your bad habits judged."
            tvTrackedApps.text = if (state.trackedApps.isEmpty()) {
                "No tracked apps configured"
            } else {
                state.trackedApps.joinToString("\n\n") { app ->
                    "${app.label} (${app.packageName})\nthreshold=${app.thresholdMinutes} min | ${if (app.enabled) "enabled" else "disabled"}"
                }
            }
        }

        fun saveBaseState() {
            val current = repo.getState()
            repo.saveState(
                current.copy(
                    enabled = cbEnabled.isChecked,
                    ttsEnabled = cbTts.isChecked,
                    voiceEnabled = cbVoice.isChecked,
                    rollingWindowMinutes = etRollingWindow.text.toString().trim().toIntOrNull()?.coerceAtLeast(5) ?: current.rollingWindowMinutes,
                    cooldownMinutes = etCooldown.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: current.cooldownMinutes
                )
            )
        }

        findViewById<Button>(R.id.btnTrackedSave).setOnClickListener {
            val packageName = etTrackedPackage.text.toString().trim()
            val label = etTrackedLabel.text.toString().trim()
            val threshold = etTrackedThreshold.text.toString().trim().toIntOrNull()?.coerceAtLeast(1)
            if (packageName.isBlank()) {
                Toast.makeText(this, "Package name required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveBaseState()
            val current = repo.getState()
            val updated = current.trackedApps.filterNot { it.packageName == packageName } + InterventionTrackedApp(
                packageName = packageName,
                label = label.ifBlank { packageName },
                enabled = true,
                thresholdMinutes = threshold ?: 20
            )
            repo.saveState(current.copy(trackedApps = updated.sortedBy { it.label.lowercase() }))
            etTrackedPackage.setText("")
            etTrackedLabel.setText("")
            etTrackedThreshold.setText("")
            render()
            Toast.makeText(this, "Tracked app saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTrackedRemove).setOnClickListener {
            val packageName = etTrackedPackage.text.toString().trim()
            if (packageName.isBlank()) {
                Toast.makeText(this, "Enter the package name to remove", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveBaseState()
            val current = repo.getState()
            repo.saveState(current.copy(trackedApps = current.trackedApps.filterNot { it.packageName == packageName }))
            etTrackedPackage.setText("")
            etTrackedLabel.setText("")
            etTrackedThreshold.setText("")
            render()
            Toast.makeText(this, "Tracked app removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnInterventionSaveAll).setOnClickListener {
            saveBaseState()
            render()
            Toast.makeText(this, "Intervention settings saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStartContext).setOnClickListener {
            startService(Intent(this, ContextOrchestratorService::class.java))
            startService(Intent(this, AstraBrainService::class.java))
            Toast.makeText(this, "Intervention service started", Toast.LENGTH_SHORT).show()
        }

        render()
    }
}
