package dev.securemessenger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import org.junit.Rule
import java.util.UUID
import kotlin.test.Test

class AdaptiveAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = TokenResponse(
        "access",
        "refresh",
        1,
        2,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "test_user",
    )
    private val chat = UserResponse(UUID.randomUUID().toString(), "friend_user")

    @Test
    fun wide_layout_keeps_list_and_selected_chat_visible() {
        compose.setContent {
            HomeLayout(
                isWide = true,
                fold = FoldState(),
                session = session,
                searchResult = null,
                selectedChat = chat,
                draft = "",
                busy = false,
                error = null,
                onSearch = {},
                onSelect = {},
                onCloseChat = {},
                onDraftChange = {},
                onLogout = {},
            )
        }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        compose.onNodeWithTag("chat_detail").assertIsDisplayed()
    }

    @Test
    fun horizontal_half_opened_fold_separates_content_and_input() {
        compose.setContent {
            HomeLayout(
                isWide = false,
                fold = FoldState(halfOpened = true, horizontal = true),
                session = session,
                searchResult = null,
                selectedChat = chat,
                draft = "Черновик",
                busy = false,
                error = null,
                onSearch = {},
                onSelect = {},
                onCloseChat = {},
                onDraftChange = {},
                onLogout = {},
            )
        }
        compose.onNodeWithTag("flex_content").assertIsDisplayed()
        compose.onNodeWithTag("flex_input").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsDisplayed()
    }
}
