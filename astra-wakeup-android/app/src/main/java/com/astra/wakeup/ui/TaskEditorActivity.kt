package com.astra.wakeup.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astra.wakeup.R
import com.astra.wakeup.brain.actions.Action
import com.astra.wakeup.brain.tasks.Task
import com.astra.wakeup.brain.tasks.TaskStep
import com.astra.wakeup.brain.tasks.TaskStorage

class TaskEditorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_editor)

        val etId = findViewById<EditText>(R.id.etTaskId)
        val etDesc = findViewById<EditText>(R.id.etTaskDesc)
        val sp = findViewById<Spinner>(R.id.spTaskTrigger)
        val etSpeech = findViewById<EditText>(R.id.etTaskSpeech)
        val etNotify = findViewById<EditText>(R.id.etTaskNotify)
        val spPersona = findViewById<Spinner>(R.id.spTaskPersonality)
        val etDelaySpeech = findViewById<EditText>(R.id.etDelaySpeechMs)
        val etDelayNotify = findViewById<EditText>(R.id.etDelayNotifyMs)
        val etDelayPersona = findViewById<EditText>(R.id.etDelayPersonaMs)
        val tv = findViewById<TextView>(R.id.tvTasks)

        fun selectedTriggerIndex(trigger: String): Int {
            val arr = resources.getStringArray(R.array.context_triggers)
            return arr.indexOf(trigger).coerceAtLeast(0)
        }

        fun selectedPersonaIndex(mode: String): Int {
            val arr = resources.getStringArray(R.array.personality_modes)
            return arr.indexOf(mode).coerceAtLeast(0)
        }

        fun refresh() {
            val tasks = TaskStorage.list(this)
            tv.text = if (tasks.isEmpty()) "No custom tasks" else tasks.joinToString("\n\n") { t ->
                val steps = t.steps.joinToString(" | ") { s ->
                    val label = when (val a = s.action) {
                        is Action.Speak -> "speak:${a.text}"
                        is Action.ShowNotification -> "notify:${a.text}"
                        is Action.ChangePersonality -> "persona:${a.mode}"
                        is Action.Log -> "log:${a.message}"
                    }
                    "$label@${s.delayMs}ms"
                }
                "${t.id} (${t.trigger}) -> ${t.description}\n$steps"
            }
        }

        fun clearForm() {
            etId.setText("")
            etDesc.setText("")
            etSpeech.setText("")
            etNotify.setText("")
            etDelaySpeech.setText("")
            etDelayNotify.setText("")
            etDelayPersona.setText("")
            sp.setSelection(0)
            spPersona.setSelection(0)
        }

        findViewById<Button>(R.id.btnLoadTask).setOnClickListener {
            val id = etId.text.toString().trim()
            if (id.isBlank()) {
                Toast.makeText(this, "Enter Task ID to load", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val t = TaskStorage.get(this, id)
            if (t == null) {
                Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            etDesc.setText(t.description)
            sp.setSelection(selectedTriggerIndex(t.trigger))

            val speak = t.steps.firstOrNull { it.action is Action.Speak }
            val notify = t.steps.firstOrNull { it.action is Action.ShowNotification }
            val persona = t.steps.firstOrNull { it.action is Action.ChangePersonality }

            etSpeech.setText((speak?.action as? Action.Speak)?.text ?: "")
            etNotify.setText((notify?.action as? Action.ShowNotification)?.text ?: "")
            spPersona.setSelection(selectedPersonaIndex((persona?.action as? Action.ChangePersonality)?.mode ?: "coach"))
            etDelaySpeech.setText((speak?.delayMs ?: 0L).takeIf { it > 0 }?.toString() ?: "")
            etDelayNotify.setText((notify?.delayMs ?: 0L).takeIf { it > 0 }?.toString() ?: "")
            etDelayPersona.setText((persona?.delayMs ?: 0L).takeIf { it > 0 }?.toString() ?: "")
            Toast.makeText(this, "Task loaded", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnDeleteTask).setOnClickListener {
            val id = etId.text.toString().trim()
            if (id.isBlank()) {
                Toast.makeText(this, "Enter Task ID to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TaskStorage.delete(this, id)
            Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show()
            clearForm()
            refresh()
        }

        findViewById<Button>(R.id.btnSaveTask).setOnClickListener {
            val id = etId.text.toString().trim()
            val desc = etDesc.text.toString().trim()
            val trig = sp.selectedItem.toString()
            val speech = etSpeech.text.toString().trim()
            val notify = etNotify.text.toString().trim()
            val persona = spPersona.selectedItem.toString().trim()

            if (id.isBlank()) {
                Toast.makeText(this, "Task ID required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val steps = mutableListOf<TaskStep>()
            if (speech.isNotBlank()) {
                steps += TaskStep("speak", Action.Speak(speech), etDelaySpeech.text.toString().toLongOrNull() ?: 0L)
            }
            if (notify.isNotBlank()) {
                steps += TaskStep("notify", Action.ShowNotification(notify), etDelayNotify.text.toString().toLongOrNull() ?: 0L)
            }
            // personality step always allowed for multi-step builder
            steps += TaskStep("persona", Action.ChangePersonality(persona), etDelayPersona.text.toString().toLongOrNull() ?: 0L)

            if (steps.isEmpty()) {
                Toast.makeText(this, "Add at least one step", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            TaskStorage.upsert(
                this,
                Task(
                    id = id,
                    description = if (desc.isBlank()) id else desc,
                    trigger = trig,
                    steps = steps,
                    priority = 60
                )
            )
            Toast.makeText(this, "Task saved", Toast.LENGTH_SHORT).show()
            refresh()
        }

        refresh()
    }
}
