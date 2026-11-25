package dev.securemessenger.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.securemessenger.data.GroupConversation
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.media.MediaKind
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse

private fun pickMediaRequest(type: ActivityResultContracts.PickVisualMedia.VisualMediaType) =
    PickVisualMediaRequest.Builder().setMediaType(type).build()

@Composable
fun AdaptiveApp() {
    val authViewModel = hiltViewModel<AuthViewModel>()
    val chatsViewModel = hiltViewModel<ChatsViewModel>()
    val groupsViewModel = hiltViewModel<GroupsViewModel>()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    val groupsState by groupsViewModel.state.collectAsStateWithLifecycle()
    val fold by rememberFoldState()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val session = authState.session
    val onMediaSelected: (Uri, MediaKind) -> Unit = { uri, kind ->
        if (groupsState.selectedGroup != null) groupsViewModel.sendUriMedia(uri, kind)
        else chatsViewModel.sendUriMedia(uri, kind)
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onMediaSelected(it, MediaKind.PHOTO) }
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onMediaSelected(it, MediaKind.VIDEO) }
    }
    val stickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onMediaSelected(it, MediaKind.STICKER) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onMediaSelected(it, MediaKind.FILE) }
    }
    val onPickMedia: (MediaKind) -> Unit = { kind ->
        when (kind) {
            MediaKind.PHOTO -> photoLauncher.launch(pickMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            MediaKind.VIDEO, MediaKind.STICKER -> {
                if (kind == MediaKind.VIDEO) videoLauncher.launch(pickMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                else stickerLauncher.launch(pickMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            MediaKind.FILE -> fileLauncher.launch(arrayOf("*/*"))
            MediaKind.VOICE -> Unit
        }
    }

    val context = LocalContext.current
    val voiceAction = {
        if (groupsState.selectedGroup != null) {
            if (groupsState.isRecording) groupsViewModel.stopVoiceRecording() else groupsViewModel.startVoiceRecording()
        } else {
            if (chatsState.isRecording) chatsViewModel.stopVoiceRecording() else chatsViewModel.startVoiceRecording()
        }
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceAction()
    }
    val onVoiceClick = {
        if (groupsState.isRecording || chatsState.isRecording ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            voiceAction()
        } else {
            voicePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(session?.accountId) {
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(Modifier.fillMaxSize()) {
    if (chatsState.safetyNumber != null) {
        SafetyNumberScreen(
            safetyNumber = chatsState.safetyNumber!!,
            onBack = chatsViewModel::closeSafetyNumber,
            onConfirm = chatsViewModel::acceptSafetyNumber,
        )
    } else if (authState.destination == AuthDestination.CHATS && session != null) {
        HomeLayout(
            isWide = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND),
            fold = fold,
            session = session,
            searchResult = chatsState.searchResult,
            selectedChat = chatsState.selectedChat,
            draft = chatsState.draft,
            busy = chatsState.busy,
            error = chatsState.error,
            onSearch = chatsViewModel::search,
            onSelect = { user ->
                groupsViewModel.closeGroup()
                chatsViewModel.selectChat(user)
            },
            onCloseChat = chatsViewModel::closeChat,
            onDraftChange = chatsViewModel::updateDraft,
            onLogout = {
                chatsViewModel.reset()
                groupsViewModel.reset()
                authViewModel.logout()
            },
            messages = chatsState.messages,
            onSend = { chatsViewModel.sendCurrentDraft() },
            onOpenSafety = chatsViewModel::openSafetyNumber,
            groups = groupsState.groups,
            selectedGroup = groupsState.selectedGroup,
            groupMessages = groupsState.messages,
            groupDraft = groupsState.draft,
            groupBusy = groupsState.busy,
            groupError = groupsState.error,
            groupIsRecording = groupsState.isRecording,
            groupMediaProgress = groupsState.mediaProgress,
            directIsRecording = chatsState.isRecording,
            mediaProgress = chatsState.mediaProgress,
            onSelectGroup = { conversation ->
                chatsViewModel.closeChat()
                groupsViewModel.selectGroup(conversation)
            },
            onCreateGroup = groupsViewModel::createGroup,
            onCloseGroup = groupsViewModel::closeGroup,
            onGroupDraftChange = groupsViewModel::updateDraft,
            onSendGroup = groupsViewModel::sendCurrentDraft,
            onAddMember = groupsViewModel::addMember,
            onRemoveMember = groupsViewModel::removeMember,
            onPickMedia = onPickMedia,
            onVoice = onVoiceClick,
            onVoiceGroup = onVoiceClick,
        )
    } else {
        AuthScreens(authState, authViewModel)
    }
    }
}

@Composable
fun HomeLayout(
    isWide: Boolean,
    fold: FoldState,
    session: TokenResponse,
    searchResult: UserResponse?,
    selectedChat: UserResponse?,
    draft: String,
    busy: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onSelect: (UserResponse) -> Unit,
    onCloseChat: () -> Unit,
    onDraftChange: (String) -> Unit,
    onLogout: () -> Unit,
    messages: List<MessageEntity> = emptyList(),
    onSend: (String) -> Unit = {},
    onOpenSafety: () -> Unit = {},
    groups: List<GroupConversation> = emptyList(),
    selectedGroup: GroupConversation? = null,
    groupMessages: List<MessageEntity> = emptyList(),
    groupDraft: String = "",
    groupBusy: Boolean = false,
    groupError: String? = null,
    groupIsRecording: Boolean = false,
    groupMediaProgress: String? = null,
    directIsRecording: Boolean = false,
    mediaProgress: String? = null,
    onSelectGroup: (GroupConversation) -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    onCloseGroup: () -> Unit = {},
    onGroupDraftChange: (String) -> Unit = {},
    onSendGroup: () -> Unit = {},
    onAddMember: (String) -> Unit = {},
    onRemoveMember: (String) -> Unit = {},
    onPickMedia: (MediaKind) -> Unit = {},
    onVoice: () -> Unit = {},
    onVoiceGroup: () -> Unit = {},
) {
    if (isWide) {
        Row(Modifier.fillMaxSize()) {
            ChatListPane(
                modifier = Modifier.width(340.dp).testTag("chat_list"),
                username = session.username,
                searchResult = searchResult,
                selectedChat = selectedChat,
                busy = busy,
                error = error,
                onSearch = onSearch,
                onSelect = onSelect,
                onLogout = onLogout,
                groups = groups,
                selectedGroup = selectedGroup,
                groupError = groupError,
                onSelectGroup = onSelectGroup,
                onCreateGroup = onCreateGroup,
            )
            Box(Modifier.weight(1f).testTag("chat_detail")) {
                when {
                    selectedGroup != null -> GroupChatDetail(
                        selectedGroup, session.accountId, groupMessages, groupDraft, fold, false, groupBusy, groupError,
                        groupIsRecording, groupMediaProgress, onCloseGroup, onGroupDraftChange, onSendGroup,
                        onPickMedia, onVoiceGroup, onAddMember, onRemoveMember,
                    )
                    selectedChat == null -> EmptyChat()
                    else -> ChatDetail(
                        selectedChat, draft, fold, false, onCloseChat, onDraftChange, messages, onSend,
                        error == "IDENTITY_CHANGED", onOpenSafety, onPickMedia, onVoice, directIsRecording, busy, mediaProgress,
                    )
                }
            }
        }
    } else if (selectedChat == null && selectedGroup == null) {
        ChatListPane(
            modifier = Modifier.fillMaxSize().testTag("chat_list"),
            username = session.username,
            searchResult = searchResult,
            selectedChat = null,
            busy = busy,
            error = error,
            onSearch = onSearch,
            onSelect = onSelect,
            onLogout = onLogout,
            groups = groups,
            selectedGroup = selectedGroup,
            groupError = groupError,
            onSelectGroup = onSelectGroup,
            onCreateGroup = onCreateGroup,
        )
    } else if (selectedGroup != null) {
        GroupChatDetail(
            selectedGroup, session.accountId, groupMessages, groupDraft, fold, true, groupBusy, groupError,
            groupIsRecording, groupMediaProgress, onCloseGroup, onGroupDraftChange, onSendGroup,
            onPickMedia, onVoiceGroup, onAddMember, onRemoveMember,
        )
    } else {
        Box(Modifier.testTag("chat_detail")) {
            ChatDetail(
                requireNotNull(selectedChat), draft, fold, true, onCloseChat, onDraftChange, messages, onSend,
                error == "IDENTITY_CHANGED", onOpenSafety, onPickMedia, onVoice, directIsRecording, busy, mediaProgress,
            )
        }
    }
}

@Composable
private fun ChatListPane(
    modifier: Modifier,
    username: String,
    searchResult: UserResponse?,
    selectedChat: UserResponse?,
    busy: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onSelect: (UserResponse) -> Unit,
    onLogout: () -> Unit,
    groups: List<GroupConversation> = emptyList(),
    selectedGroup: GroupConversation? = null,
    groupError: String? = null,
    onSelectGroup: (GroupConversation) -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чаты", style = MaterialTheme.typography.headlineSmall)
                Text("@$username", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onLogout) { Text("Выйти") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Поиск по @username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("username_search"),
        )
        Button(onClick = { onSearch(search) }, enabled = !busy && search.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("search_button")) {
            Text("Найти пользователя")
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        error?.let { Text(chatErrorText(it), color = MaterialTheme.colorScheme.error) }
        searchResult?.let { user ->
            Card(Modifier.fillMaxWidth().testTag("search_result").clickable { onSelect(user) }) {
                Text("@${user.username}", Modifier.padding(16.dp))
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            selectedChat?.let { chat ->
                item {
                    Card(Modifier.fillMaxWidth().clickable { onSelect(chat) }) {
                        Text("@${chat.username}", Modifier.padding(16.dp))
                    }
                }
            }
            if (selectedChat == null) {
                item { Text("Чатов пока нет. Найдите пользователя по юзернейму.") }
            }
            item {
                GroupListSection(groups, selectedGroup, onSelectGroup, onCreateGroup, groupError)
            }
        }
    }
}

@Composable
private fun ChatDetail(
    chat: UserResponse,
    draft: String,
    fold: FoldState,
    showBack: Boolean,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    messages: List<MessageEntity>,
    onSend: (String) -> Unit,
    identityChanged: Boolean,
    onOpenSafety: () -> Unit,
    onPick: (MediaKind) -> Unit = {},
    onVoice: () -> Unit = {},
    isRecording: Boolean = false,
    busy: Boolean = false,
    mediaProgress: String? = null,
) {
    if (showBack) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) TextButton(onClick = onBack) { Text("Назад") }
            Text("@${chat.username}", style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = onOpenSafety, modifier = Modifier.testTag("open_safety")) {
            Text(if (identityChanged) "Ключ изменился. Проверить safety number" else "Проверить safety number")
        }
        HorizontalDivider()
        if (fold.halfOpened && fold.horizontal) {
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_content")) {
                MessageHistory(messages)
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_input")) {
                Composer(draft, onDraftChange, onSend, onPick, onVoice, isRecording, busy, mediaProgress)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessageHistory(messages)
            }
            Composer(draft, onDraftChange, onSend, onPick, onVoice, isRecording, busy, mediaProgress)
        }
    }
}

@Composable
internal fun MessageHistory(messages: List<MessageEntity>) {
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Сообщений пока нет", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val listState = rememberLazyListState()
    val latestOutgoingId = messages.lastOrNull { it.outgoing }?.messageId
    LaunchedEffect(messages.size) {
        listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("message_history"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.messageId }) { message ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
            ) {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        if (message.mediaJson != null) {
                            MediaMessageBubble(message.mediaJson, message.body)
                        } else {
                            Text(
                                message.body,
                                modifier = Modifier.testTag(
                                    when {
                                        !message.outgoing -> "incoming_message_body"
                                        message.messageId == latestOutgoingId -> "latest_outgoing_body"
                                        else -> "outgoing_message_body"
                                    },
                                ),
                            )
                        }
                        if (message.outgoing) {
                            Text(
                                message.status,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag(
                                    if (message.messageId == latestOutgoingId) "latest_outgoing_status" else "message_status",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onPick: (MediaKind) -> Unit = {},
    onVoice: () -> Unit = {},
    isRecording: Boolean = false,
    busy: Boolean = false,
    mediaProgress: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AttachmentControls(onPick)
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Черновик сообщения") },
                modifier = Modifier.weight(1f).testTag("message_input"),
                maxLines = 4,
            )
            IconButton(onClick = onVoice, modifier = Modifier.testTag("voice_button")) {
                Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = if (isRecording) "Остановить запись" else "Записать голосовое")
            }
            Button(
                onClick = { onSend(draft) },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).testTag("send_button"),
            ) {
                Text("Отправить")
            }
        }
        mediaProgress?.let { Text("Загружено $it байт", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EmptyChat() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Выберите чат или найдите пользователя")
    }
}

private fun chatErrorText(code: String): String = when (code) {
    "USER_NOT_FOUND" -> "Пользователь не найден"
    "INVALID_USERNAME" -> "Некорректный юзернейм"
    "INVALID_CREDENTIALS" -> "Сессия истекла. Войдите снова"
    "NETWORK_ERROR" -> "Сервер недоступен"
    else -> "Не удалось выполнить поиск"
}
