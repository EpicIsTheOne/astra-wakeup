package com.astra.wakeup.brain.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SharedPrefsMemoryStore(context: Context) : MemoryStore {
    private val prefs = context.getSharedPreferences("astra_brain_memory", Context.MODE_PRIVATE)

    override suspend fun store(item: MemoryItem) {
        val list = load().toMutableList()
        list += item
        save(list)
    }

    override suspend fun search(query: String, scope: MemoryScope?): List<MemoryItem> {
        val q = query.lowercase()
        return load().filter {
            (scope == null || it.scope == scope) &&
                (it.content.lowercase().contains(q) || it.category.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) })
        }
    }

    override suspend fun update(item: MemoryItem) {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list += item
        save(list)
    }

    override suspend fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    override suspend fun all(scope: MemoryScope?): List<MemoryItem> {
        return load().filter { scope == null || it.scope == scope }
    }

    private fun load(): List<MemoryItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tags = mutableListOf<String>()
                    val tArr = o.optJSONArray("tags") ?: JSONArray()
                    for (j in 0 until tArr.length()) tags += tArr.optString(j)
                    add(
                        MemoryItem(
                            id = o.optString("id"),
                            scope = runCatching { MemoryScope.valueOf(o.optString("scope")) }.getOrDefault(MemoryScope.SHORT_TERM),
                            category = o.optString("category", "misc"),
                            content = o.optString("content", ""),
                            tags = tags,
                            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<MemoryItem>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("scope", it.scope.name)
                    .put("category", it.category)
                    .put("content", it.content)
                    .put("tags", JSONArray(it.tags))
                    .put("updatedAtMs", it.updatedAtMs)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
