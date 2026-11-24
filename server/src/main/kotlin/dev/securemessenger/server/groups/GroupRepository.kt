package dev.securemessenger.server.groups

import dev.securemessenger.server.storage.transaction
import dev.securemessenger.shared.GroupMemberResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.UsernameNormalizer
import dev.securemessenger.shared.ValidationException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class GroupNotFoundException : RuntimeException("Group not found")
class GroupAccessException : RuntimeException("Group access denied")
class GroupMemberNotFoundException : RuntimeException("Group member not found")
class GroupConflictException : RuntimeException("Group message conflict")

interface GroupRepository {
    fun create(ownerAccountId: UUID, memberUsernames: List<String>): GroupResponse
    fun list(accountId: UUID): List<GroupResponse>
    fun getForMember(accountId: UUID, groupId: UUID): GroupResponse?
    fun addMembers(ownerAccountId: UUID, groupId: UUID, memberUsernames: List<String>): GroupResponse
    fun removeMember(ownerAccountId: UUID, groupId: UUID, targetAccountId: UUID): GroupResponse
}

class JdbcGroupRepository(private val dataSource: DataSource) : GroupRepository {
    override fun create(ownerAccountId: UUID, memberUsernames: List<String>): GroupResponse = dataSource.transaction { connection ->
        val accounts = accountsForUsernames(connection, memberUsernames)
        if (accounts.values.all { it == ownerAccountId }) throw ValidationException("INVALID_GROUP_MEMBERS")
        val groupId = UUID.randomUUID()
        connection.execute("INSERT INTO groups(id, owner_account_id) VALUES (?, ?)", groupId, ownerAccountId)
        connection.execute("INSERT INTO group_members(group_id, account_id, role) VALUES (?, ?, 'OWNER')", groupId, ownerAccountId)
        accounts.values.filter { it != ownerAccountId }.forEach { accountId ->
            connection.execute("INSERT INTO group_members(group_id, account_id, role) VALUES (?, ?, 'MEMBER')", groupId, accountId)
        }
        groupResponse(connection, groupId, ownerAccountId) ?: throw GroupNotFoundException()
    }

    override fun list(accountId: UUID): List<GroupResponse> = dataSource.connection.use { connection ->
        val ids = connection.queryAll(
            "SELECT group_id FROM group_members WHERE account_id = ? AND removed_at IS NULL ORDER BY joined_at",
            { it.getObject(1, UUID::class.java) },
            accountId,
        )
        ids.mapNotNull { groupId -> groupResponse(connection, groupId, accountId) }
    }

    override fun getForMember(accountId: UUID, groupId: UUID): GroupResponse? = dataSource.connection.use { connection ->
        groupResponse(connection, groupId, accountId)
    }

    override fun addMembers(ownerAccountId: UUID, groupId: UUID, memberUsernames: List<String>): GroupResponse = dataSource.transaction { connection ->
        requireOwner(connection, groupId, ownerAccountId)
        val accounts = accountsForUsernames(connection, memberUsernames)
        val activeCount = connection.queryOne(
            "SELECT count(*) FROM group_members WHERE group_id = ? AND removed_at IS NULL",
            { it.getInt(1) },
            groupId,
        ) ?: 0
        val newCount = accounts.values.count { accountId ->
            connection.queryOne(
                "SELECT 1 FROM group_members WHERE group_id = ? AND account_id = ? AND removed_at IS NULL",
                { it.getInt(1) },
                groupId,
                accountId,
            ) == null
        }
        if (activeCount + newCount > MAX_GROUP_MEMBERS) throw ValidationException("GROUP_TOO_LARGE")
        accounts.values.filter { it != ownerAccountId }.forEach { accountId ->
            connection.execute(
                """
                INSERT INTO group_members(group_id, account_id, role, joined_at, removed_at)
                VALUES (?, ?, 'MEMBER', now(), NULL)
                ON CONFLICT (group_id, account_id) DO UPDATE
                SET role = 'MEMBER', joined_at = now(), removed_at = NULL
                """,
                groupId,
                accountId,
            )
        }
        groupResponse(connection, groupId, ownerAccountId) ?: throw GroupNotFoundException()
    }

    override fun removeMember(ownerAccountId: UUID, groupId: UUID, targetAccountId: UUID): GroupResponse = dataSource.transaction { connection ->
        requireOwner(connection, groupId, ownerAccountId)
        if (targetAccountId == ownerAccountId) throw ValidationException("OWNER_REMOVAL_NOT_ALLOWED")
        connection.execute(
            "UPDATE group_members SET removed_at = now() WHERE group_id = ? AND account_id = ? AND removed_at IS NULL",
            groupId,
            targetAccountId,
        )
        connection.execute(
            """
            DELETE FROM message_queue
            WHERE group_id = ? AND recipient_device_id IN (
                SELECT id FROM devices WHERE account_id = ?
            )
            """,
            groupId,
            targetAccountId,
        )
        groupResponse(connection, groupId, ownerAccountId) ?: throw GroupNotFoundException()
    }

    private fun requireOwner(connection: Connection, groupId: UUID, accountId: UUID) {
        val owner = connection.queryOne(
            "SELECT owner_account_id FROM groups WHERE id = ? FOR UPDATE",
            { it.getObject(1, UUID::class.java) },
            groupId,
        ) ?: throw GroupNotFoundException()
        if (owner != accountId) throw GroupAccessException()
    }

    private fun accountsForUsernames(connection: Connection, rawUsernames: List<String>): Map<String, UUID> {
        val usernames = rawUsernames.map { UsernameNormalizer.normalize(it) }.toSet()
        if (usernames.isEmpty() || usernames.size >= MAX_GROUP_MEMBERS) throw ValidationException("INVALID_GROUP_MEMBERS")
        return usernames.associate { username ->
            val accountId = connection.queryOne(
                "SELECT id FROM accounts WHERE username_normalized = ? AND email_verified = TRUE",
                { it.getObject(1, UUID::class.java) },
                username,
            ) ?: throw GroupMemberNotFoundException()
            username to accountId
        }
    }

    private fun groupResponse(connection: Connection, groupId: UUID, accountId: UUID): GroupResponse? {
        val owner = connection.queryOne(
            """
            SELECT g.owner_account_id FROM groups g
            JOIN group_members gm ON gm.group_id = g.id
            WHERE g.id = ? AND gm.account_id = ? AND gm.removed_at IS NULL
            """,
            { it.getObject(1, UUID::class.java) },
            groupId,
            accountId,
        ) ?: return null
        val members = connection.queryAll(
            """
            SELECT gm.account_id, a.username, gm.role FROM group_members gm
            JOIN accounts a ON a.id = gm.account_id
            WHERE gm.group_id = ? AND gm.removed_at IS NULL
            ORDER BY gm.joined_at, a.username
            """,
            { rs ->
                GroupMemberResponse(
                    accountId = rs.getObject("account_id", UUID::class.java).toString(),
                    username = rs.getString("username"),
                    role = rs.getString("role"),
                )
            },
            groupId,
        )
        return GroupResponse(groupId.toString(), owner.toString(), members)
    }

    private companion object {
        const val MAX_GROUP_MEMBERS = 50
    }
}

private fun <T> Connection.queryOne(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): T? =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
    }

private fun <T> Connection.queryAll(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(mapper(rs)) }
        }
    }

private fun Connection.execute(sql: String, vararg parameters: Any?): Int = prepareStatement(sql).use { statement ->
    statement.bind(parameters)
    statement.executeUpdate()
}

private fun PreparedStatement.bind(parameters: Array<out Any?>) {
    parameters.forEachIndexed { index, value -> setObject(index + 1, value) }
}
