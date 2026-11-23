package dev.securemessenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF315DA8)
private val LightBlue = Color(0xFF9FC2F2)

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = LightBlue,
                secondary = LightBlue,
                background = Color(0xFF101418),
                surface = Color(0xFF101418),
                onSurface = Color(0xFFE2E6EC),
                onSurfaceVariant = Color(0xFFB8C0CC),
            )
        } else {
            lightColorScheme(primary = Blue, secondary = Blue)
        },
        content = content,
    )
}
