package dev.securemessenger.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import dev.securemessenger.MainActivity
import org.junit.Rule
import kotlin.test.Test

class E2eeConversationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun real_encrypted_conversation() {
        val args = InstrumentationRegistry.getArguments()
        val email = args.getString("email") ?: error("email is required")
        val password = args.getString("password") ?: error("password is required")
        val remote = args.getString("remote") ?: error("remote is required")
        val message = args.getString("message").orEmpty()
        val expected = args.getString("expect").orEmpty()
        val reply = args.getString("reply").orEmpty()
        val reviewSafety = args.getString("review_safety") == "true"

        keepScreenOn()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("email_input").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("email_input").performTextClearance()
            compose.onNodeWithTag("email_input").performTextInput(email)
            compose.onNodeWithTag("password_input").performTextClearance()
            compose.onNodeWithTag("password_input").performTextInput(password)
            compose.onNodeWithTag("primary_action").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithTag("username_search").performTextInput(remote)
        compose.onNodeWithTag("search_button").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("search_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search_result").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()
        }

        if (message.isNotEmpty()) {
            sendAndAwaitSent(message)
        }
        if (expected.isNotEmpty()) {
            compose.waitUntil(45_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
        if (reply.isNotEmpty()) {
            sendAndAwaitSent(reply)
        }
        if (reviewSafety) {
            compose.onNodeWithTag("open_safety").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("safety_number_text").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("safety_qr").assertIsDisplayed()
            val text = compose.onNodeWithTag("safety_number_text").fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString("") { it.text }
            check(text.filter(Char::isDigit).length == 60) { "Safety number must contain 60 digits" }
            compose.onNodeWithTag("confirm_safety").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun keepScreenOn() {
        compose.activityRule.scenario.onActivity { activity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "pm grant ${activity.packageName} android.permission.POST_NOTIFICATIONS",
                ).close()
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
    }

    private fun sendAndAwaitSent(body: String) {
        fun latestText(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .firstOrNull()?.let { node ->
                runCatching { node.config[SemanticsProperties.Text].joinToString("") { it.text } }.getOrNull()
            }
        fun latestStatus(): String? = latestText("latest_outgoing_status")
        fun latestBody(): String? = latestText("latest_outgoing_body")

        compose.onNodeWithTag("message_input").performTextClearance()
        compose.onNodeWithTag("message_input").performTextInput(body)
        compose.onNodeWithTag("send_button").performClick()
        val acknowledged = try {
            compose.waitUntil(20_000) {
                val status = latestStatus()
                latestBody() == body && (status == "SENT" || status == "DELIVERED" || status == "READ")
            }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
        check(acknowledged) {
            "Latest outgoing message status: ${latestStatus() ?: "missing"}; " +
                "expected body length=${body.length}; actual body length=${latestBody()?.length}"
        }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("message_input").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text.isEmpty()
        }
    }
}
