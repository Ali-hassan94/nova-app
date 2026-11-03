package com.jarvis.mobile

import android.content.Context

object Config {
    fun prefs(c: Context) = c.getSharedPreferences("nova", Context.MODE_PRIVATE)

    /** User ki apni key ho to wo, warna APK ke andar build ke waqt daali hui key. */
    fun groqKey(c: Context): String {
        val own = prefs(c).getString("groq_key", "")?.trim().orEmpty()
        return if (own.isNotEmpty()) own else BuildConfig.DEFAULT_GROQ_KEY
    }

    fun hasBuiltInKey(): Boolean = BuildConfig.DEFAULT_GROQ_KEY.isNotEmpty()

    fun memories(c: Context): List<String> =
        (prefs(c).getString("memories", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addMemory(c: Context, fact: String) {
        val l = memories(c).toMutableList()
        l.add(fact.replace("\n", " ").trim())
        while (l.size > 40) l.removeAt(0)
        prefs(c).edit().putString("memories", l.joinToString("\n")).apply()
    }

    fun clearMemories(c: Context) {
        prefs(c).edit().remove("memories").apply()
    }
}
