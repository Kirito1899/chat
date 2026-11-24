package dev.securemessenger.data

import dev.securemessenger.data.local.GroupInfoDao
import dev.securemessenger.data.local.GroupInfoEntity
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.ValidationException

data class GroupConversation(
    val group: GroupResponse,
    val title: String,
)

class GroupChatRepository(
    private val groupInfo: GroupInfoDao,
    private val accounts: AuthRepository,
    private val messages: MessageRepository,
) {
    suspend fun create(title: String, memberUsernames: List<String>): GroupConversation {
        val normalizedTitle = validateTitle(title)
        val group = accounts.createGroup(memberUsernames)
        groupInfo.upsert(GroupInfoEntity(group.groupId, normalizedTitle, group.ownerAccountId))
        messages.sendGroupInfo(group, normalizedTitle)
        return GroupConversation(group, normalizedTitle)
    }

    suspend fun list(): List<GroupConversation> = accounts.listGroups().groups.map { group ->
        GroupConversation(group, groupInfo.get(group.groupId)?.title ?: "Группа")
    }

    suspend fun get(groupId: String): GroupConversation {
        val group = accounts.getGroup(groupId)
        return GroupConversation(group, groupInfo.get(groupId)?.title ?: "Группа")
    }

    suspend fun addMembers(group: GroupResponse, memberUsernames: List<String>): GroupConversation {
        val previous = group.members.map { it.accountId }.toSet()
        val updated = accounts.addGroupMembers(group.groupId, memberUsernames)
        val added = updated.members.map { it.accountId }.toSet() - previous
        if (added.isNotEmpty()) {
            val title = groupInfo.get(group.groupId)?.title ?: "Группа"
            messages.sendGroupInfo(updated, title, added)
        }
        return GroupConversation(updated, groupInfo.get(group.groupId)?.title ?: "Группа")
    }

    suspend fun removeMember(group: GroupResponse, accountId: String): GroupConversation {
        val updated = accounts.removeGroupMember(group.groupId, accountId)
        return GroupConversation(updated, groupInfo.get(group.groupId)?.title ?: "Группа")
    }

    private fun validateTitle(title: String): String {
        val normalized = title.trim()
        if (normalized.isEmpty() || normalized.length > 80) throw ValidationException("INVALID_GROUP_TITLE")
        return normalized
    }
}
