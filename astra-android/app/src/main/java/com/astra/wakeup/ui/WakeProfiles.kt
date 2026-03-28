package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class WakePhase {
    SURFACE,
    ENGAGE,
    INTERVENTION,
    NO_MORE_GAMES
}

data class WakeProfile(
    val id: String,
    val title: String,
    val description: String,
    val voiceVolume: Int,
    val musicVolume: Int,
    val sfxVolume: Int,
    val punish: Boolean,
    val sfxAllowedFrom: WakePhase,
    val loopMsSurface: Long,
    val loopMsEngage: Long,
    val loopMsIntervention: Long,
    val loopMsNoMoreGames: Long,
    val snoozeMinutesFirst: Int,
    val snoozeMinutesLater: Int,
    val promptStyle: String
)

object WakeProfiles {
    private val zone: ZoneId = ZoneId.of("America/New_York")

    val all: List<WakeProfile> = listOf(
        WakeProfile(
            id = "gentle",
            title = "Gentle",
            description = "Soft landing, lighter pressure, voice-first.",
            voiceVolume = 60,
            musicVolume = 24,
            sfxVolume = 35,
            punish = false,
            sfxAllowedFrom = WakePhase.INTERVENTION,
            loopMsSurface = 45_000,
            loopMsEngage = 35_000,
            loopMsIntervention = 26_000,
            loopMsNoMoreGames = 20_000,
            snoozeMinutesFirst = 12,
            snoozeMinutesLater = 10,
            promptStyle = "Start warm, patient, and persuasive. Escalate slowly and avoid harsh sound effects unless Epic keeps ignoring you."
        ),
        WakeProfile(
            id = "workday",
            title = "Workday",
            description = "Competent, steady, and hard to ignore.",
            voiceVolume = 72,
            musicVolume = 34,
            sfxVolume = 82,
            punish = true,
            sfxAllowedFrom = WakePhase.ENGAGE,
            loopMsSurface = 30_000,
            loopMsEngage = 22_000,
            loopMsIntervention = 17_000,
            loopMsNoMoreGames = 12_000,
            snoozeMinutesFirst = 10,
            snoozeMinutesLater = 7,
            promptStyle = "Be sharp, focused, and useful. Keep momentum high because this is a real get-up-and-move morning."
        ),
        WakeProfile(
            id = "recovery",
            title = "Recovery",
            description = "Low-chaos, more humane, still effective.",
            voiceVolume = 64,
            musicVolume = 28,
            sfxVolume = 45,
            punish = false,
            sfxAllowedFrom = WakePhase.NO_MORE_GAMES,
            loopMsSurface = 55_000,
            loopMsEngage = 40_000,
            loopMsIntervention = 28_000,
            loopMsNoMoreGames = 22_000,
            snoozeMinutesFirst = 12,
            snoozeMinutesLater = 8,
            promptStyle = "Be gentle but firm. Preserve dignity, reduce noise, and only escalate if absolutely necessary."
        ),
        WakeProfile(
            id = "absolute_violence",
            title = "Absolute Violence",
            description = "For mornings where failure is not allowed.",
            voiceVolume = 82,
            musicVolume = 40,
            sfxVolume = 100,
            punish = true,
            sfxAllowedFrom = WakePhase.SURFACE,
            loopMsSurface = 22_000,
            loopMsEngage = 16_000,
            loopMsIntervention = 11_000,
            loopMsNoMoreGames = 8_000,
            snoozeMinutesFirst = 7,
            snoozeMinutesLater = 5,
            promptStyle = "Be intense, relentless, and theatrically merciless. This is a mission-critical wake-up."
        ),
        WakeProfile(
            id = "custom",
            title = "Custom",
            description = "Use the current sliders and punish toggle exactly.",
            voiceVolume = 70,
            musicVolume = 35,
            sfxVolume = 90,
            punish = true,
            sfxAllowedFrom = WakePhase.ENGAGE,
            loopMsSurface = 30_000,
            loopMsEngage = 22_000,
            loopMsIntervention = 16_000,
            loopMsNoMoreGames = 12_000,
            snoozeMinutesFirst = 10,
            snoozeMinutesLater = 7,
            promptStyle = "Respect the custom settings and behave accordingly."
        )
    )

    fun byId(id: String?): WakeProfile = all.firstOrNull { it.id == id } ?: all.first { it.id == "workday" }

    fun defaultProfile(context: Context): WakeProfile = byId(context.prefs().getString("wake_default_plan", "workday"))

    fun nextProfileId(current: String?): String {
        val idx = all.indexOfFirst { it.id == current }
        val next = if (idx < 0) 0 else (idx + 1) % all.size
        return all[next].id
    }

    fun setTomorrowOverride(context: Context, planId: String?) {
        val edit = context.prefs().edit()
        if (planId.isNullOrBlank()) {
            edit.remove("wake_tomorrow_override_plan").remove("wake_tomorrow_override_date")
        } else {
            edit.putString("wake_tomorrow_override_plan", planId)
                .putString("wake_tomorrow_override_date", LocalDate.now(zone).plusDays(1).toString())
        }
        edit.apply()
    }

    fun tomorrowOverrideLabel(context: Context): String {
        val prefs = context.prefs()
        val date = prefs.getString("wake_tomorrow_override_date", null)
        val planId = prefs.getString("wake_tomorrow_override_plan", null)
        return if (!date.isNullOrBlank() && !planId.isNullOrBlank()) {
            "Tomorrow override: ${byId(planId).title} ($date)"
        } else {
            "Tomorrow override: none"
        }
    }

    fun activeProfile(context: Context): WakeProfile {
        val prefs = context.prefs()
        val today = LocalDate.now(zone).toString()
        val overrideDate = prefs.getString("wake_tomorrow_override_date", null)
        val overridePlan = prefs.getString("wake_tomorrow_override_plan", null)
        return if (overrideDate == today && !overridePlan.isNullOrBlank()) {
            byId(overridePlan)
        } else {
            defaultProfile(context)
        }
    }

    fun applyProfileDefaults(context: Context, profileId: String) {
        val profile = byId(profileId)
        val edit = context.prefs().edit().putString("wake_default_plan", profile.id)
        if (profile.id != "custom") {
            edit.putInt("wake_voice_volume", profile.voiceVolume)
                .putInt("wake_music_volume", profile.musicVolume)
                .putInt("wake_sfx_volume", profile.sfxVolume)
                .putBoolean("punish", profile.punish)
        }
        edit.apply()
    }

    fun currentPhase(context: Context, nowMs: Long = System.currentTimeMillis()): WakePhase {
        val prefs = context.prefs()
        val triggeredAt = prefs.getLong("last_alarm_triggered_at", nowMs)
        val snoozes = prefs.getInt("wake_snooze_count", 0)
        val elapsed = (nowMs - triggeredAt).coerceAtLeast(0L)
        return when {
            snoozes >= 2 || elapsed >= 8 * 60_000L -> WakePhase.NO_MORE_GAMES
            snoozes >= 1 || elapsed >= 4 * 60_000L -> WakePhase.INTERVENTION
            elapsed >= 90_000L -> WakePhase.ENGAGE
            else -> WakePhase.SURFACE
        }
    }

    fun loopMsFor(profile: WakeProfile, phase: WakePhase): Long = when (phase) {
        WakePhase.SURFACE -> profile.loopMsSurface
        WakePhase.ENGAGE -> profile.loopMsEngage
        WakePhase.INTERVENTION -> profile.loopMsIntervention
        WakePhase.NO_MORE_GAMES -> profile.loopMsNoMoreGames
    }

    fun snoozeMinutes(profile: WakeProfile, snoozeCount: Int): Int = if (snoozeCount <= 0) profile.snoozeMinutesFirst else profile.snoozeMinutesLater

    fun sfxAllowed(profile: WakeProfile, phase: WakePhase): Boolean = phase.ordinal >= profile.sfxAllowedFrom.ordinal

    fun recordWakeOutcome(context: Context, outcome: String, profile: WakeProfile, phase: WakePhase) {
        val prefs = context.prefs()
        val history = runCatching { JSONArray(prefs.getString("wake_history_json", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val entry = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("profile", profile.id)
            .put("phase", phase.name)
            .put("outcome", outcome)
            .put("snoozes", prefs.getInt("wake_snooze_count", 0))
            .put("triggeredAt", prefs.getLong("last_alarm_triggered_at", 0L))
            .put("dismissedAt", prefs.getLong("last_alarm_dismissed_at", 0L))
        history.put(entry)
        val trimmed = JSONArray()
        val start = (history.length() - 20).coerceAtLeast(0)
        for (i in start until history.length()) trimmed.put(history.get(i))
        prefs.edit().putString("wake_history_json", trimmed.toString()).apply()
    }

    private fun Context.prefs() = getSharedPreferences("astra", Context.MODE_PRIVATE)
}
