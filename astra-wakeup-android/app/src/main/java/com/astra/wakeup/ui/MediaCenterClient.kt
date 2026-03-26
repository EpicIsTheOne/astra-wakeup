package com.astra.wakeup.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class MediaCenterAsset(
    val id: String,
    val title: String,
    val collection: String,
    val publicUrl: String,
    val wakeEnabled: Boolean,
    val tags: List<String>,
    val useCases: List<String>,
    val notes: String?
)

object MediaCenterClient {
    private const val DEFAULT_MEDIA_CENTER_BASE = "https://techexplore.us/missioncontrol"

    fun baseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        return prefs.getString("media_center_base_url", DEFAULT_MEDIA_CENTER_BASE)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { DEFAULT_MEDIA_CENTER_BASE }
    }

    fun fetchWakeAssets(context: Context): List<MediaCenterAsset> {
        val conn = URL(baseUrl(context) + "/api/media-center").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        return BufferedReader(conn.inputStream.reader()).use { reader ->
            val text = reader.readText()
            parseAssets(JSONObject(text).optJSONArray("assets") ?: JSONArray())
                .filter { it.wakeEnabled && (it.collection == "wake-sfx" || it.collection == "wake-music") }
        }
    }

    fun assetCatalogText(assets: List<MediaCenterAsset>, limit: Int = 10): String {
        if (assets.isEmpty()) return "No wake-ready media assets are currently available."
        return assets.take(limit).joinToString(separator = "\n") { asset ->
            buildString {
                append("- title=")
                append(JSONObject.quote(asset.title))
                append(" collection=")
                append(asset.collection)
                append(" url=")
                append(asset.publicUrl)
                if (asset.useCases.isNotEmpty()) {
                    append(" useCases=")
                    append(JSONObject.quote(asset.useCases.joinToString(", ")))
                }
                if (asset.tags.isNotEmpty()) {
                    append(" tags=")
                    append(JSONObject.quote(asset.tags.joinToString(", ")))
                }
            }
        }
    }

    private fun parseAssets(array: JSONArray): List<MediaCenterAsset> {
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("publicUrl").trim()
                if (url.isBlank()) continue
                add(
                    MediaCenterAsset(
                        id = item.optString("id"),
                        title = item.optString("title").ifBlank { item.optString("originalFilename") },
                        collection = item.optString("collection"),
                        publicUrl = url,
                        wakeEnabled = item.optBoolean("wakeEnabled", false),
                        tags = jsonArrayToList(item.optJSONArray("tags")),
                        useCases = jsonArrayToList(item.optJSONArray("useCases")),
                        notes = item.optString("notes").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
