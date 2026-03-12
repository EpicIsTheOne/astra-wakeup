package com.astra.wakeup.ui

class ContextRuleEngine(
    private val evaluator: ContextConditionEvaluator,
    private val executor: ContextActionExecutor,
    private val repo: ContextRuleRepository
) {
    fun onEvent(event: ContextEvent, snapshot: ContextSnapshot) {
        val rules = repo.getEnabledRulesByTrigger(event.trigger)
        rules.forEach { rule ->
            val pass = rule.conditions.all { evaluator.evaluate(it, snapshot, event) }
            if (pass) executor.execute(rule, event, snapshot)
        }
    }

    fun testRun(ruleId: String, snapshot: ContextSnapshot) {
        val rule = repo.getRules().firstOrNull { it.id == ruleId } ?: return
        executor.execute(rule, ContextEvent(rule.trigger), snapshot)
    }
}
