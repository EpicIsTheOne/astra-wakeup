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
        val tvRules = findViewById<TextView>(R.id.tvRules)

        fun refresh() {
            val rules = repo.getRules()
            tvRules.text = if (rules.isEmpty()) "No rules" else rules.joinToString("\n\n") {
                "${it.name} [${it.id.take(8)}] ${if (it.enabled) "✅" else "⛔"}\ntrigger=${it.trigger}\nconditions=${it.conditions.joinToString { c -> c.type+"="+c.value }}\nactions=${it.actions.joinToString { a -> a.type+":"+a.p1 }}"
            }
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
            engine.testRun(id, ContextSnapshot())
            Toast.makeText(this, "Test run executed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStartContext).setOnClickListener {
            startService(Intent(this, ContextOrchestratorService::class.java))
            Toast.makeText(this, "Context service started", Toast.LENGTH_SHORT).show()
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
