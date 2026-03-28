package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONArray

object ReminderRepository {
    private const val PREF = "astra_reminders"
    private const val KEY_REMINDERS = "items"
    private const val KEY_TASKS = "task_board"

    fun listReminders(context: Context): List<ReminderItem> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_REMINDERS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(ReminderItem.fromJson(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList()).sortedBy { it.scheduledTimeMillis }
    }

    fun listTasks(context: Context): List<TaskBoardItem> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TASKS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(TaskBoardItem.fromJson(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList()).sortedWith(compareBy<TaskBoardItem> { it.done }.thenBy { it.createdAtMillis })
    }

    fun getReminder(context: Context, id: String): ReminderItem? = listReminders(context).firstOrNull { it.id == id }
    fun getTask(context: Context, id: String): TaskBoardItem? = listTasks(context).firstOrNull { it.id == id }

    fun upsertReminder(context: Context, item: ReminderItem) {
        val updated = listReminders(context).filterNot { it.id == item.id } + item
        saveReminders(context, updated)
        if (item.enabled && item.scheduledTimeMillis > System.currentTimeMillis() + 1000L) {
            ReminderScheduler.scheduleReminder(context, item)
        } else {
            ReminderScheduler.cancelReminder(context, item.id)
        }
    }

    fun deleteReminder(context: Context, id: String) {
        ReminderScheduler.cancelReminder(context, id)
        saveReminders(context, listReminders(context).filterNot { it.id == id })
        val tasks = listTasks(context).map { if (it.linkedReminderId == id) it.copy(linkedReminderId = null) else it }
        saveTasks(context, tasks)
    }

    fun upsertTask(context: Context, item: TaskBoardItem) {
        val updated = listTasks(context).filterNot { it.id == item.id } + item
        saveTasks(context, updated)
    }

    fun deleteTask(context: Context, id: String) {
        saveTasks(context, listTasks(context).filterNot { it.id == id })
        val reminders = listReminders(context).map { if (it.linkedTaskId == id) it.copy(linkedTaskId = null) else it }
        saveReminders(context, reminders)
        reminders.forEach { ReminderScheduler.scheduleReminder(context, it) }
    }

    fun defaultReminderFeed(context: Context, now: Long = System.currentTimeMillis()): List<ReminderItem> =
        listReminders(context).filter { it.enabled && it.scheduledTimeMillis >= now && it.importance >= 2 }.sortedBy { it.scheduledTimeMillis }

    fun manageReminderFeed(context: Context): List<ReminderItem> = listReminders(context)

    fun saveReminders(context: Context, reminders: List<ReminderItem>) {
        val arr = JSONArray()
        reminders.sortedBy { it.scheduledTimeMillis }.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }

    fun saveTasks(context: Context, tasks: List<TaskBoardItem>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_TASKS, arr.toString()).apply()
    }
}
