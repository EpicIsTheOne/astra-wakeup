package com.astra.wakeup.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BrainLogEvent(
    val atMs: Long,
    val level: String,
    val message: String
)

object BrainEventLog {
    private const val PREF = "astra_brain"
    private const val KEY = "event_log"

    fun append(context: Context, level: String, message: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("atMs", System.currentTimeMillis()).put("level", level).put("message", message.take(180)))
        while (arr.length() > 200) {
            val trimmed = JSONArray()
            for (i in 1 until arr.length()) trimmed.put(arr.get(i))
            prefs.edit().putString(KEY, trimmed.toString()).apply()
            return
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun list(context: Context, level: String = "all"): List<BrainLogEvent> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val out = mutableListOf<BrainLogEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ev = BrainLogEvent(o.optLong("atMs"), o.optString("level", "info"), o.optString("message", ""))
            if (level == "all" || ev.level.equals(level, true)) out += ev
        }
        return out.takeLast(80).reversed()
    }
}
