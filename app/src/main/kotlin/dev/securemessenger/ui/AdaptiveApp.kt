package dev.securemessenger.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.securemessenger.R
import dev.securemessenger.data.GroupConversation
import dev.securemessenger.data.local.DirectConversationEntity
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.media.MediaKind
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import java.text.SimpleDateFormat
import java.util.Date

private fun pickMediaRequest(type: ActivityResultContracts.PickVisualMedia.VisualMediaType) =
    PickVisualMediaRequest.Builder().setMediaType(type).build()

@Composable
fun AdaptiveApp(notificationKey: String? = null, onNotificationHandled: () -> Unit = {}) {
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
        chatsViewModel.setAccount(session?.accountId)
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(notificationKey, session?.accountId) {
        if (notificationKey != null && session != null) {
            chatsViewModel.openNotification(notificationKey)
            onNotificationHandled()
        }
    }

    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
    if (!authState.restoreComplete) {
        Box(Modifier.fillMaxSize().testTag("session_loading"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (chatsState.safetyNumber != null) {
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
            conversations = chatsState.conversations,
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
    conversations: List<DirectConversationEntity> = emptyList(),
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
                modifier = Modifier.width(340.dp).testTag("chat_list_pane"),
                username = session.username,
                searchResult = searchResult,
                selectedChat = selectedChat,
                conversations = conversations,
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
            modifier = Modifier.fillMaxSize().testTag("chat_list_pane"),
            username = session.username,
            searchResult = searchResult,
            selectedChat = null,
            conversations = conversations,
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
    conversations: List<DirectConversationEntity> = emptyList(),
    selectedGroup: GroupConversation? = null,
    groupError: String? = null,
    onSelectGroup: (GroupConversation) -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(username)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Чаты", style = MaterialTheme.typography.headlineSmall)
                Text("@$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onLogout) { Text("Выйти") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Поиск по @username") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("username_search"),
        )
        Button(onClick = { onSearch(search) }, enabled = !busy && search.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp).testTag("search_button")) {
            Text("Найти пользователя")
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        error?.let { Text(chatErrorText(it), color = MaterialTheme.colorScheme.error) }
        searchResult?.let { user ->
            Card(
                Modifier.fillMaxWidth().testTag("search_result").clickable { onSelect(user) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatAvatar(user.username)
                    Text("@${user.username}", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selectedChat?.takeIf { selected -> conversations.none { it.accountId == selected.accountId } }?.let { chat ->
                item(key = "selected:${chat.accountId}") {
                    Card(Modifier.fillMaxWidth().clickable { onSelect(chat) }) {
                        Text("@${chat.username}", Modifier.padding(16.dp))
                    }
                }
            }
            items(conversations, key = { "direct:${it.accountId}" }) { conversation ->
                DirectConversationCard(conversation, selectedChat?.accountId == conversation.accountId, onSelect)
            }
            if (selectedChat == null && conversations.isEmpty() && groups.isEmpty()) {
                item(key = "empty_direct") { Text("Чатов пока нет. Найдите пользователя по юзернейму.") }
            }
            item(key = "group_creation") {
                GroupCreationSection(groups.isNotEmpty(), selectedGroup, onCreateGroup, groupError)
            }
            groupCards(groups, selectedGroup, onSelectGroup)
        }
    }
}

@Composable
private fun DirectConversationCard(
    conversation: DirectConversationEntity,
    selected: Boolean,
    onSelect: (UserResponse) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onSelect(UserResponse(conversation.accountId, conversation.username)) }
            .testTag("conversation_${conversation.accountId}"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(conversation.username)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "@${conversation.username}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.lastCreatedAt > 0) {
                        Text(
                            SimpleDateFormat("HH:mm", locale).format(Date(conversation.lastCreatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val body = conversation.lastBody.ifBlank {
                        if (conversation.lastCreatedAt == 0L) "Нет сообщений" else "Медиа"
                    }
                    Text(
                        (if (conversation.lastOutgoing) "Вы: " else "") + body,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.unreadCount > 0) {
                        Badge { Text(conversation.unreadCount.toString()) }
                    }
                }
            }
        }
        HorizontalDivider(
            Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        )
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
            ChatAvatar(chat.username)
            Text("@${chat.username}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
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
internal fun MessageHistory(messages: List<MessageEntity>, senderNames: Map<String, String> = emptyMap()) {
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
            MessageBubble(
                message = message,
                latestOutgoing = message.messageId == latestOutgoingId,
                senderName = senderNames[message.senderAccountId],
            )
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AttachmentControls(onPick)
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Черновик сообщения") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).testTag("message_input"),
                    maxLines = 4,
                )
                IconButton(onClick = onVoice, modifier = Modifier.testTag("voice_button")) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = if (isRecording) "Остановить запись" else "Записать голосовое")
                }
                IconButton(
                    onClick = { onSend(draft) },
                    enabled = !busy && draft.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp).size(48.dp).background(
                        if (!busy && draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        CircleShape,
                    ).testTag("send_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (!busy && draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            mediaProgress?.let { Text("Загружено $it байт", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EmptyChat() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.ic_ami_foreground),
                contentDescription = "Ami",
                modifier = Modifier.size(72.dp).padding(bottom = 12.dp),
            )
            Text("Выберите чат или найдите пользователя", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun chatErrorText(code: String): String = when (code) {
    "USER_NOT_FOUND" -> "Пользователь не найден"
    "INVALID_USERNAME" -> "Некорректный юзернейм"
    "INVALID_CREDENTIALS" -> "Сессия истекла. Войдите снова"
    "NETWORK_ERROR" -> "Сервер недоступен"
    else -> "Не удалось выполнить поиск"
}
