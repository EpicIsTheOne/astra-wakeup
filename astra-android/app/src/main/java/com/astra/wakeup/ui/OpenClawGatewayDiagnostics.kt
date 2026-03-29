package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONObject

data class GatewayAuthIssue(
    val code: String,
    val summary: String,
    val guidance: String,
    val retryable: Boolean = false
)

object OpenClawGatewayDiagnostics {
    private const val PREF_LAST_HANDSHAKE_DEBUG = "gateway_last_handshake_debug"

    fun classify(error: String?): GatewayAuthIssue? {
        val message = error?.trim().orEmpty()
        if (message.isBlank()) return null
        val lower = message.lowercase()

        return when {
            "pairing required" in lower || "PAIRING_REQUIRED" in message -> GatewayAuthIssue(
                code = "PAIRING_REQUIRED",
                summary = "waiting for approval",
                guidance = "OpenClaw wants a fresh device approval. If this phone was already paired, the cached device token was likely invalidated and needs an explicit re-pair or reset."
            )
            "device token mismatch" in lower || "AUTH_DEVICE_TOKEN_MISMATCH" in message -> GatewayAuthIssue(
                code = "AUTH_DEVICE_TOKEN_MISMATCH",
                summary = "stale device token",
                guidance = "Cached device token is stale. The app will clear and retry once; if it keeps failing, re-pair the device.",
                retryable = true
            )
            "AUTH_TOKEN_MISSING" in message || "token missing" in lower -> GatewayAuthIssue(
                code = "AUTH_TOKEN_MISSING",
                summary = "gateway token missing",
                guidance = "Enter the shared gateway token for this OpenClaw instance, then try again."
            )
            "AUTH_TOKEN_MISMATCH" in message || "gateway token mismatch" in lower -> GatewayAuthIssue(
                code = "AUTH_TOKEN_MISMATCH",
                summary = "gateway token mismatch",
                guidance = "The saved shared token does not match the gateway. Update the token in app settings."
            )
            "AUTH_BOOTSTRAP_TOKEN_INVALID" in message || "bootstrap token" in lower && "invalid" in lower -> GatewayAuthIssue(
                code = "AUTH_BOOTSTRAP_TOKEN_INVALID",
                summary = "bootstrap token invalid",
                guidance = "Bootstrap token expired or invalid. Generate a new one and save it in settings."
            )
            "DEVICE_IDENTITY_REQUIRED" in message || "CONTROL_UI_DEVICE_IDENTITY_REQUIRED" in message -> GatewayAuthIssue(
                code = "DEVICE_IDENTITY_REQUIRED",
                summary = "device identity required",
                guidance = "Gateway requires signed device auth. Keep Android direct mode enabled and retry with this install's device identity."
            )
            "DEVICE_AUTH_SIGNATURE_INVALID" in message || "DEVICE_AUTH_NONCE_MISMATCH" in message || "DEVICE_AUTH_SIGNATURE_EXPIRED" in message -> GatewayAuthIssue(
                code = "DEVICE_AUTH_INVALID",
                summary = "device signature rejected",
                guidance = "Challenge-bound device signature was rejected. Reconnect and, if repeated, re-pair or inspect gateway version compatibility."
            )
            else -> null
        }
    }

    fun recordHandshake(
        context: Context,
        stage: String,
        config: OpenClawGatewayConfig,
        nonce: String? = null,
        connectParams: JSONObject? = null,
        helloPayload: JSONObject? = null,
        error: String? = null,
        extra: JSONObject? = null
    ) {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        val resolved = config.resolvedAuth()
        val device = connectParams?.optJSONObject("device")
        val auth = connectParams?.optJSONObject("auth")
        val payload = JSONObject().apply {
            put("stage", stage)
            put("recordedAtMs", System.currentTimeMillis())
            put("wsUrl", config.wsUrl)
            put("authMode", resolved.mode.name)
            put("hasGatewayToken", !config.gatewayToken.isNullOrBlank())
            put("hasBootstrapToken", !config.bootstrapToken.isNullOrBlank())
            put("hasDeviceToken", !config.deviceToken.isNullOrBlank())
            put("noncePresent", !nonce.isNullOrBlank())
            put("noncePreview", nonce?.take(16).orEmpty())
            put("authPayload", auth?.let { JSONObject(it.toString()) } ?: JSONObject())
            put("device", device?.let {
                JSONObject().apply {
                    put("id", it.optString("id"))
                    put("publicKeyPreview", it.optString("publicKey").take(24))
                    put("signaturePreview", it.optString("signature").take(24))
                    put("signedAt", it.optLong("signedAt"))
                    put("noncePreview", it.optString("nonce").take(16))
                    put("signatureVersion", it.optString("signatureVersion"))
                }
            } ?: JSONObject())
            put("helloAuth", helloPayload?.optJSONObject("auth")?.let { JSONObject(it.toString()) } ?: JSONObject())
            put("server", helloPayload?.optJSONObject("server")?.let { JSONObject(it.toString()) } ?: JSONObject())
            put("error", error.orEmpty())
            put("extra", extra ?: JSONObject())
        }
        prefs.edit().putString(PREF_LAST_HANDSHAKE_DEBUG, payload.toString()).apply()
    }

    fun lastHandshakeDebug(context: Context): String {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_LAST_HANDSHAKE_DEBUG, null).orEmpty()
        if (raw.isBlank()) return "-"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.take(280)
        val device = json.optJSONObject("device")
        val auth = json.optJSONObject("authPayload")
        val helloAuth = json.optJSONObject("helloAuth")
        return buildString {
            append("stage=")
            append(json.optString("stage").ifBlank { "?" })
            append(" authMode=")
            append(json.optString("authMode").ifBlank { "?" })
            append(" nonce=")
            append(if (json.optBoolean("noncePresent")) "yes" else "no")
            auth?.let {
                if (it.length() > 0) {
                    append(" authKeys=")
                    append(it.keys().asSequence().asIterable().joinToString(","))
                }
            }
            device?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                append(" deviceId=")
                append(it.take(16))
            }
            device?.optString("signatureVersion")?.takeIf { it.isNotBlank() }?.let {
                append(" sig=")
                append(it)
            }
            helloAuth?.optString("deviceToken")?.takeIf { it.isNotBlank() }?.let {
                append(" helloDeviceToken=yes")
            }
            json.optString("error").takeIf { it.isNotBlank() }?.let {
                append(" error=")
                append(it.take(120))
            }
        }
    }

    fun describeStatus(context: Context, chatError: String?): String {
        val auth = OpenClawGatewayAuthStore.authDebugSummary(context)
        val issue = classify(chatError)
        val handshake = lastHandshakeDebug(context)
        return if (issue != null) {
            "${issue.summary}; ${issue.guidance} [$auth] [handshake=$handshake]"
        } else {
            "$auth [handshake=$handshake]"
        }
    }
}
