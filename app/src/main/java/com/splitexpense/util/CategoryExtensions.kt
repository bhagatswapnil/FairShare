package com.splitexpense.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.splitexpense.data.db.entity.Category

fun Category.displayName(): String = when (this) {
    Category.FOOD          -> "Food"
    Category.TRAVEL        -> "Travel"
    Category.UTILITIES     -> "Utilities"
    Category.ENTERTAINMENT -> "Entertainment"
    Category.SHOPPING      -> "Shopping"
    Category.HEALTH        -> "Health"
    Category.DRINKS        -> "Drinks"
    Category.BEVERAGES     -> "Beverages"
    Category.OTHER         -> "Other"
}

fun Category.icon(): ImageVector = when (this) {
    Category.FOOD          -> Icons.Filled.Restaurant
    Category.TRAVEL        -> Icons.Filled.Flight
    Category.UTILITIES     -> Icons.Filled.Lightbulb
    Category.ENTERTAINMENT -> Icons.Filled.Movie
    Category.SHOPPING      -> Icons.Filled.ShoppingCart
    Category.HEALTH        -> Icons.Filled.Favorite
    Category.DRINKS        -> Icons.Filled.LocalBar
    Category.BEVERAGES     -> Icons.Filled.Coffee
    Category.OTHER         -> Icons.Filled.Category
}

fun Category.color(): Color = when (this) {
    Category.FOOD          -> Color(0xFFEF5350)
    Category.TRAVEL        -> Color(0xFF29B6F6)
    Category.UTILITIES     -> Color(0xFFFFCA28)
    Category.ENTERTAINMENT -> Color(0xFF66BB6A)
    Category.SHOPPING      -> Color(0xFF7E57C2)
    Category.HEALTH        -> Color(0xFFEC407A)
    Category.DRINKS        -> Color(0xFFFF7043)
    Category.BEVERAGES     -> Color(0xFF26C6DA)
    Category.OTHER         -> Color(0xFF78909C)
}

fun formatCurrency(amount: Double, symbol: String = "₹"): String = "$symbol%.2f".format(amount)

val avatarPalette = listOf(
    0xFFE53935L, 0xFFD81B60L, 0xFF8E24AAL, 0xFF5E35B1L,
    0xFF3949ABL, 0xFF1E88E5L, 0xFF039BE5L, 0xFF00ACC1L,
    0xFF00897BL, 0xFF43A047L, 0xFF7CB342L, 0xFFF4511EL
)

fun nameToAvatarColor(name: String): Long =
    avatarPalette[Math.abs(name.hashCode()) % avatarPalette.size]
