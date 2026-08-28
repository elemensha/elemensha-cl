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

// 밝은 배경용. background/surface 와 그 위의 글자색을 반드시 함께 지정한다 —
// 한쪽만 정하면 기본값과 섞여 '검정 바탕에 검정 글씨' 같은 조합이 나온다.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6B1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFC8),
    onPrimaryContainer = Color(0xFF1B3A0E),
    secondary = Color(0xFF1F5FBF),
    onSecondary = Color.White,
    error = LossRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E1),
    onErrorContainer = Color(0xFF6B0F16),
    background = Color.White,
    onBackground = Color(0xFF16161A),
    surface = Color.White,
    onSurface = Color(0xFF16161A),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF54545E),
    outline = Color(0xFFC4C4CC),
    outlineVariant = Color(0xFFE3E3E8),
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

/**
 * 시스템 테마와 무관하게 항상 밝게 그리는 테마.
 *
 * 페어링 화면에만 쓴다. 서버 주소와 페어링 코드를 처음 입력하는 자리라
 * 기기 설정이 무엇이든 확실히 읽혀야 한다.
 */
@Composable
fun ElemenshaLightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}

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
