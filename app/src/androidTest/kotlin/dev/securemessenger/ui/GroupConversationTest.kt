package dev.securemessenger.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.EntryPointAccessors
import dev.securemessenger.MainActivity
import dev.securemessenger.di.AcceptanceEntryPoint
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.e2ee.MediaDescriptor
import dev.securemessenger.data.media.EncryptedMediaDataSourceFactory
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupConversationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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
            "prepare" -> {
                ensureLogin(email, password)
                entryPoint(email, password).messages().sync()
            }
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
                val entry = entryPoint(email, password)
                val group = entry.groupChats().list().single { it.title == title }
                withTimeout(45_000) {
                    entry.messages().observeGroupMessages(group.group.groupId)
                        .filter { chat -> chat.any { it.body == expected } }
                        .first()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("message_history").performScrollToNode(hasText(expected))
                compose.onNodeWithText(expected).assertIsDisplayed()
                withTimeout(10_000) {
                    entry.messages().observeGroupMessages(group.group.groupId)
                        .filter { chat -> chat.single { it.body == expected }.status == "READ" }
                        .first()
                }
            }
            "expect_status" -> {
                openGroup(title, email, password)
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("message_history").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("message_history").performScrollToNode(hasTestTag("latest_outgoing_status"))
                compose.waitUntil(45_000) { latestText("latest_outgoing_status") == expected }
            }
            "force_read" -> {
                ensureLogin(email, password)
                val entry = entryPoint(email, password)
                val group = entry.groupChats().list().single { it.title == title }
                entry.messages().markGroupRead(group.group)
            }
            "inspect_receipts" -> {
                ensureLogin(email, password)
                val entry = entryPoint(email, password)
                entry.messages().sync()
                val group = entry.groupChats().list().single { it.title == title }
                val message = entry.messages().observeGroupMessages(group.group.groupId).first().single { it.body == expected }
                val receipts = entry.database().groupReceipts().observeAll().first().filter { it.groupMessageId == message.messageId }
                val details = receipts.joinToString { "${it.recipientDeviceId}:${it.state}:${it.envelopeId}" }
                val pending = entry.database().outboxes().all()
                    .filter { it.groupId == group.group.groupId }
                    .joinToString { "${it.envelopeId}:${it.messageId}" }
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString("receipt_state", "message=${message.status}; outgoing=${message.outgoing}; receipts=$details")
                        putString("outbox_state", pending)
                    },
                )
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
                val entry = entryPoint(email, password)
                entry.messages().sync()
                val group = entry.groupChats().list().single { it.title == title }
                val entity = entry.messages().observeGroupMessages(group.group.groupId).first().last { it.mediaJson != null }
                val descriptor = kotlinx.serialization.json.Json.decodeFromString(
                    MediaDescriptor.serializer(),
                    requireNotNull(entity.mediaJson),
                )
                entry.media().reader(descriptor).use { reader ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var position = 0L
                    while (position < reader.plaintextBytes) {
                        val count = reader.read(position, buffer, 0, buffer.size)
                        check(count > 0) { "Unexpected end of decrypted media" }
                        digest.update(buffer, 0, count)
                        position += count
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                        "Decrypted media digest differs"
                    }
                }
                if (mime.startsWith("video/") || mime.startsWith("audio/")) verifyPlayback(entry, descriptor)
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
        compose.waitUntil(20_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
        compose.waitForIdle()
        val localUsername = InstrumentationRegistry.getArguments().getString("local_username").orEmpty()
        if (compose.onAllNodesWithTag("group_members_button").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()) {
            if (localUsername.isNotEmpty() && compose.onAllNodesWithText("@$localUsername").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText("Выйти").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("email_input").fetchSemanticsNodes().isNotEmpty() }
            } else {
                return
            }
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

    private suspend fun verifyPlayback(entry: AcceptanceEntryPoint, descriptor: MediaDescriptor) {
        val file = entry.media().existingCiphertext(descriptor) ?: entry.media().download(descriptor)
        val factory = EncryptedMediaDataSourceFactory(file, Base64.getDecoder().decode(descriptor.key))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val created = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory))
                .build()
            player = created
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    ready.countDown()
                }
            })
            created.setMediaItem(MediaItem.fromUri("encrypted://${descriptor.mediaId}"))
            created.prepare()
        }
        try {
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Media3 did not become ready")
            assertNull(failure.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(requireNotNull(player).duration > 0)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release() }
        }
    }

    private suspend fun entryPoint(email: String, password: String): AcceptanceEntryPoint {
        val entry = EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Context>(),
            AcceptanceEntryPoint::class.java,
        )
        if (entry.accounts().restore() == null) entry.accounts().login(email, password)
        return entry
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

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
