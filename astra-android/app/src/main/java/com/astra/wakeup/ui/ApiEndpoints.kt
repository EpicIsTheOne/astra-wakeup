package com.astra.wakeup.ui

object ApiEndpoints {
    // Accept either full endpoint (/api/astra or /api/wakeup/line) or base host URL.
    fun normalizeBase(apiUrl: String): String {
        val u = apiUrl.trim().trimEnd('/')
        return when {
            u.endsWith("/api/astra") -> u
            u.endsWith("/api/wakeup/line") -> u.substringBeforeLast("/api/wakeup/line") + "/api/astra"
            u.contains("/api/") -> u.substringBefore("/api/") + "/api/astra"
            else -> "$u/api/astra"
        }
    }

    fun line(apiUrl: String) = "${normalizeBase(apiUrl)}/line"
    fun fm(apiUrl: String) = "${normalizeBase(apiUrl)}/fm"
    fun wakeRespond(apiUrl: String) = "${normalizeBase(apiUrl)}/wake-respond"
    fun chatRespond(apiUrl: String) = "${normalizeBase(apiUrl)}/respond"
    fun health(apiUrl: String) = "${normalizeBase(apiUrl)}/health"
}
