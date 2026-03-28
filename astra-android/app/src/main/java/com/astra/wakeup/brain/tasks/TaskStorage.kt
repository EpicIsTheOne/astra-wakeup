package com.astra.wakeup.brain.tasks

import android.content.Context
import com.astra.wakeup.brain.actions.Action
import org.json.JSONArray
import org.json.JSONObject

object TaskStorage {
    private const val PREF = "astra_tasks"
    private const val KEY = "custom_tasks"

    fun list(context: Context): List<Task> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                    val steps = mutableListOf<TaskStep>()
                    for (j in 0 until stepsArr.length()) {
                        val s = stepsArr.getJSONObject(j)
                        val type = s.optString("type")
                        val p1 = s.optString("p1")
                        val action = when (type) {
                            "speak" -> Action.Speak(p1)
                            "notify" -> Action.ShowNotification(p1)
                            "personality" -> Action.ChangePersonality(p1)
                            else -> Action.Log("info", p1)
                        }
                        steps += TaskStep(s.optString("id", "step$j"), action, s.optLong("delayMs", 0L))
                    }
                    add(Task(o.optString("id"), o.optString("description"), o.optString("trigger"), steps, o.optInt("priority", 50)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("description", t.description)
            o.put("trigger", t.trigger)
            o.put("priority", t.priority)
            val sArr = JSONArray()
            t.steps.forEach { s ->
                val so = JSONObject()
                so.put("id", s.id)
                so.put("delayMs", s.delayMs)
                when (val a = s.action) {
                    is Action.Speak -> { so.put("type", "speak"); so.put("p1", a.text) }
                    is Action.ShowNotification -> { so.put("type", "notify"); so.put("p1", a.text) }
                    is Action.ChangePersonality -> { so.put("type", "personality"); so.put("p1", a.mode) }
                    is Action.Log -> { so.put("type", "log"); so.put("p1", a.message) }
                }
                sArr.put(so)
            }
            o.put("steps", sArr)
            arr.put(o)
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(context: Context, task: Task) {
        val tasks = list(context).filterNot { it.id == task.id }.toMutableList()
        tasks += task
        save(context, tasks)
    }

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun get(context: Context, id: String): Task? = list(context).firstOrNull { it.id == id }
}
