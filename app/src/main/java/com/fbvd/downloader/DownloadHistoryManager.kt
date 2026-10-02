package com.fbvd.downloader

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DownloadItem(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val description: String,
    val hashtags: String,
    val originalUrl: String,
    var isExpanded: Boolean = false
) {
    val allCombined: String
        get() = buildString {
            if (title.isNotBlank()) append(title).append("\n\n")
            if (description.isNotBlank()) append(description).append("\n\n")
            if (hashtags.isNotBlank()) append(hashtags)
        }.trim()
}

class DownloadHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("fbvd_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "history_entries"

    fun getHistory(): MutableList<DownloadItem> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<DownloadItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addItem(item: DownloadItem) {
        val list = getHistory()
        list.removeAll { it.originalUrl == item.originalUrl }
        list.add(0, item)
        if (list.size > 10) {
            list.subList(10, list.size).clear()
        }
        save(list)
    }

    fun removeItem(item: DownloadItem) {
        val list = getHistory()
        list.removeAll { it.id == item.id }
        save(list)
    }

    private fun save(list: List<DownloadItem>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }
}
