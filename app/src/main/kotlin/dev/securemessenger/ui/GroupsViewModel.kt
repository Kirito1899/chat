package dev.securemessenger.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.securemessenger.data.GroupChatRepository
import dev.securemessenger.data.GroupConversation
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.e2ee.IdentityChangedException
import dev.securemessenger.data.local.DraftStore
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.media.MediaInput
import dev.securemessenger.data.media.MediaKind
import dev.securemessenger.data.media.MediaMessagePreparer
import dev.securemessenger.data.media.MediaService
import dev.securemessenger.data.media.VoiceRecordingRepository
import dev.securemessenger.shared.ValidationException
import dev.securemessenger.data.remote.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class GroupsUiState(
    val groups: List<GroupConversation> = emptyList(),
    val selectedGroup: GroupConversation? = null,
    val messages: List<MessageEntity> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val isRecording: Boolean = false,
    val mediaProgress: String? = null,
)

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupChats: GroupChatRepository,
    private val messages: MessageRepository,
    private val drafts: DraftStore,
    private val media: MediaService,
    private val mediaPreparer: MediaMessagePreparer,
    private val voiceRecording: VoiceRecordingRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = mutableState.asStateFlow()
    private var messagesJob: Job? = null
    private var syncJob: Job? = null
    private var draftJob: Job? = null
    private var draftRevision = 0L

    init {
        viewModelScope.launch {
            if (savedState.get<String>(GROUP_ID) == null) {
                drafts.selectedGroupId()?.let { savedState[GROUP_ID] = it }
            }
        }
        syncJob = viewModelScope.launch {
            while (true) {
                try {
                    refreshGroups()
                    messages.sync()
                    mutableState.value.selectedGroup?.let { messages.markGroupRead(it.group) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                } catch (_: ApiException) {
                } catch (_: IdentityChangedException) {
                    mutableState.value = mutableState.value.copy(error = "GROUP_IDENTITY_CHANGED")
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(error = "SYNC_FAILED")
                }
                delay(5_000)
            }
        }
    }

    fun selectGroup(conversation: GroupConversation) {
        val revision = ++draftRevision
        savedState[GROUP_ID] = conversation.group.groupId
        viewModelScope.launch { drafts.saveSelectedGroup(conversation.group.groupId) }
        messagesJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedGroup = conversation, messages = emptyList(), draft = "", error = null)
        messagesJob = viewModelScope.launch {
            messages.observeGroupMessages(conversation.group.groupId).collect { chat ->
                mutableState.value = mutableState.value.copy(messages = chat)
            }
        }
        viewModelScope.launch {
            try {
                messages.markGroupRead(conversation.group)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
            } catch (_: ApiException) {
            } catch (_: IdentityChangedException) {
                mutableState.value = mutableState.value.copy(error = "GROUP_IDENTITY_CHANGED")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "SYNC_FAILED")
            }
        }
        viewModelScope.launch {
            val draft = drafts.get("group:${conversation.group.groupId}")
            if (mutableState.value.selectedGroup == conversation && draftRevision == revision) {
                mutableState.value = mutableState.value.copy(draft = draft)
            }
        }
    }

    fun closeGroup() {
        draftRevision++
        savedState.remove<String>(GROUP_ID)
        viewModelScope.launch { drafts.clearSelectedGroup() }
        messagesJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedGroup = null, messages = emptyList(), draft = "")
    }

    fun createGroup(title: String, memberUsernames: List<String>) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val created = groupChats.create(title, memberUsernames)
                selectGroup(created)
                refreshGroups()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (error: ValidationException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(error = "NETWORK_ERROR")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "GROUP_CREATE_FAILED")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun addMember(username: String) {
        val selected = mutableState.value.selectedGroup ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val updated = groupChats.addMembers(selected.group, listOf(username))
                mutableState.value = mutableState.value.copy(selectedGroup = updated)
                refreshGroups()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (error: ValidationException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "GROUP_MEMBER_ADD_FAILED")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun removeMember(accountId: String) {
        val selected = mutableState.value.selectedGroup ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val updated = groupChats.removeMember(selected.group, accountId)
                mutableState.value = mutableState.value.copy(selectedGroup = updated)
                refreshGroups()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (error: ValidationException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "GROUP_MEMBER_REMOVE_FAILED")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun sendCurrentDraft() {
        val selected = mutableState.value.selectedGroup ?: return
        val body = mutableState.value.draft
        if (body.isBlank() || mutableState.value.busy) return
        if (body.length > 4000) {
            mutableState.value = mutableState.value.copy(error = "MESSAGE_TOO_LONG")
            return
        }
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                messages.sendGroupText(selected.group, body)
                clearDraft()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IdentityChangedException) {
                mutableState.value = mutableState.value.copy(error = "GROUP_IDENTITY_CHANGED")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "SEND_PENDING")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun sendUriMedia(uri: Uri, kind: MediaKind) {
        val selected = mutableState.value.selectedGroup ?: return
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                sendMediaInput(selected, mediaPreparer.fromUri(uri, kind))
            } catch (error: CancellationException) {
                throw error
            } catch (_: IdentityChangedException) {
                mutableState.value = mutableState.value.copy(error = "GROUP_IDENTITY_CHANGED")
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
        val selected = mutableState.value.selectedGroup ?: return
        if (!voiceRecording.isRecording) return
        viewModelScope.launch {
            try {
                val recording = voiceRecording.stop()
                mutableState.value = mutableState.value.copy(isRecording = false, busy = true, error = null)
                sendMediaInput(selected, mediaPreparer.fromRecording(recording))
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

    fun updateDraft(value: String) {
        val selected = mutableState.value.selectedGroup ?: return
        draftRevision++
        mutableState.value = mutableState.value.copy(draft = value)
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(150)
            drafts.save("group:${selected.group.groupId}", value)
        }
    }

    fun reset() {
        voiceRecording.cancel()
        syncJob?.cancel()
        messagesJob?.cancel()
        draftJob?.cancel()
        savedState.remove<String>(GROUP_ID)
        viewModelScope.launch { drafts.clearSelectedGroup() }
        mutableState.value = GroupsUiState()
    }

    private suspend fun sendMediaInput(selected: GroupConversation, input: MediaInput) {
        val caption = mutableState.value.draft
        val prepared = media.prepare(input.open, input.fileName, input.mimeType, input.size, { uploaded ->
            mutableState.value = mutableState.value.copy(mediaProgress = "$uploaded")
        }, input.cleanup, destinationChatId = "group:${selected.group.groupId}", caption = caption)
        messages.sendGroupMedia(selected.group, prepared.descriptor, caption)
        clearDraft()
    }

    private suspend fun clearDraft() {
        val selected = mutableState.value.selectedGroup ?: return
        draftRevision++
        draftJob?.cancel()
        drafts.save("group:${selected.group.groupId}", "")
        mutableState.value = mutableState.value.copy(draft = "")
    }

    private suspend fun refreshGroups() {
        val list = groupChats.list()
        val selectedId = mutableState.value.selectedGroup?.group?.groupId ?: savedState.get<String>(GROUP_ID)
        val selected = list.find { it.group.groupId == selectedId }
        mutableState.value = mutableState.value.copy(groups = list)
        when {
            selectedId == null -> Unit
            selected == null -> closeGroup()
            mutableState.value.selectedGroup == null -> selectGroup(selected)
            mutableState.value.selectedGroup != selected -> mutableState.value = mutableState.value.copy(selectedGroup = selected)
        }
    }

    private companion object {
        const val GROUP_ID = "selected_group_id"
    }
}
