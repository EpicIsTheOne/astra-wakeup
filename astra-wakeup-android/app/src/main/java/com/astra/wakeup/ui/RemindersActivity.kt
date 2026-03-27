package com.astra.wakeup.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import java.util.Calendar

class RemindersActivity : AppCompatActivity() {
    private var editingReminderId: String? = null
    private var pickedTimeMillis = System.currentTimeMillis() + 60 * 60 * 1000L
    private var manageMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminders)

        val spImportance = findViewById<Spinner>(R.id.spReminderImportance)
        val spAnnoyance = findViewById<Spinner>(R.id.spReminderAnnoyance)
        val spRepeat = findViewById<Spinner>(R.id.spReminderRepeat)
        val spTask = findViewById<Spinner>(R.id.spTaskLink)
        listOf(spImportance, spAnnoyance, spRepeat).forEach { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, when (it.id) {
            R.id.spReminderImportance -> listOf("Normal", "Important", "Critical")
            R.id.spReminderAnnoyance -> listOf("Gentle", "Pushy", "Chaotic")
            else -> listOf("Once", "Daily", "Weekly")
        }) }

        findViewById<Button>(R.id.btnPickReminderTime).setOnClickListener { pickDateTime() }
        findViewById<Button>(R.id.btnSaveReminder).setOnClickListener { saveReminder() }
        findViewById<Button>(R.id.btnCancelEditReminder).setOnClickListener { clearReminderForm() }
        findViewById<Button>(R.id.btnToggleManageMode).setOnClickListener {
            manageMode = !manageMode
            refresh()
        }
        findViewById<Button>(R.id.btnSaveTaskBoardItem).setOnClickListener { saveTask() }
        findViewById<Button>(R.id.btnClearTaskBoardItem).setOnClickListener { clearTaskForm() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvReminderPickedTime).text = formatTimestamp(pickedTimeMillis)
        findViewById<Button>(R.id.btnToggleManageMode).text = if (manageMode) "Leave manage mode" else "Manage all reminders"
        val tasks = ReminderRepository.listTasks(this)
        val taskAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No linked task") + tasks.map { it.title })
        findViewById<Spinner>(R.id.spTaskLink).adapter = taskAdapter
        refreshTaskReminderSpinner()
        renderReminders()
        renderTasks()
    }

    private fun pickDateTime() {
        val calendar = Calendar.getInstance().apply { timeInMillis = pickedTimeMillis }
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            TimePickerDialog(this, { _, hour, minute ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                pickedTimeMillis = picked.timeInMillis
                refresh()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveReminder() {
        val title = findViewById<EditText>(R.id.etReminderTitle).text.toString().trim()
        if (title.isBlank()) return toast("Reminder text required")
        val tasks = ReminderRepository.listTasks(this)
        val taskSelection = findViewById<Spinner>(R.id.spTaskLink).selectedItemPosition - 1
        val repeatRule = when (findViewById<Spinner>(R.id.spReminderRepeat).selectedItemPosition) {
            1 -> "daily"
            2 -> "weekly"
            else -> "once"
        }
        val item = ReminderItem(
            id = editingReminderId ?: java.util.UUID.randomUUID().toString(),
            title = title,
            scheduledTimeMillis = pickedTimeMillis,
            importance = findViewById<Spinner>(R.id.spReminderImportance).selectedItemPosition + 1,
            annoyanceLevel = findViewById<Spinner>(R.id.spReminderAnnoyance).selectedItemPosition + 1,
            verifyLater = findViewById<CheckBox>(R.id.cbVerifyLater).isChecked,
            repeatRule = repeatRule,
            enabled = findViewById<CheckBox>(R.id.cbReminderEnabled).isChecked,
            followUpState = "scheduled",
            linkedTaskId = tasks.getOrNull(taskSelection)?.id
        )
        ReminderRepository.upsertReminder(this, item)
        item.linkedTaskId?.let { taskId ->
            ReminderRepository.getTask(this, taskId)?.let { task ->
                ReminderRepository.upsertTask(this, task.copy(linkedReminderId = item.id))
            }
        }
        clearReminderForm()
        refresh()
        toast("Reminder saved")
    }

    private fun clearReminderForm() {
        editingReminderId = null
        findViewById<EditText>(R.id.etReminderTitle).setText("")
        findViewById<Spinner>(R.id.spReminderImportance).setSelection(1)
        findViewById<Spinner>(R.id.spReminderAnnoyance).setSelection(1)
        findViewById<Spinner>(R.id.spReminderRepeat).setSelection(0)
        findViewById<Spinner>(R.id.spTaskLink).setSelection(0)
        findViewById<CheckBox>(R.id.cbVerifyLater).isChecked = false
        findViewById<CheckBox>(R.id.cbReminderEnabled).isChecked = true
        pickedTimeMillis = System.currentTimeMillis() + 60 * 60 * 1000L
        findViewById<TextView>(R.id.tvReminderEditorTitle).text = "Create reminder"
        refresh()
    }

    private fun renderReminders() {
        val container = findViewById<LinearLayout>(R.id.layoutReminderList)
        container.removeAllViews()
        val items = if (manageMode) ReminderRepository.manageReminderFeed(this) else ReminderRepository.defaultReminderFeed(this)
        findViewById<TextView>(R.id.tvReminderListMode).text = if (manageMode) "All reminders" else "Important upcoming reminders"
        if (items.isEmpty()) {
            container.addView(TextView(this).apply { text = if (manageMode) "No reminders yet." else "No important reminders queued."; setTextColor(0xFFCBD5E1.toInt()) })
            return
        }
        items.forEach { item ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(0xFF162033.toInt())
                val titleView = TextView(context).apply {
                    text = item.title
                    textSize = 18f
                    setTextColor(0xFFF8FAFC.toInt())
                }
                val metaView = TextView(context).apply {
                    text = "${importanceLabel(item.importance)} · ${annoyanceLabel(item.annoyanceLevel)} · ${formatTimestamp(item.scheduledTimeMillis)}\n${if (item.enabled) "Enabled" else "Paused"} · ${item.repeatRule} · snoozes ${item.snoozeCount} · ${item.followUpState}"
                    setTextColor(0xFFCBD5E1.toInt())
                }
                addView(titleView)
                addView(metaView)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val edit = Button(context).apply { text = "Edit"; setOnClickListener { loadReminder(item) } }
                    val toggle = Button(context).apply { text = if (item.enabled) "Disable" else "Enable"; setOnClickListener { ReminderRepository.upsertReminder(context, item.copy(enabled = !item.enabled)); refresh() } }
                    val later = Button(context).apply { text = "Later"; visibility = if (manageMode) View.VISIBLE else View.GONE; setOnClickListener { ReminderRepository.upsertReminder(context, item.copy(scheduledTimeMillis = ReminderScheduler.computeLaterTime(item), snoozeCount = item.snoozeCount + 1)); refresh() } }
                    val delete = Button(context).apply { text = "Delete"; visibility = if (manageMode) View.VISIBLE else View.GONE; setOnClickListener { ReminderRepository.deleteReminder(context, item.id); refresh() } }
                    addView(edit)
                    addView(toggle)
                    addView(later)
                    addView(delete)
                })
            })
            container.addView(View(this).apply { minimumHeight = 16 })
        }
    }

    private fun loadReminder(item: ReminderItem) {
        editingReminderId = item.id
        findViewById<TextView>(R.id.tvReminderEditorTitle).text = "Edit reminder"
        findViewById<EditText>(R.id.etReminderTitle).setText(item.title)
        findViewById<Spinner>(R.id.spReminderImportance).setSelection(item.importance - 1)
        findViewById<Spinner>(R.id.spReminderAnnoyance).setSelection(item.annoyanceLevel - 1)
        findViewById<Spinner>(R.id.spReminderRepeat).setSelection(when (item.repeatRule) { "daily" -> 1; "weekly" -> 2; else -> 0 })
        findViewById<CheckBox>(R.id.cbVerifyLater).isChecked = item.verifyLater
        findViewById<CheckBox>(R.id.cbReminderEnabled).isChecked = item.enabled
        pickedTimeMillis = item.scheduledTimeMillis
        refresh()
        val tasks = ReminderRepository.listTasks(this)
        findViewById<Spinner>(R.id.spTaskLink).setSelection((tasks.indexOfFirst { it.id == item.linkedTaskId }).takeIf { it >= 0 }?.plus(1) ?: 0)
    }

    private fun renderTasks() {
        val container = findViewById<LinearLayout>(R.id.layoutTaskBoard)
        container.removeAllViews()
        val tasks = ReminderRepository.listTasks(this)
        if (tasks.isEmpty()) {
            container.addView(TextView(this).apply { text = "No tasks yet."; setTextColor(0xFFCBD5E1.toInt()) })
            return
        }
        tasks.forEach { task ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(0xFF162033.toInt())
                addView(TextView(context).apply { text = if (task.done) "✓ ${task.title}" else task.title; textSize = 17f; setTextColor(0xFFF8FAFC.toInt()) })
                addView(TextView(context).apply {
                    val linked = task.linkedReminderId?.let { ReminderRepository.getReminder(context, it)?.summary() } ?: "No reminder linked"
                    text = "${task.notes.ifBlank { "No notes" }}\n$linked"
                    setTextColor(0xFFCBD5E1.toInt())
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(context).apply { text = if (task.done) "Undo" else "Done"; setOnClickListener { ReminderRepository.upsertTask(context, task.copy(done = !task.done, completedAtMillis = if (task.done) 0L else System.currentTimeMillis())); refresh() } })
                    addView(Button(context).apply { text = "Edit"; setOnClickListener { loadTask(task) } })
                    addView(Button(context).apply { text = "Delete"; setOnClickListener { ReminderRepository.deleteTask(context, task.id); refresh() } })
                })
            })
            container.addView(View(this).apply { minimumHeight = 16 })
        }
    }

    private fun saveTask() {
        val id = findViewById<TextView>(R.id.tvEditingTaskId).text.toString().ifBlank { java.util.UUID.randomUUID().toString() }
        val title = findViewById<EditText>(R.id.etTaskBoardTitle).text.toString().trim()
        if (title.isBlank()) return toast("Task title required")
        val reminders = ReminderRepository.listReminders(this)
        val selection = findViewById<Spinner>(R.id.spTaskReminderLink).selectedItemPosition - 1
        val linkedReminderId = reminders.getOrNull(selection)?.id
        ReminderRepository.upsertTask(this, TaskBoardItem(id = id, title = title, notes = findViewById<EditText>(R.id.etTaskBoardNotes).text.toString().trim(), linkedReminderId = linkedReminderId))
        linkedReminderId?.let { reminderId ->
            ReminderRepository.getReminder(this, reminderId)?.let { reminder ->
                ReminderRepository.upsertReminder(this, reminder.copy(linkedTaskId = id))
            }
        }
        clearTaskForm()
        refresh()
        toast("Task saved")
    }

    private fun loadTask(task: TaskBoardItem) {
        findViewById<TextView>(R.id.tvEditingTaskId).text = task.id
        findViewById<EditText>(R.id.etTaskBoardTitle).setText(task.title)
        findViewById<EditText>(R.id.etTaskBoardNotes).setText(task.notes)
        val reminders = ReminderRepository.listReminders(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No linked reminder") + reminders.map { it.summary() })
        findViewById<Spinner>(R.id.spTaskReminderLink).adapter = adapter
        findViewById<Spinner>(R.id.spTaskReminderLink).setSelection((reminders.indexOfFirst { it.id == task.linkedReminderId }).takeIf { it >= 0 }?.plus(1) ?: 0)
    }

    private fun clearTaskForm() {
        findViewById<TextView>(R.id.tvEditingTaskId).text = ""
        findViewById<EditText>(R.id.etTaskBoardTitle).setText("")
        findViewById<EditText>(R.id.etTaskBoardNotes).setText("")
        refreshTaskReminderSpinner()
    }

    private fun refreshTaskReminderSpinner() {
        val reminders = ReminderRepository.listReminders(this)
        findViewById<Spinner>(R.id.spTaskReminderLink).adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No linked reminder") + reminders.map { it.summary() })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
