package com.astra.wakeup.brain.automation

import com.astra.wakeup.brain.actions.Action

data class AutomationRule(
    val id: String,
    val source: String, // context|cron|task
    val trigger: String,
    val conditions: List<Pair<String, String>> = emptyList(),
    val actions: List<Action> = emptyList(),
    val enabled: Boolean = true
)

data class AutomationState(
    val totalRules: Int,
    val activeRules: Int,
    val contextRules: Int,
    val taskRules: Int,
    val cronRules: Int,
    val lastEvent: String = "-",
    val lastDecision: String = "-"
)
