package com.astra.wakeup.ui

enum class TriggerType {
    TIME_TICK,
    PHONE_UNLOCK,
    CHARGING_CHANGED,
    HEADPHONE_CHANGED,
    CALENDAR_EVENT_STARTING,
    LOCATION_ENTER,
    ALARM_DISMISSED,
    ALARM_TRIGGERED
}

enum class PersonalityMode { SWEET, BULLY, COACH, RADIO, SILENT }

data class RuleCondition(
    val type: String,
    val value: String
)

data class RuleAction(
    val type: String,
    val p1: String,
    val p2: String = ""
)

data class ContextRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: TriggerType,
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>
)

data class ContextEvent(
    val trigger: TriggerType,
    val timestampMs: Long = System.currentTimeMillis(),
    val payload: Map<String, String> = emptyMap()
)

data class ContextSnapshot(
    val nowMs: Long = System.currentTimeMillis(),
    val isCharging: Boolean = false,
    val headphonesConnected: Boolean = false,
    val lastAlarmTriggeredAt: Long? = null,
    val lastAlarmDismissedAt: Long? = null,
    val locationZoneId: String? = null,
    val nextCalendarEventMs: Long? = null,
    val currentPersonality: PersonalityMode = PersonalityMode.COACH,
    val foregroundAppPackage: String? = null
)
