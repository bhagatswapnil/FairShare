package com.splitexpense.util

import android.content.Context

/**
 * Persists the user's Gemini API key in private SharedPreferences.
 * Each user of the APK manages their own key independently.
 */
class ApiKeyManager(context: Context) {

    private val prefs = context.getSharedPreferences("fairshare_prefs", Context.MODE_PRIVATE)

    fun getKey(): String = prefs.getString(KEY_GEMINI, "") ?: ""

    fun setKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun hasKey(): Boolean = getKey().isNotBlank()

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
    }
}
