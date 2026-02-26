package com.adagiostream.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

fun scaledTypography(scaleFactor: Float): Typography {
    if (scaleFactor == 1.0f) return Typography
    return Typography(
        displayLarge = Typography.displayLarge.scaled(scaleFactor),
        displayMedium = Typography.displayMedium.scaled(scaleFactor),
        displaySmall = Typography.displaySmall.scaled(scaleFactor),
        headlineLarge = Typography.headlineLarge.scaled(scaleFactor),
        headlineMedium = Typography.headlineMedium.scaled(scaleFactor),
        headlineSmall = Typography.headlineSmall.scaled(scaleFactor),
        titleLarge = Typography.titleLarge.scaled(scaleFactor),
        titleMedium = Typography.titleMedium.scaled(scaleFactor),
        titleSmall = Typography.titleSmall.scaled(scaleFactor),
        bodyLarge = Typography.bodyLarge.scaled(scaleFactor),
        bodyMedium = Typography.bodyMedium.scaled(scaleFactor),
        bodySmall = Typography.bodySmall.scaled(scaleFactor),
        labelLarge = Typography.labelLarge.scaled(scaleFactor),
        labelMedium = Typography.labelMedium.scaled(scaleFactor),
        labelSmall = Typography.labelSmall.scaled(scaleFactor),
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize * factor,
    lineHeight = lineHeight * factor,
)
