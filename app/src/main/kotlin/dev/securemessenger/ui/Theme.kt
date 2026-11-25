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
            primaryContainer = Color(0xFF16483F),
            onPrimaryContainer = Color(0xFFB6F4E9),
            secondary = Color(0xFFA4B9FF),
            onSecondary = Color(0xFF172746),
            background = Color(0xFF0C1118),
            onBackground = Color(0xFFE7EDF5),
            surface = Color(0xFF121923),
            onSurface = Color(0xFFE7EDF5),
            surfaceContainer = Color(0xFF151E29),
            surfaceContainerHigh = Color(0xFF1C2835),
            onSurfaceVariant = Color(0xFF9EABBA),
            outline = Color(0xFF334251),
            error = Color(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006C60),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB8F0E6),
            onPrimaryContainer = Color(0xFF082B27),
            secondary = Color(0xFF355BA8),
            onSecondary = Color.White,
            background = Color(0xFFF6F8FC),
            onBackground = Color(0xFF14202B),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF14202B),
            surfaceContainer = Color(0xFFF9FBFF),
            surfaceContainerHigh = Color(0xFFEAF0F6),
            onSurfaceVariant = Color(0xFF536575),
            outline = Color(0xFFB6C3CE),
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
