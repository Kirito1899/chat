package dev.securemessenger.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import dev.securemessenger.MainActivity
import org.junit.Rule
import kotlin.test.Test

class RealAccountFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun real_login_search_and_draft_restoration() {
        val arguments = InstrumentationRegistry.getArguments()
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "state-ready").delete()
        keepScreenOn()
        compose.waitUntil(20_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
        val email = arguments.getString("email") ?: error("email instrumentation argument is required")
        val password = arguments.getString("password") ?: error("password instrumentation argument is required")
        val username = arguments.getString("username") ?: error("username instrumentation argument is required")
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Выйти").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("email_input").fetchSemanticsNodes().isNotEmpty()
            }
        }

        compose.onNodeWithTag("email_input").performTextInput(email)
        compose.onNodeWithTag("password_input").performTextInput(password)
        compose.onNodeWithTag("primary_action").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("username_search").performTextInput(username)
        compose.onNodeWithTag("search_button").performClick()
        val matchingUsers = compose.onAllNodesWithText("@$username")
        check(matchingUsers.fetchSemanticsNodes().isNotEmpty()) { "Search result is not visible" }
        compose.onNodeWithTag("search_result").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("message_input").performTextClearance()
        compose.onNodeWithTag("message_input").performTextInput("Черновик после пересоздания")
        Thread.sleep(600)
        compose.activityRule.scenario.recreate()
        keepScreenOn()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Черновик после пересоздания").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("message_input")
            .assertIsDisplayed()
            .assertTextEquals("Черновик после пересоздания")
        if (arguments.getString("show_list") == "true" || arguments.getString("show_auth") == "true") {
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() }
        }
        if (arguments.getString("show_auth") == "true") {
            compose.onNodeWithText("Выйти").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("email_input").fetchSemanticsNodes().isNotEmpty() }
        }
        if (arguments.getString("wait_for_kill") == "true") {
            File(ApplicationProvider.getApplicationContext<Context>().filesDir, "state-ready").writeText("ready")
            Thread.sleep(60_000)
            error("Process was not killed during the restoration scenario")
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
}
