package com.splitexpense.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

val AppTypography = Typography(
    displaySmall   = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold,     fontSize = 36.sp, letterSpacing = (-0.5).sp),
    headlineLarge  = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold,     fontSize = 26.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    headlineSmall  = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleLarge     = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold,     fontSize = 19.sp, letterSpacing = (-0.3).sp),
    titleMedium    = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = (-0.1).sp),
    titleSmall     = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.sp),
    bodyLarge      = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal,   fontSize = 14.sp, letterSpacing = 0.sp),
    bodyMedium     = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal,   fontSize = 12.sp, letterSpacing = 0.sp),
    bodySmall      = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal,   fontSize = 11.sp, letterSpacing = 0.sp),
    labelLarge     = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelMedium    = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium,   fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelSmall     = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium,   fontSize = 9.sp,  letterSpacing = 0.8.sp),
)
