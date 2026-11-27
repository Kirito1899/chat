package dev.securemessenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.securemessenger.data.GroupConversation
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.media.MediaKind
import dev.securemessenger.shared.GroupMemberResponse

@Composable
fun GroupCreationSection(
    hasGroups: Boolean,
    selectedGroup: GroupConversation?,
    onCreate: (String, List<String>) -> Unit,
    error: String?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var member by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(selectedGroup?.group?.groupId) {
        if (selectedGroup != null) {
            expanded = false
            title = ""
            member = ""
        }
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("create_group_toggle")) {
        Text(if (expanded) "Скрыть создание группы" else "Создать группу")
    }
    if (expanded) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название группы") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("group_title_input"),
        )
        OutlinedTextField(
            value = member,
            onValueChange = { member = it },
            label = { Text("@username участника") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("group_member_input"),
        )
        Button(
            onClick = {
                onCreate(title, listOf(member))
            },
            enabled = title.isNotBlank() && member.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("create_group_button"),
        ) {
            Text("Создать зашифрованную группу")
        }
    }
    error?.let {
        Text(groupErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("group_error"))
    }
    if (!hasGroups) {
        Text("Групп пока нет", style = MaterialTheme.typography.bodySmall)
    }
}

fun LazyListScope.groupCards(
    groups: List<GroupConversation>,
    selectedGroup: GroupConversation?,
    onSelect: (GroupConversation) -> Unit,
) {
    items(groups, key = { it.group.groupId }) { conversation ->
        Column(
            Modifier.fillMaxWidth()
                .background(
                    if (selectedGroup?.group?.groupId == conversation.group.groupId) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    } else {
                        Color.Transparent
                    },
                )
                .testTag("group_item_${conversation.group.groupId}")
                .clickable { onSelect(conversation) },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(conversation.title)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${conversation.group.members.size} участников", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(
                Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
fun GroupChatDetail(
    conversation: GroupConversation,
    currentAccountId: String,
    messages: List<MessageEntity>,
    draft: String,
    fold: FoldState,
    showBack: Boolean,
    busy: Boolean,
    error: String?,
    isRecording: Boolean,
    mediaProgress: String?,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPick: (MediaKind) -> Unit,
    onVoice: () -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    if (showBack) BackHandler(onBack = onBack)
    var showMembers by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) TextButton(onClick = onBack) { Text("Назад") }
            ChatAvatar(conversation.title)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${conversation.group.members.size} участников", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showMembers = !showMembers }, modifier = Modifier.testTag("group_members_button")) {
                Text("Участники")
            }
        }
        if (showMembers) {
            GroupMembersPanel(conversation, currentAccountId, onAddMember, onRemoveMember)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        HorizontalDivider()
        if (fold.halfOpened && fold.horizontal) {
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_content")) {
                MessageHistory(messages, conversation.group.members.associate { it.accountId to it.username })
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().testTag("flex_input")) {
                Composer(draft, { onDraftChange(it) }, { onSend() }, onPick, onVoice, isRecording, busy, mediaProgress)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessageHistory(messages, conversation.group.members.associate { it.accountId to it.username })
            }
            Composer(draft, { onDraftChange(it) }, { onSend() }, onPick, onVoice, isRecording, busy, mediaProgress)
        }
    }
}

@Composable
private fun GroupMembersPanel(
    conversation: GroupConversation,
    currentAccountId: String,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        conversation.group.members.forEach { member ->
            GroupMemberRow(member, currentAccountId == conversation.group.ownerAccountId, onRemoveMember)
        }
        if (currentAccountId == conversation.group.ownerAccountId) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("@username нового участника") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("add_member_input"),
            )
            Button(
                onClick = {
                    onAddMember(username)
                    username = ""
                },
                enabled = username.isNotBlank(),
                modifier = Modifier.testTag("add_member_button"),
            ) {
                Text("Добавить участника")
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMemberResponse,
    canRemove: Boolean,
    onRemove: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("@${member.username}")
            Text(member.role, style = MaterialTheme.typography.bodySmall)
        }
        if (canRemove && member.role != "OWNER") {
            TextButton(onClick = { onRemove(member.accountId) }, modifier = Modifier.testTag("remove_member_${member.accountId}")) {
                Text("Удалить")
            }
        }
    }
}

private fun groupErrorText(code: String): String = when (code) {
    "RECIPIENT_NOT_READY" -> "Участник должен хотя бы раз войти в приложение перед добавлением"
    "USER_NOT_FOUND" -> "Пользователь не найден"
    "NETWORK_ERROR" -> "Сервер недоступен. Попробуйте ещё раз"
    "INVALID_GROUP_TITLE" -> "Введите название группы длиной до 80 символов"
    "INVALID_GROUP_MEMBERS" -> "Проверьте список участников"
    "GROUP_TOO_LARGE" -> "В группе может быть не больше 50 участников"
    "GROUP_CREATE_FAILED" -> "Не удалось создать зашифрованную группу"
    "GROUP_MEMBER_ADD_FAILED" -> "Не удалось добавить участника"
    "GROUP_MEMBER_REMOVE_FAILED" -> "Не удалось удалить участника"
    else -> "Действие не выполнено. Попробуйте ещё раз"
}
