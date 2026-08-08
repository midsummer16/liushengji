package com.voiceclone.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 暖色调色板:珊瑚主色 + 琥珀金次色。
// 整体偏暖(亲友陪伴),与"声纹工坊"家庭场景契合,避免冷蓝的工业感。
val PrimaryCoral = Color(0xFFFF8A65)
val SecondaryAmber = Color(0xFFF2A65A)
val TertiaryPeach = Color(0xFFFFB59C)

val WarmBackgroundLight = Color(0xFFFDF6F0)
val WarmSurfaceLight = Color(0xFFFFF8F2)
val WarmBackgroundDark = Color(0xFF1C1814)
val WarmSurfaceDark = Color(0xFF2A2520)
val WarmSurfaceVariantDark = Color(0xFF3A322C)

// 暖色暗色主题:背景与 surface 都用暖色(咖啡/深棕),不混冷色。
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryCoral,
    onPrimary = Color(0xFF3A1A0A),
    primaryContainer = Color(0xFF7A3A22),
    onPrimaryContainer = Color(0xFFFFD9C7),
    secondary = SecondaryAmber,
    onSecondary = Color(0xFF3A2308),
    secondaryContainer = Color(0xFF5C3A14),
    onSecondaryContainer = Color(0xFFFFE0B8),
    tertiary = TertiaryPeach,
    onTertiary = Color(0xFF3A1F12),
    background = WarmBackgroundDark,
    onBackground = Color(0xFFF5E6D8),
    surface = WarmSurfaceDark,
    onSurface = Color(0xFFF5E6D8),
    surfaceVariant = WarmSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFD9C7B5),
    outline = Color(0xFF8A7868),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5C0F0A)
)

// 暖色亮色主题:奶油底色 + 珊瑚强调,亲切柔和。
private val LightColorScheme = lightColorScheme(
    primary = PrimaryCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9C7),
    onPrimaryContainer = Color(0xFF4A1E0A),
    secondary = SecondaryAmber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5C2),
    onSecondaryContainer = Color(0xFF4A2C08),
    tertiary = Color(0xFFD17A5A),
    onTertiary = Color.White,
    background = WarmBackgroundLight,
    onBackground = Color(0xFF2A1F18),
    surface = WarmSurfaceLight,
    onSurface = Color(0xFF2A1F18),
    surfaceVariant = Color(0xFFF5E6D6),
    onSurfaceVariant = Color(0xFF6B5343),
    outline = Color(0xFFB89A82),
    error = Color(0xFFD34A2F),
    onError = Color.White
)

// 自定义 Typography:用系统默认字体,但加大标题字重和行高,营造"圆润感"。
// bodyLarge 显式设 24sp 行高,避免在多语言/中文混排时行距过紧。
private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

// 自定义 Shapes:全部加大圆角,营造"温暖亲和"的视觉,弱化卡片锐利感。
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun VoiceCloneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
