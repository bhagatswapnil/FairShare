package com.splitexpense.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists app-wide settings — currently just the single currency used across every
 * group. Currency is intentionally an app-level setting rather than a per-group one:
 * FairShare aggregates totals across groups (e.g. the Home screen summary banner), and
 * letting each group pick its own currency made those aggregates meaningless once more
 * than one currency was in play. One global currency keeps every total, chart and PDF
 * consistent everywhere.
 *
 * Backed by the same SharedPreferences file as [ApiKeyManager]. Held as a singleton on
 * [com.splitexpense.SplitExpenseApplication] so every ViewModel observes the same
 * [currencyCode] StateFlow instance — a change made from one screen is reflected
 * immediately on every other screen currently alive, with no DB round-trip needed.
 */
class AppSettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences("fairshare_prefs", Context.MODE_PRIVATE)

    private val _currencyCode =
        MutableStateFlow(prefs.getString(KEY_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY)
    val currencyCode: StateFlow<String> = _currencyCode.asStateFlow()

    fun setCurrencyCode(code: String) {
        prefs.edit().putString(KEY_CURRENCY, code).apply()
        _currencyCode.value = code
    }

    companion object {
        private const val KEY_CURRENCY = "app_currency_code"
        private const val DEFAULT_CURRENCY = "INR"
    }
}
