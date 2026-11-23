package dev.securemessenger.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.securemessenger.data.AccountRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.e2ee.IdentityChangedException
import dev.securemessenger.data.local.DraftStore
import dev.securemessenger.data.local.MessageEntity
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
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val messages: MessageRepository,
    private val drafts: DraftStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = mutableState.asStateFlow()
    private var draftJob: Job? = null
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
            if (mutableState.value.selectedChat == user) {
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
                updateDraft("")
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

    fun closeChat() {
        messagesJob?.cancel()
        syncJob?.cancel()
        savedState.remove<String>(CHAT_ID)
        savedState.remove<String>(CHAT_USERNAME)
        mutableState.value = mutableState.value.copy(selectedChat = null, messages = emptyList(), draft = "")
    }

    fun updateDraft(value: String) {
        val chat = mutableState.value.selectedChat ?: return
        mutableState.value = mutableState.value.copy(draft = value)
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(150)
            drafts.save("direct:${chat.accountId}", value)
        }
    }

    fun reset() {
        closeChat()
        mutableState.value = ChatsUiState()
    }

    private companion object {
        const val CHAT_ID = "selected_chat_id"
        const val CHAT_USERNAME = "selected_chat_username"
    }
}
