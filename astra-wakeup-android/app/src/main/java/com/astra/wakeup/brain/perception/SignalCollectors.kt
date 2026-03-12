package com.astra.wakeup.brain.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class SignalCollectors(private val context: Context) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val event = when (action) {
                    Intent.ACTION_USER_PRESENT -> ContextEvent("PHONE_UNLOCK")
                    Intent.ACTION_POWER_CONNECTED -> ContextEvent("CHARGING_CHANGED", data = mapOf("charging" to "true"))
                    Intent.ACTION_POWER_DISCONNECTED -> ContextEvent("CHARGING_CHANGED", data = mapOf("charging" to "false"))
                    Intent.ACTION_HEADSET_PLUG -> {
                        val connected = intent.getIntExtra("state", 0) == 1
                        ContextEvent("HEADPHONE_CHANGED", data = mapOf("connected" to connected.toString()))
                    }
                    Intent.ACTION_TIME_TICK -> ContextEvent("TIME_TICK")
                    else -> null
                } ?: return
                EventBus.publish(event)
            }
        }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_TIME_TICK)
        }
        context.registerReceiver(receiver, f)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
