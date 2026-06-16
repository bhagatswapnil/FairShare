package com.splitexpense.util

/** A curated currency the user can pick for a group. Not an exhaustive ISO 4217 list. */
data class CurrencyOption(
    val code: String,
    val symbol: String,
    val label: String
)

val SUPPORTED_CURRENCIES = listOf(
    CurrencyOption("INR", "₹", "Indian Rupee"),
    CurrencyOption("USD", "$", "US Dollar"),
    CurrencyOption("EUR", "€", "Euro"),
    CurrencyOption("GBP", "£", "British Pound"),
    CurrencyOption("JPY", "¥", "Japanese Yen"),
    CurrencyOption("AUD", "A$", "Australian Dollar"),
    CurrencyOption("CAD", "C$", "Canadian Dollar"),
    CurrencyOption("SGD", "S$", "Singapore Dollar"),
    CurrencyOption("AED", "د.إ", "UAE Dirham"),
    CurrencyOption("CNY", "¥", "Chinese Yuan"),
    CurrencyOption("THB", "฿", "Thai Baht"),
    CurrencyOption("NPR", "₨", "Nepalese Rupee")
)

private val DEFAULT_CURRENCY = SUPPORTED_CURRENCIES.first() // INR

fun currencyOptionFor(code: String): CurrencyOption =
    SUPPORTED_CURRENCIES.find { it.code == code } ?: DEFAULT_CURRENCY

fun currencySymbolFor(code: String): String = currencyOptionFor(code).symbol
