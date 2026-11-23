package dev.securemessenger.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse

@Composable
fun AdaptiveApp() {
    val authViewModel = hiltViewModel<AuthViewModel>()
    val chatsViewModel = hiltViewModel<ChatsViewModel>()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    val fold by rememberFoldState()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val session = authState.session

    Surface(Modifier.fillMaxSize()) {
    if (authState.destination == AuthDestination.CHATS && session != null) {
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
            onSelect = chatsViewModel::selectChat,
            onCloseChat = chatsViewModel::closeChat,
            onDraftChange = chatsViewModel::updateDraft,
            onLogout = {
                chatsViewModel.reset()
                authViewModel.logout()
            },
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
            )
            Box(Modifier.weight(1f).testTag("chat_detail")) {
                if (selectedChat == null) {
                    EmptyChat()
                } else {
                    ChatDetail(selectedChat, draft, fold, false, onCloseChat, onDraftChange)
                }
            }
        }
    } else if (selectedChat == null) {
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
        )
    } else {
        Box(Modifier.testTag("chat_detail")) {
            ChatDetail(selectedChat, draft, fold, true, onCloseChat, onDraftChange)
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
) {
    if (showBack) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) TextButton(onClick = onBack) { Text("Назад") }
            Text("@${chat.username}", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        if (fold.halfOpened && fold.horizontal) {
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_content")) {
                MessageHistory()
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_input")) {
                Composer(draft, onDraftChange)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessageHistory()
            }
            Composer(draft, onDraftChange)
        }
    }
}

@Composable
private fun MessageHistory() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Сообщения пока не отправляются. Отправка будет включена только после E2EE-приращения.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Черновик сообщения") },
            modifier = Modifier.weight(1f).testTag("message_input"),
            maxLines = 4,
        )
        Button(onClick = {}, enabled = false, modifier = Modifier.padding(start = 8.dp)) {
            Text("Отправить")
        }
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
