package com.astra.wakeup.ui

import org.json.JSONArray
import org.json.JSONObject

data class WakePlan(
    val speech: String,
    val actions: JSONArray = JSONArray(),
    val raw: JSONObject = JSONObject()
)

object WakePlanParser {
    fun parse(rawText: String?): WakePlan {
        if (rawText.isNullOrBlank()) {
            return WakePlan("OpenClaw didn't answer. Check the connection and try again.")
        }
        val trimmed = rawText.trim()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
        if (obj == null) {
            return WakePlan(trimmed)
        }
        val speech = obj.optString("speech").ifBlank {
            obj.optString("reply").ifBlank { trimmed }
        }
        val actions = obj.optJSONArray("actions") ?: JSONArray()
        return WakePlan(speech = speech, actions = actions, raw = obj)
    }
}
