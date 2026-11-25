package dev.securemessenger.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.securemessenger.data.AccountRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.e2ee.IdentityChangedException
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.local.DraftStore
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.media.MediaInput
import dev.securemessenger.data.media.MediaKind
import dev.securemessenger.data.media.MediaMessagePreparer
import dev.securemessenger.data.media.MediaService
import dev.securemessenger.data.media.VoiceRecordingRepository
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class ChatsUiState(
    val selectedChat: UserResponse? = null,
    val searchResult: UserResponse? = null,
    val messages: List<MessageEntity> = emptyList(),
    val draft: String = "",
    val safetyNumber: SafetyNumber? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val isRecording: Boolean = false,
    val mediaProgress: String? = null,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val messages: MessageRepository,
    private val drafts: DraftStore,
    private val savedState: SavedStateHandle,
    private val media: MediaService,
    private val mediaPreparer: MediaMessagePreparer,
    private val voiceRecording: VoiceRecordingRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = mutableState.asStateFlow()
    private var draftJob: Job? = null
    private var draftRevision = 0L
    private var messagesJob: Job? = null
    private var syncJob: Job? = null

    init {
        val accountId = savedState.get<String>(CHAT_ID)
        val username = savedState.get<String>(CHAT_USERNAME)
        if (accountId != null && username != null) {
            selectChat(UserResponse(accountId, username))
        }
    }

    fun search(username: String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = accounts.searchByUsername(username)
                mutableState.value = mutableState.value.copy(searchResult = result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (error: ValidationException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(error = "NETWORK_ERROR")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "INTERNAL_ERROR")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun selectChat(user: UserResponse) {
        val revision = ++draftRevision
        savedState[CHAT_ID] = user.accountId
        savedState[CHAT_USERNAME] = user.username
        messagesJob?.cancel()
        syncJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedChat = user, messages = emptyList(), draft = "", error = null)
        messagesJob = viewModelScope.launch {
            messages.observeMessages(user.accountId).collect {
                mutableState.value = mutableState.value.copy(messages = it)
            }
        }
        viewModelScope.launch {
            val draft = drafts.get("direct:${user.accountId}")
            if (mutableState.value.selectedChat == user && draftRevision == revision) {
                mutableState.value = mutableState.value.copy(draft = draft)
            }
        }
        syncJob = viewModelScope.launch {
            while (true) {
                try {
                    messages.sync()
                    if (mutableState.value.selectedChat == user) messages.markChatRead(user)
                    mutableState.value = mutableState.value.copy(error = null)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IdentityChangedException) {
                    mutableState.value = mutableState.value.copy(error = "IDENTITY_CHANGED")
                } catch (_: IOException) {
                } catch (_: ApiException) {
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(error = "SYNC_FAILED")
                }
                delay(5_000)
            }
        }
    }

    fun sendCurrentDraft() {
        val user = mutableState.value.selectedChat ?: return
        val body = mutableState.value.draft
        if (body.isBlank()) return
        if (body.length > 4000) {
            mutableState.value = mutableState.value.copy(error = "MESSAGE_TOO_LONG")
            return
        }
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                messages.sendText(user, body)
                draftRevision++
                draftJob?.cancel()
                drafts.save("direct:${user.accountId}", "")
                mutableState.value = mutableState.value.copy(draft = "")
            } catch (error: CancellationException) {
                throw error
            } catch (_: IdentityChangedException) {
                mutableState.value = mutableState.value.copy(error = "IDENTITY_CHANGED")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "SEND_PENDING")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun sendUriMedia(uri: Uri, kind: MediaKind) {
        val user = mutableState.value.selectedChat ?: return
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val input = mediaPreparer.fromUri(uri, kind)
                sendMediaInput(user, input)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(error = "NETWORK_ERROR")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "MEDIA_SEND_FAILED")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false, mediaProgress = null)
            }
        }
    }

    fun startVoiceRecording() {
        if (mutableState.value.busy) return
        try {
            voiceRecording.start()
            mutableState.value = mutableState.value.copy(isRecording = true, error = null)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "VOICE_RECORDING_FAILED")
        }
    }

    fun stopVoiceRecording() {
        val user = mutableState.value.selectedChat ?: return
        if (!voiceRecording.isRecording) return
        viewModelScope.launch {
            try {
                val recording = voiceRecording.stop()
                mutableState.value = mutableState.value.copy(isRecording = false, busy = true, error = null)
                sendMediaInput(user, mediaPreparer.fromRecording(recording))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "MEDIA_SEND_FAILED")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false, isRecording = false, mediaProgress = null)
            }
        }
    }

    fun cancelVoiceRecording() {
        voiceRecording.cancel()
        mutableState.value = mutableState.value.copy(isRecording = false)
    }

    private suspend fun sendMediaInput(user: UserResponse, input: MediaInput) {
        val caption = mutableState.value.draft
        val prepared = media.prepare(input.open, input.fileName, input.mimeType, input.size, { uploaded ->
            mutableState.value = mutableState.value.copy(mediaProgress = "$uploaded")
        }, input.cleanup, destinationChatId = "direct:${user.accountId}", recipientUsername = user.username, caption = caption)
        messages.sendMedia(user, prepared.descriptor, caption)
        draftRevision++
        draftJob?.cancel()
        drafts.save("direct:${user.accountId}", "")
        mutableState.value = mutableState.value.copy(draft = "")
    }

    fun openSafetyNumber() {
        val user = mutableState.value.selectedChat ?: return
        viewModelScope.launch {
            try {
                val number = messages.safetyNumber(user)
                mutableState.value = mutableState.value.copy(safetyNumber = number)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "SAFETY_NUMBER_UNAVAILABLE")
            }
        }
    }

    fun acceptSafetyNumber() {
        val user = mutableState.value.selectedChat ?: return
        viewModelScope.launch {
            try {
                messages.acceptChangedIdentity(user)
                mutableState.value = mutableState.value.copy(safetyNumber = null, error = null)
                messages.sync()
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(safetyNumber = null, error = "IDENTITY_CHANGED")
            }
        }
    }

    fun closeSafetyNumber() {
        mutableState.value = mutableState.value.copy(safetyNumber = null)
    }

    fun closeChat() {
        draftRevision++
        messagesJob?.cancel()
        syncJob?.cancel()
        savedState.remove<String>(CHAT_ID)
        savedState.remove<String>(CHAT_USERNAME)
        mutableState.value = mutableState.value.copy(selectedChat = null, messages = emptyList(), draft = "")
    }

    fun updateDraft(value: String) {
        val chat = mutableState.value.selectedChat ?: return
        draftRevision++
        mutableState.value = mutableState.value.copy(draft = value)
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(150)
            drafts.save("direct:${chat.accountId}", value)
        }
    }

    fun reset() {
        voiceRecording.cancel()
        closeChat()
        mutableState.value = ChatsUiState()
    }

    private companion object {
        const val CHAT_ID = "selected_chat_id"
        const val CHAT_USERNAME = "selected_chat_username"
    }
}
