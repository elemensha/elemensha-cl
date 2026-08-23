package com.elemensha.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 로고에서 뽑은 색
val LogoGreen = Color(0xFF7CC142)
val LogoRed = Color(0xFFE0344B)
val LogoBlue = Color(0xFF3D8BF5)
val LogoCyan = Color(0xFF21D4E0)

val ProfitGreen = Color(0xFF16C784)
val LossRed = Color(0xFFEA3943)
val WarnAmber = Color(0xFFF5A524)

private val DarkColors = darkColorScheme(
    primary = LogoGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1B3A0E),
    onPrimaryContainer = LogoGreen,
    secondary = LogoBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0E2647),
    onSecondaryContainer = Color(0xFF9EC6FF),
    tertiary = LogoCyan,
    error = LossRed,
    onError = Color.White,
    errorContainer = Color(0xFF3B0F13),
    onErrorContainer = Color(0xFFFFB4AB),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0E0E11),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1A1A1F),
    onSurfaceVariant = Color(0xFFA8A8B3),
    outline = Color(0xFF35353D),
    outlineVariant = Color(0xFF23232A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6B1C),
    secondary = Color(0xFF1F5FBF),
    error = LossRed,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
    // 숫자가 흔들리지 않도록 시세/RSI 표시는 등폭 폰트를 쓴다
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    ),
)

@Composable
fun ElemenshaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
