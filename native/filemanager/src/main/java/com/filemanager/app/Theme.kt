package com.filemanager.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF1E88E5)
val PrimarySoft = Color(0xFFE3F0FD)
val PrimaryStrong = Color(0xFF1565C0)
val Bg = Color(0xFFF5F7FB)
val Border = Color(0xFFE2E8F2)
val Surface2 = Color(0xFFEEF2F8)
val TextMain = Color(0xFF1A1F2B)
val TextSoft = Color(0xFF6B728E)
val TextFaint = Color(0xFF9AA3BB)
val Danger = Color(0xFFD32F2F)

@Composable
fun FMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Primary,
            onPrimary = Color.White,
            primaryContainer = PrimarySoft,
            onPrimaryContainer = PrimaryStrong,
            background = Bg,
            surface = Color.White,
            onSurface = TextMain,
            surfaceVariant = Surface2,
            outline = Border
        ),
        content = content
    )
}
