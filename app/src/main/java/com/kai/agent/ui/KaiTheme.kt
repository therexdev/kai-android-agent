package com.kai.agent.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val KaiDark = darkColorScheme(
    primary = Color(0xFFBEA5FF), onPrimary = Color(0xFF201333),
    primaryContainer = Color(0xFF342744), onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFADCDBF), secondaryContainer = Color(0xFF263B33),
    background = Color(0xFF111114), onBackground = Color(0xFFF5F1F8),
    surface = Color(0xFF111114), onSurface = Color(0xFFF5F1F8),
    surfaceVariant = Color(0xFF242328), onSurfaceVariant = Color(0xFFAAA5B2),
    outline = Color(0xFF444048), outlineVariant = Color(0xFF302D35),
    surfaceContainer = Color(0xFF1B1A1F), surfaceContainerHigh = Color(0xFF242228),
    surfaceContainerHighest = Color(0xFF2C2932), error = Color(0xFFFFB4AB),
)
private val KaiLight = lightColorScheme(
    primary = Color(0xFF7151A5), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF2B1643),
    secondary = Color(0xFF486B5A), secondaryContainer = Color(0xFFD8EADC),
    background = Color(0xFFF8F6FA), onBackground = Color(0xFF211D27),
    surface = Color(0xFFF8F6FA), onSurface = Color(0xFF211D27),
    surfaceVariant = Color(0xFFEDE8F1), onSurfaceVariant = Color(0xFF736D7B),
    outline = Color(0xFFA39AAA), outlineVariant = Color(0xFFDFD8E5),
    surfaceContainer = Color(0xFFF0ECF3), surfaceContainerHigh = Color(0xFFEAE5EE),
    surfaceContainerHighest = Color(0xFFE3DDE9), error = Color(0xFFBA3939),
)

@Composable
fun KaiTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) KaiDark else KaiLight,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
            headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
        ), content = content,
    )
}
