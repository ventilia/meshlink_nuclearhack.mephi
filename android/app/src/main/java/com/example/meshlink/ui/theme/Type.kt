package com.example.meshlink.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.example.meshlink.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val PressStart2PFamily = FontFamily(
    Font(GoogleFont("Press Start 2P"), provider)
)

val MeshLinkTypography = Typography(
    // Заголовок приложения
    headlineLarge = TextStyle(
        fontFamily = PressStart2PFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PressStart2PFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.sp
    ),
    // Имена пользователей в списке
    titleMedium = TextStyle(
        fontFamily = PressStart2PFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = PressStart2PFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 0.sp
    ),
    // Тело сообщений — обычный шрифт для читаемости
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp
    ),
    // Метки (время, статус)
    labelSmall = TextStyle(
        fontFamily = PressStart2PFamily,
        fontSize = 6.sp,
        letterSpacing = 0.sp
    )
)