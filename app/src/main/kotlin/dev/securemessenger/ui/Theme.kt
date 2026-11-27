package dev.securemessenger.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF6EE7D8),
            onPrimary = Color(0xFF073A32),
            primaryContainer = Color(0xFF005E4B),
            onPrimaryContainer = Color(0xFFE9EDEF),
            secondary = Color(0xFFA3C9BC),
            onSecondary = Color(0xFF10241D),
            background = Color(0xFF0E1621),
            onBackground = Color(0xFFE9EDEF),
            surface = Color(0xFF17212B),
            onSurface = Color(0xFFE9EDEF),
            surfaceContainer = Color(0xFF1F2C34),
            surfaceContainerHigh = Color(0xFF24333F),
            onSurfaceVariant = Color(0xFFA9B6C0),
            outline = Color(0xFF34454F),
            error = Color(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006C60),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9FDD3),
            onPrimaryContainer = Color(0xFF102116),
            secondary = Color(0xFF4B635B),
            onSecondary = Color.White,
            background = Color(0xFFF4F7FA),
            onBackground = Color(0xFF102116),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF102116),
            surfaceContainer = Color(0xFFF8FAF9),
            surfaceContainerHigh = Color(0xFFE7EFEA),
            onSurfaceVariant = Color(0xFF53675D),
            outline = Color(0xFFB5C4BA),
            error = Color(0xFFBA1A1A),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        typography = Typography(
            headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
        ),
        content = content,
    )
}
