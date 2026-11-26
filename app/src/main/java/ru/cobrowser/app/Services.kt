package ru.cobrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Service(
    val name: String,
    val url: String,
    val domain: String,
    val color: Int,
    val letter: String
)

object Services {
    val list: List<Service> = listOf(
        Service("YouTube", "https://m.youtube.com/", "youtube.com", 0xFFE62117.toInt(), "▶"),
        Service("Google", "https://www.google.com/", "google.com", 0xFF4285F4.toInt(), "G"),
        Service("Netflix", "https://www.netflix.com/", "netflix.com", 0xFF111111.toInt(), "N"),
        Service("Disney+", "https://www.disneyplus.com/", "disneyplus.com", 0xFF113CCF.toInt(), "D+"),
        Service("RuTube", "https://rutube.ru/", "rutube.ru", 0xFF1BBF4B.toInt(), "R"),
        Service("VK Видео", "https://vkvideo.ru/", "vkvideo.ru", 0xFF0077FF.toInt(), "VK")
    )
}

class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("cobrowser", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString("favorites", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(items: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().put("title", it.first).put("url", it.second))
        }
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    fun has(url: String): Boolean = all().any { it.second == url }

    fun add(title: String, url: String) {
        if (has(url)) return
        save(all() + Pair(title, url))
    }

    fun remove(url: String) {
        save(all().filter { it.second != url })
    }
}
