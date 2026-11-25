package dev.securemessenger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.GroupMemberResponse
import dev.securemessenger.data.GroupConversation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
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

    @Test
    fun message_history_displays_incoming_and_outgoing_messages() {
        val messages = listOf(
            message(id = "incoming", body = "Входящее сообщение", outgoing = false, status = "DELIVERED"),
            message(id = "outgoing", body = "Исходящее сообщение", outgoing = true, status = "SENT"),
        )

        showChat(messages = messages)

        compose.onNodeWithText("Входящее сообщение").assertIsDisplayed()
        compose.onNodeWithText("Исходящее сообщение").assertIsDisplayed()
    }

    @Test
    fun message_history_displays_all_outgoing_delivery_statuses() {
        val statuses = listOf("PENDING", "SENT", "DELIVERED", "READ")
        val messages = statuses.map { status ->
            message(id = status, body = "Сообщение $status", outgoing = true, status = status)
        }

        showChat(messages = messages)

        statuses.forEach { status ->
            compose.onNodeWithText("Сообщение $status").assertIsDisplayed()
            compose.onNodeWithText(status).assertIsDisplayed()
        }
    }

    @Test
    fun composer_enables_send_for_nonblank_draft_and_sends_it() {
        var sentDraft: String? = null
        showChat(draft = "  Сообщение  ", onSend = { sentDraft = it })

        compose.onNodeWithTag("send_button").assertIsEnabled().performClick()

        assertEquals("  Сообщение  ", sentDraft)
    }

    @Test
    fun composer_disables_send_for_blank_draft() {
        showChat(draft = " \n\t ")

        compose.onNodeWithTag("send_button").assertIsNotEnabled()
    }

    @Test
    fun group_card_click_selects_group() {
        val groups = List(30) { index ->
            GroupConversation(
                GroupResponse(
                    "group-$index",
                    session.accountId,
                    listOf(GroupMemberResponse(session.accountId, session.username, "OWNER")),
                ),
                "Private group $index",
            )
        }
        val target = groups.last()
        var selected: GroupConversation? = null
        compose.setContent {
            HomeLayout(
                isWide = false,
                fold = FoldState(),
                session = session,
                searchResult = null,
                selectedChat = null,
                draft = "",
                busy = false,
                error = null,
                onSearch = {},
                onSelect = {},
                onCloseChat = {},
                onDraftChange = {},
                onLogout = {},
                groups = groups,
                onSelectGroup = { selected = it },
            )
        }

        compose.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("group_item_${target.group.groupId}"))
        compose.onNodeWithTag("group_item_${target.group.groupId}").performClick()

        assertEquals(target, selected)
    }

    @Test
    fun group_key_readiness_error_is_visible() {
        compose.setContent {
            GroupCreationSection(false, null, { _, _ -> }, "RECIPIENT_NOT_READY")
        }

        compose.onNodeWithTag("group_error").assertIsDisplayed()
    }

    private fun showChat(
        draft: String = "",
        messages: List<MessageEntity> = emptyList(),
        onSend: (String) -> Unit = {},
    ) {
        compose.setContent {
            HomeLayout(
                isWide = false,
                fold = FoldState(),
                session = session,
                searchResult = null,
                selectedChat = chat,
                draft = draft,
                busy = false,
                error = null,
                onSearch = {},
                onSelect = {},
                onCloseChat = {},
                onDraftChange = {},
                onLogout = {},
                messages = messages,
                onSend = onSend,
            )
        }
    }

    private fun message(id: String, body: String, outgoing: Boolean, status: String) = MessageEntity(
        messageId = id,
        chatId = chat.accountId,
        body = body,
        outgoing = outgoing,
        createdAt = 1L,
        status = status,
    )
}
