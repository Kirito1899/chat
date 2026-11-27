package dev.securemessenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.securemessenger.R
import dev.securemessenger.data.local.MessageEntity
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AmiThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ami_logo_and_outgoing_green_palette_are_visible() {
        var expected = Color.Unspecified
        var actual = Color.Unspecified
        compose.setContent {
            val dark = isSystemInDarkTheme()
            MessengerTheme {
                actual = MaterialTheme.colorScheme.primaryContainer
                expected = if (dark) Color(0xFF005E4B) else Color(0xFFD9FDD3)
                AmiLogo()
                MessageBubble(MessageEntity("message", "direct:peer", "Сообщение", true, 1, "SENT"), latestOutgoing = true)
            }
        }

        compose.onNodeWithContentDescription("Ami").assertIsDisplayed()
        compose.onNodeWithContentDescription("Отправлено").assertIsDisplayed()
        assertEquals(expected, actual)
    }

    @Composable
    private fun AmiLogo() {
        Image(
            painterResource(R.drawable.ic_ami_foreground),
            contentDescription = "Ami",
            modifier = Modifier,
        )
    }
}
