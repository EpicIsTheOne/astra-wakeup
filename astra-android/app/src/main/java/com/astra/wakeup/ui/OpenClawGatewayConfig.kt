package com.astra.wakeup.ui

import android.content.Context
import java.util.UUID

private const val ASTRA_PREFS = "astra"
private const val DEFAULT_ASTRA_BASE_URL = "https://techexplore.us"

enum class GatewayAuthMode {
    NONE,
    SHARED_TOKEN,
    BOOTSTRAP,
    DEVICE_TOKEN
}

data class GatewayResolvedAuth(
    val mode: GatewayAuthMode,
    val payload: org.json.JSONObject?,
    val signatureToken: String?
)

/**
 * Minimal persisted config scaffold for direct Gateway access.
 * This intentionally reuses the old api_url field so existing installs keep working.
 */
data class OpenClawGatewayConfig(
    val httpBaseUrl: String,
    val wsUrl: String,
    val gatewayToken: String? = null,
    val bootstrapToken: String? = null,
    val deviceToken: String? = null,
    val insecureLocalAuthAllowed: Boolean = false,
    val sessionKey: String = "main"
) {
    fun resolvedAuth(): GatewayResolvedAuth {
        deviceToken?.takeIf { it.isNotBlank() }?.let { token ->
            return GatewayResolvedAuth(
                mode = GatewayAuthMode.DEVICE_TOKEN,
                payload = org.json.JSONObject().put("deviceToken", token),
                signatureToken = token
            )
        }

        bootstrapToken?.takeIf { it.isNotBlank() }?.let { token ->
            return GatewayResolvedAuth(
                mode = GatewayAuthMode.BOOTSTRAP,
                payload = org.json.JSONObject().put("bootstrapToken", token),
                signatureToken = token
            )
        }

        gatewayToken?.takeIf { it.isNotBlank() }?.let { token ->
            return GatewayResolvedAuth(
                mode = GatewayAuthMode.SHARED_TOKEN,
                payload = org.json.JSONObject().put("token", token),
                signatureToken = token
            )
        }

        return GatewayResolvedAuth(
            mode = GatewayAuthMode.NONE,
            payload = null,
            signatureToken = null
        )
    }

    fun authMode(): GatewayAuthMode = resolvedAuth().mode

    fun preferredAuthToken(): String? = resolvedAuth().signatureToken

    fun effectiveAuthPayload(): org.json.JSONObject? = resolvedAuth().payload

    companion object {
        fun fromContext(context: Context): OpenClawGatewayConfig {
            runCatching { OpenClawGatewayAuthStore.ensureScaffold(context) }
            val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
            val storedApiUrl = prefs.getString("api_url", "") ?: ""
            val apiUrl = storedApiUrl.ifBlank { DEFAULT_ASTRA_BASE_URL }
            if (storedApiUrl.isBlank()) {
                prefs.edit().putString("api_url", apiUrl).apply()
            }
            return fromPrefsApiUrl(
                apiUrl = apiUrl,
                gatewayToken = prefs.getString("gateway_token", null),
                bootstrapToken = prefs.getString("gateway_bootstrap_token", null),
                deviceToken = prefs.getString("gateway_device_token", null),
                insecureLocalAuthAllowed = prefs.getBoolean("gateway_insecure_local_auth_allowed", false),
                sessionKey = prefs.getString("gateway_session_key", "main") ?: "main"
            )
        }

        fun fromPrefsApiUrl(
            apiUrl: String,
            gatewayToken: String? = null,
            bootstrapToken: String? = null,
            deviceToken: String? = null,
            insecureLocalAuthAllowed: Boolean = false,
            sessionKey: String = "main"
        ): OpenClawGatewayConfig {
            val base = apiUrl.trim().trimEnd('/').ifBlank { DEFAULT_ASTRA_BASE_URL }
            val normalizedHttp = when {
                base.isBlank() -> ""
                base.endsWith("/api/astra") -> base.removeSuffix("/api/astra")
                base.contains("/api/") -> base.substringBefore("/api/")
                else -> base
            }
            val wsUrl = when {
                normalizedHttp.isBlank() -> ""
                else -> normalizedHttp
                    .replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://") + "/gateway"
            }
            return OpenClawGatewayConfig(
                httpBaseUrl = normalizedHttp,
                wsUrl = wsUrl,
                gatewayToken = gatewayToken?.takeIf { it.isNotBlank() },
                bootstrapToken = bootstrapToken?.takeIf { it.isNotBlank() },
                deviceToken = deviceToken?.takeIf { it.isNotBlank() },
                insecureLocalAuthAllowed = insecureLocalAuthAllowed,
                sessionKey = sessionKey.ifBlank { "main" }
            )
        }
    }
}

object OpenClawGatewayAuthStore {
    fun ensureScaffold(context: Context) {
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("gateway_device_id")) {
            prefs.edit()
                .putString("gateway_device_id", "android-${UUID.randomUUID()}")
                .apply()
        }

        OpenClawGatewayCrypto.ensureDeviceIdentity(context)
            .onSuccess { identity ->
                prefs.edit().putString("gateway_device_id", identity.deviceId).apply()
            }
            .onFailure {
                prefs.edit()
                    .putString("gateway_device_id", "android-${UUID.randomUUID()}")
                    .apply()

                OpenClawGatewayCrypto.ensureDeviceIdentity(context)
                    .onSuccess { identity ->
                        prefs.edit().putString("gateway_device_id", identity.deviceId).apply()
                    }
            }

        if (!prefs.contains("gateway_session_key")) {
            prefs.edit().putString("gateway_session_key", "main").apply()
        }

        migrateLegacyAuthState(prefs)
    }

    fun persistHelloAuth(context: Context, helloPayload: org.json.JSONObject) {
        val auth = helloPayload.optJSONObject("auth") ?: return
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("gateway_last_auth_json", auth.toString())
        auth.optString("deviceToken").takeIf { it.isNotBlank() }?.let {
            editor.putString("gateway_device_token", it)
            editor.remove("gateway_bootstrap_token")
            editor.remove("gateway_pairing_code")
        }
        auth.optString("role").takeIf { it.isNotBlank() }?.let {
            editor.putString("gateway_last_role", it)
        }
        auth.optJSONArray("scopes")?.let {
            editor.putString("gateway_last_scopes", it.toString())
        }
        auth.optLong("issuedAtMs", 0L).takeIf { it > 0L }?.let {
            editor.putLong("gateway_device_token_issued_at", it)
        }
        helloPayload.optJSONObject("server")?.optString("version")?.takeIf { it.isNotBlank() }?.let {
            editor.putString("gateway_server_version", it)
        }
        helloPayload.optJSONObject("features")?.optJSONArray("methods")?.let {
            editor.putString("gateway_last_methods", it.toString())
        }
        helloPayload.optJSONObject("features")?.optJSONArray("events")?.let {
            editor.putString("gateway_last_events", it.toString())
        }
        editor.apply()
        migrateLegacyAuthState(prefs)
    }

    fun clearDeviceToken(context: Context) {
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("gateway_device_token")
            .remove("gateway_device_token_issued_at")
            .apply()
        migrateLegacyAuthState(prefs)
    }

    fun clearAllGatewayAuth(context: Context) {
        context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("gateway_token")
            .remove("gateway_bootstrap_token")
            .remove("gateway_device_token")
            .remove("gateway_device_token_issued_at")
            .remove("gateway_last_role")
            .remove("gateway_last_scopes")
            .remove("gateway_last_auth_json")
            .remove("gateway_last_methods")
            .remove("gateway_last_events")
            .remove("gateway_server_version")
            .apply()
    }

    fun authDebugSummary(context: Context): String {
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        val hasSharedToken = !prefs.getString("gateway_token", null).isNullOrBlank()
        val hasBootstrapToken = !prefs.getString("gateway_bootstrap_token", null).isNullOrBlank()
        val hasDeviceToken = !prefs.getString("gateway_device_token", null).isNullOrBlank()
        val role = prefs.getString("gateway_last_role", null).orEmpty()
        val lastAuth = prefs.getString("gateway_last_auth_json", null).orEmpty().take(180)
        val mode = currentAuthMode(prefs).name.lowercase()
        return "mode=$mode shared=$hasSharedToken bootstrap=$hasBootstrapToken device=$hasDeviceToken role=${role.ifBlank { "?" }} lastAuth=${lastAuth.ifBlank { "-" }}"
    }

    private fun migrateLegacyAuthState(prefs: android.content.SharedPreferences) {
        val mode = currentAuthMode(prefs)
        prefs.edit().putString("gateway_auth_mode", mode.name).apply()
    }

    private fun currentAuthMode(prefs: android.content.SharedPreferences): GatewayAuthMode {
        return when {
            !prefs.getString("gateway_device_token", null).isNullOrBlank() -> GatewayAuthMode.DEVICE_TOKEN
            !prefs.getString("gateway_bootstrap_token", null).isNullOrBlank() -> GatewayAuthMode.BOOTSTRAP
            !prefs.getString("gateway_token", null).isNullOrBlank() -> GatewayAuthMode.SHARED_TOKEN
            else -> GatewayAuthMode.NONE
        }
    }
}
