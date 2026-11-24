package dev.securemessenger.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.securemessenger.MainActivity
import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.GroupChatRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.media.MediaService
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupConversationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestEntryPoint {
        fun accounts(): AuthRepository
        fun groupChats(): GroupChatRepository
        fun media(): MediaService
        fun messages(): MessageRepository
        fun database(): AppDatabase
    }

    @Test
    fun real_group_flow() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("action") ?: error("action is required")
        val email = args.getString("email") ?: error("email is required")
        val password = args.getString("password") ?: error("password is required")
        val title = args.getString("title") ?: error("title is required")
        val member = args.getString("member").orEmpty()
        val message = args.getString("message").orEmpty()
        val expected = args.getString("expect").orEmpty()
        val mime = args.getString("mime").orEmpty()

        keepScreenOn()
        when (action) {
            "create" -> {
                ensureLogin(email, password)
                compose.onNodeWithTag("create_group_toggle").performClick()
                compose.onNodeWithTag("group_title_input").performTextInput(title)
                compose.onNodeWithTag("group_member_input").performTextInput(member)
                compose.onNodeWithTag("create_group_button").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("group_members_button").fetchSemanticsNodes().isNotEmpty() }
            }
            "send" -> {
                openGroup(title, email, password)
                compose.onNodeWithTag("message_input").performTextClearance()
                compose.onNodeWithTag("message_input").performTextInput(message)
                compose.onNodeWithTag("send_button").performClick()
                compose.waitUntil(30_000) {
                    val status = latestText("latest_outgoing_status")
                    latestText("latest_outgoing_body") == message && (status == "SENT" || status == "DELIVERED" || status == "READ")
                }
            }
            "expect" -> {
                openGroup(title, email, password)
                compose.waitUntil(45_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(expected).assertIsDisplayed()
            }
            "expect_status" -> {
                openGroup(title, email, password)
                compose.waitUntil(45_000) { latestText("latest_outgoing_status") == expected }
            }
            "add_member" -> {
                openGroup(title, email, password)
                openMembers()
                compose.onNodeWithTag("add_member_input").performTextInput(member)
                compose.onNodeWithTag("add_member_button").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("@$member").fetchSemanticsNodes().isNotEmpty() }
            }
            "remove_member" -> {
                openGroup(title, email, password)
                openMembers()
                compose.onAllNodesWithText("Удалить").onLast().performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("@$member").fetchSemanticsNodes().isEmpty() }
            }
            "expect_removed" -> {
                ensureLogin(email, password)
                compose.waitUntil(30_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
            }
            "expect_not_message" -> {
                ensureLogin(email, password)
                val entry = entryPoint(email, password)
                entry.messages().sync()
                val group = entry.groupChats().list().find { it.title == title }
                val groupId = group?.group?.groupId
                    ?: entry.database().groupInfo().observeAll().first().single { it.title == title }.groupId
                val bodies = entry.messages().observeGroupMessages(groupId).first().map { it.body }
                check(expected !in bodies) { "Unexpected message was delivered to a removed or new member" }
            }
            "send_removed" -> {
                val entry = entryPoint(email, password)
                val info = entry.database().groupInfo().observeAll().first().single { it.title == title }
                val request = SendGroupMessageRequest(
                    UUID.randomUUID().toString(),
                    SendMessageRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2, encode(ByteArray(32) { 4 })),
                )
                val error = assertFailsWith<dev.securemessenger.data.remote.ApiException> {
                    entry.accounts().sendGroupMessage(info.groupId, request)
                }
                check(error.code == "MESSAGE_FORBIDDEN") { error.code }
            }
            "send_media" -> {
                openGroup(title, email, password)
                val entry = entryPoint(email, password)
                val group = entry.groupChats().list().single { it.title == title }
                val file = File(args.getString("media_path") ?: error("media_path is required"))
                val prepared = entry.media().prepare(
                    { FileInputStream(file) },
                    file.name,
                    mime,
                    file.length(),
                )
                entry.messages().sendGroupMedia(group.group, prepared.descriptor, "")
            }
            "expect_media" -> {
                openGroup(title, email, password)
                val tag = when {
                    mime.startsWith("video/") -> "media_video"
                    mime.startsWith("audio/") -> "media_audio"
                    mime.startsWith("image/") -> "media_image"
                    else -> "save_media"
                }
                compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            "verify_file" -> {
                openGroup(title, email, password)
                val entry = entryPoint(email, password)
                entry.messages().sync()
                val group = entry.groupChats().list().single { it.title == title }
                val entity = entry.messages().observeGroupMessages(group.group.groupId).first().last { it.mediaJson != null }
                val descriptor = kotlinx.serialization.json.Json.decodeFromString(
                    dev.securemessenger.data.e2ee.MediaDescriptor.serializer(),
                    requireNotNull(entity.mediaJson),
                )
                val plaintext = entry.media().readBytes(descriptor).decodeToString()
                check(plaintext == expected) { "Decrypted file differs" }
            }
            else -> error("Unknown action $action")
        }
        Unit
    }

    private fun ensureLogin(email: String, password: String) {
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("group_members_button").fetchSemanticsNodes().isNotEmpty()) return
        if (compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("email_input").performTextClearance()
            compose.onNodeWithTag("email_input").performTextInput(email)
            compose.onNodeWithTag("password_input").performTextClearance()
            compose.onNodeWithTag("password_input").performTextInput(password)
            compose.onNodeWithTag("primary_action").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun openGroup(title: String, email: String, password: String) {
        ensureLogin(email, password)
        if (compose.onAllNodesWithTag("group_members_button").fetchSemanticsNodes().isNotEmpty()) return
        compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("group_members_button").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openMembers() {
        if (compose.onAllNodesWithTag("add_member_input").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("group_members_button").performClick()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("add_member_input").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun latestText(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.let { node ->
        runCatching { node.config[SemanticsProperties.Text].joinToString("") { it.text } }.getOrNull()
    }

    private suspend fun entryPoint(email: String, password: String): TestEntryPoint {
        val entry = EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Context>(),
            TestEntryPoint::class.java,
        )
        if (entry.accounts().restore() == null) entry.accounts().login(email, password)
        return entry
    }

    private fun keepScreenOn() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
