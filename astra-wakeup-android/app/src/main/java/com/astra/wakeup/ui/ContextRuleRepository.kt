package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ContextRuleRepository(context: Context) {
    private val prefs = context.getSharedPreferences("astra_context", Context.MODE_PRIVATE)

    fun getRules(): List<ContextRule> {
        val raw = prefs.getString("rules_json", null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultRules()
            saveRules(defaults)
            return defaults
        }
        return runCatching { parse(JSONArray(raw)) }.getOrElse { defaultRules() }
    }

    fun getEnabledRulesByTrigger(trigger: TriggerType): List<ContextRule> {
        return getRules().filter { it.enabled && it.trigger == trigger }
    }

    fun saveRules(rules: List<ContextRule>) {
        prefs.edit().putString("rules_json", toJson(rules).toString()).apply()
    }

    fun upsert(rule: ContextRule) {
        val list = getRules().toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list += rule
        saveRules(list)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = getRules().map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveRules(list)
    }

    private fun defaultRules(): List<ContextRule> = listOf(
        ContextRule(
            id = UUID.randomUUID().toString(),
            name = "Unlock after alarm greeting",
            trigger = TriggerType.PHONE_UNLOCK,
            conditions = listOf(RuleCondition("after_alarm", "30")),
            actions = listOf(
                RuleAction("change_personality", "coach"),
                RuleAction("speak", "Good morning. I prepared your day summary.")
            )
        ),
        ContextRule(
            id = UUID.randomUUID().toString(),
            name = "Charging at night → silent",
            trigger = TriggerType.CHARGING_CHANGED,
            conditions = listOf(RuleCondition("time_range", "22:00-07:00"), RuleCondition("is_charging", "true")),
            actions = listOf(RuleAction("change_personality", "silent"))
        )
    )

    private fun parse(arr: JSONArray): List<ContextRule> {
        val out = mutableListOf<ContextRule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val condArr = o.optJSONArray("conditions") ?: JSONArray()
            val actArr = o.optJSONArray("actions") ?: JSONArray()
            val conditions = mutableListOf<RuleCondition>()
            val actions = mutableListOf<RuleAction>()
            for (j in 0 until condArr.length()) {
                val c = condArr.getJSONObject(j)
                conditions += RuleCondition(c.optString("type"), c.optString("value"))
            }
            for (j in 0 until actArr.length()) {
                val a = actArr.getJSONObject(j)
                actions += RuleAction(a.optString("type"), a.optString("p1"), a.optString("p2"))
            }
            out += ContextRule(
                id = o.optString("id"),
                name = o.optString("name"),
                enabled = o.optBoolean("enabled", true),
                trigger = runCatching { TriggerType.valueOf(o.optString("trigger")) }.getOrDefault(TriggerType.TIME_TICK),
                conditions = conditions,
                actions = actions
            )
        }
        return out
    }

    private fun toJson(rules: List<ContextRule>): JSONArray {
        val arr = JSONArray()
        rules.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("enabled", r.enabled)
            o.put("trigger", r.trigger.name)
            val cond = JSONArray()
            r.conditions.forEach { c ->
                cond.put(JSONObject().put("type", c.type).put("value", c.value))
            }
            val act = JSONArray()
            r.actions.forEach { a ->
                act.put(JSONObject().put("type", a.type).put("p1", a.p1).put("p2", a.p2))
            }
            o.put("conditions", cond)
            o.put("actions", act)
            arr.put(o)
        }
        return arr
    }
}
