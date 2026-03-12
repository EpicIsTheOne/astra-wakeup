package com.astra.wakeup.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.brain.AstraBrainService
import java.util.UUID

class ContextActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_context)

        val repo = ContextRuleRepository(this)
        val etId = findViewById<EditText>(R.id.etRuleId)
        val etName = findViewById<EditText>(R.id.etRuleName)
        val spTrigger = findViewById<Spinner>(R.id.spRuleTrigger)
        val etCond = findViewById<EditText>(R.id.etRuleConditions)
        val etAct = findViewById<EditText>(R.id.etRuleActions)
        val etZone = findViewById<EditText>(R.id.etCurrentZone)
        val tvRules = findViewById<TextView>(R.id.tvRules)
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)

        etZone.setText(prefs.getString("context_location_zone", "") ?: "")

        fun refresh() {
            val rules = repo.getRules()
            tvRules.text = if (rules.isEmpty()) "No rules" else rules.joinToString("\n\n") {
                "${it.name} [${it.id.take(8)}] ${if (it.enabled) "✅" else "⛔"}\ntrigger=${it.trigger}\nconditions=${it.conditions.joinToString { c -> c.type+"="+c.value }}\nactions=${it.actions.joinToString { a -> a.type+":"+a.p1 }}"
            }
        }

        fun seedTemplate(name: String, trigger: TriggerType, cond: String, act: String) {
            etId.setText("")
            etName.setText(name)
            spTrigger.setSelection(trigger.ordinal)
            etCond.setText(cond)
            etAct.setText(act)
        }

        findViewById<Button>(R.id.btnTplUnlockBrief).setOnClickListener {
            seedTemplate(
                "Unlock after alarm briefing",
                TriggerType.PHONE_UNLOCK,
                "after_alarm=30",
                "change_personality:coach|speak:Good morning, here's your day summary"
            )
        }

        findViewById<Button>(R.id.btnTplSchoolQuiet).setOnClickListener {
            seedTemplate(
                "School quiet mode",
                TriggerType.TIME_TICK,
                "time_range=08:00-15:00;location_zone=school",
                "change_personality:silent|show_notification:Quiet mode enabled"
            )
        }

        findViewById<Button>(R.id.btnTplHeadphones).setOnClickListener {
            seedTemplate(
                "Headphones radio mode",
                TriggerType.HEADPHONE_CHANGED,
                "headphones_connected=true",
                "change_personality:radio|speak:Astra FM connected"
            )
        }

        findViewById<Button>(R.id.btnSaveZone).setOnClickListener {
            prefs.edit().putString("context_location_zone", etZone.text.toString().trim()).apply()
            Toast.makeText(this, "Zone saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRuleSave).setOnClickListener {
            val id = etId.text.toString().trim().ifBlank { UUID.randomUUID().toString() }
            val name = etName.text.toString().trim().ifBlank { "Untitled rule" }
            val trigger = runCatching { TriggerType.valueOf(spTrigger.selectedItem.toString()) }.getOrDefault(TriggerType.TIME_TICK)
            val conditions = parseConditions(etCond.text.toString())
            val actions = parseActions(etAct.text.toString())
            val existing = repo.getRules().firstOrNull { it.id == id }
            repo.upsert(ContextRule(id, name, existing?.enabled ?: true, trigger, conditions, actions))
            Toast.makeText(this, "Rule saved", Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<Button>(R.id.btnRuleToggle).setOnClickListener {
            val id = etId.text.toString().trim()
            val rule = repo.getRules().firstOrNull { it.id == id }
            if (rule == null) {
                Toast.makeText(this, "Enter valid rule ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repo.setEnabled(id, !rule.enabled)
            refresh()
        }

        findViewById<Button>(R.id.btnRuleTest).setOnClickListener {
            val id = etId.text.toString().trim()
            val engine = ContextRuleEngine(ContextConditionEvaluator(), ContextActionExecutor(this), repo)
            val snap = ContextSnapshot(
                locationZoneId = prefs.getString("context_location_zone", null),
                lastAlarmTriggeredAt = prefs.getLong("last_alarm_triggered_at", 0L).takeIf { it > 0 },
                lastAlarmDismissedAt = prefs.getLong("last_alarm_dismissed_at", 0L).takeIf { it > 0 }
            )
            engine.testRun(id, snap)
            Toast.makeText(this, "Test run executed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStartContext).setOnClickListener {
            startService(Intent(this, ContextOrchestratorService::class.java))
            startService(Intent(this, AstraBrainService::class.java))
            Toast.makeText(this, "Context + Brain services started", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    private fun parseConditions(raw: String): List<RuleCondition> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";")
            .mapNotNull { s ->
                val p = s.split("=", limit = 2)
                if (p.size != 2) null else RuleCondition(p[0].trim(), p[1].trim())
            }
    }

    private fun parseActions(raw: String): List<RuleAction> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\\|")
            .mapNotNull { s ->
                val p = s.split(":", limit = 2)
                if (p.size != 2) null else RuleAction(p[0].trim(), p[1].trim())
            }
    }
}
