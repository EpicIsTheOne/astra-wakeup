package com.astra.wakeup.ui

import android.content.Context

data class GatewayAuthIssue(
    val code: String,
    val summary: String,
    val guidance: String,
    val retryable: Boolean = false
)

object OpenClawGatewayDiagnostics {
    fun classify(error: String?): GatewayAuthIssue? {
        val message = error?.trim().orEmpty()
        if (message.isBlank()) return null
        val lower = message.lowercase()

        return when {
            "pairing required" in lower || "PAIRING_REQUIRED" in message -> GatewayAuthIssue(
                code = "PAIRING_REQUIRED",
                summary = "pairing required",
                guidance = "Gateway needs a paired device or bootstrap token. Add a bootstrap/shared token in settings or pair this device first."
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
                guidance = "Set a shared gateway token or bootstrap token in app settings."
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

    fun describeStatus(context: Context, chatError: String?): String {
        val auth = OpenClawGatewayAuthStore.authDebugSummary(context)
        val issue = classify(chatError)
        return if (issue != null) {
            "${issue.summary}; ${issue.guidance} [$auth]"
        } else {
            auth
        }
    }
}
