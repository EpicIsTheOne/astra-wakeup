package com.astra.wakeup.ui

import android.content.Context
import java.util.UUID

object NodeIdentity {
    fun getNodeInstanceId(context: Context): String {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        val existing = prefs.getString("gateway_node_instance_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "astra-android-${UUID.randomUUID()}"
        prefs.edit().putString("gateway_node_instance_id", created).apply()
        return created
    }

    fun getStableNodeId(context: Context): String {
        OpenClawGatewayAuthStore.ensureScaffold(context)
        return context.getSharedPreferences("astra", Context.MODE_PRIVATE)
            .getString("gateway_device_id", null)
            ?.takeIf { it.isNotBlank() }
            ?: "android-unknown"
    }
}
