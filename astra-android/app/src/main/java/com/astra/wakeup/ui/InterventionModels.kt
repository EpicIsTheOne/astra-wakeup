package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InterventionTrackedApp(
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val thresholdMinutes: Int = 20
)

data class InterventionState(
    val enabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val trackedApps: List<InterventionTrackedApp> = defaultTrackedApps(),
    val rollingWindowMinutes: Int = 60,
    val cooldownMinutes: Int = 20
)

object InterventionDefaults {
    const val PREFS = "astra_intervention"
    const val DEFAULT_WINDOW_MINUTES = 60
    const val DEFAULT_COOLDOWN_MINUTES = 20

    fun defaultState() = InterventionState()
}

fun defaultTrackedApps(): List<InterventionTrackedApp> = listOf(
    InterventionTrackedApp(packageName = "com.google.android.youtube", label = "YouTube", thresholdMinutes = 20),
    InterventionTrackedApp(packageName = "com.zhiliaoapp.musically", label = "TikTok", thresholdMinutes = 20)
)

class InterventionRepository(context: Context) {
    private val prefs = context.getSharedPreferences(InterventionDefaults.PREFS, Context.MODE_PRIVATE)

    fun getState(): InterventionState {
        val raw = prefs.getString("state_json", null)
        if (raw.isNullOrBlank()) {
            val defaults = InterventionDefaults.defaultState()
            saveState(defaults)
            return defaults
        }
        return runCatching { parse(JSONObject(raw)) }.getOrElse { InterventionDefaults.defaultState() }
    }

    fun saveState(state: InterventionState) {
        prefs.edit().putString("state_json", toJson(state).toString()).apply()
    }

    fun saveLastPopupAt(packageName: String, atMs: Long) {
        prefs.edit().putLong("last_popup_$packageName", atMs).apply()
    }

    fun getLastPopupAt(packageName: String): Long = prefs.getLong("last_popup_$packageName", 0L)

    private fun parse(obj: JSONObject): InterventionState {
        val tracked = obj.optJSONArray("trackedApps") ?: JSONArray()
        return InterventionState(
            enabled = obj.optBoolean("enabled", true),
            ttsEnabled = obj.optBoolean("ttsEnabled", true),
            voiceEnabled = obj.optBoolean("voiceEnabled", true),
            trackedApps = buildList {
                for (i in 0 until tracked.length()) {
                    val item = tracked.optJSONObject(i) ?: continue
                    add(
                        InterventionTrackedApp(
                            packageName = item.optString("packageName"),
                            label = item.optString("label").ifBlank { item.optString("packageName") },
                            enabled = item.optBoolean("enabled", true),
                            thresholdMinutes = item.optInt("thresholdMinutes", 20)
                        )
                    )
                }
            }.ifEmpty { defaultTrackedApps() },
            rollingWindowMinutes = obj.optInt("rollingWindowMinutes", InterventionDefaults.DEFAULT_WINDOW_MINUTES),
            cooldownMinutes = obj.optInt("cooldownMinutes", InterventionDefaults.DEFAULT_COOLDOWN_MINUTES)
        )
    }

    private fun toJson(state: InterventionState): JSONObject {
        return JSONObject().apply {
            put("enabled", state.enabled)
            put("ttsEnabled", state.ttsEnabled)
            put("voiceEnabled", state.voiceEnabled)
            put("rollingWindowMinutes", state.rollingWindowMinutes)
            put("cooldownMinutes", state.cooldownMinutes)
            put("trackedApps", JSONArray().apply {
                state.trackedApps.forEach { app ->
                    put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                        put("enabled", app.enabled)
                        put("thresholdMinutes", app.thresholdMinutes)
                    })
                }
            })
        }
    }
}
