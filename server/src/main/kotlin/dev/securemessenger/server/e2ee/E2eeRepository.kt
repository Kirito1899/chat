package dev.securemessenger.server.e2ee

import dev.securemessenger.server.groups.GroupConflictException
import dev.securemessenger.server.media.MediaAccessException
import dev.securemessenger.server.media.MediaNotReadyException
import dev.securemessenger.server.storage.transaction
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

class IdentityChangeException : RuntimeException("Device identity is immutable")
class RecipientNotFoundException : RuntimeException("Recipient device is unavailable")
class MessageAuthorizationException : RuntimeException("Message is not available to this device")
class MessageConflictException : RuntimeException("Message ID was reused for different content")

interface E2eeRepository {
    fun storeDeviceKeys(accountId: UUID, deviceId: UUID, upload: DeviceKeysUpload)
    fun fetchBundleByUsername(username: String): PreKeyBundleResponse?
    fun enqueue(senderAccountId: UUID, senderDeviceId: UUID, request: SendMessageRequest)
    fun enqueueGroup(senderAccountId: UUID, senderDeviceId: UUID, groupId: UUID, groupMessageId: UUID, request: SendMessageRequest)
    fun fetchUndelivered(deviceId: UUID): List<IncomingMessage>
    fun markDelivered(messageId: UUID, recipientDeviceId: UUID): Boolean
    fun deliveredSenderDevice(messageId: UUID, recipientDeviceId: UUID): UUID?
    fun deliveredForSender(senderDeviceId: UUID): List<String>
}

class JdbcE2eeRepository(private val dataSource: DataSource) : E2eeRepository {
    override fun storeDeviceKeys(accountId: UUID, deviceId: UUID, upload: DeviceKeysUpload) {
        upload.validate()
        dataSource.transaction { connection ->
            val owner = connection.queryOne(
                "SELECT account_id FROM devices WHERE id = ? AND revoked_at IS NULL FOR UPDATE",
                { it.getObject(1, UUID::class.java) },
                deviceId,
            )
            if (owner != accountId) throw MessageAuthorizationException()
            val existing = connection.queryOne(
                "SELECT identity_key FROM device_keys WHERE device_id = ? FOR UPDATE",
                { it.getBytes(1) },
                deviceId,
            )
            val identity = upload.identityKey.decode()
            if (existing != null && !MessageDigest.isEqual(existing, identity)) {
                throw IdentityChangeException()
            }
            if (existing == null) {
                connection.execute(
                    """
                    INSERT INTO device_keys(
                        device_id, account_id, registration_id, identity_key,
                        signed_prekey_id, signed_prekey_public, signed_prekey_signature,
                        last_resort_kyber_id, last_resort_kyber_public, last_resort_kyber_signature
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    deviceId,
                    accountId,
                    upload.registrationId,
                    identity,
                    upload.signedPreKey.id,
                    upload.signedPreKey.publicKey.decode(),
                    upload.signedPreKey.signature.decode(),
                    upload.lastResortKyberPreKey.id,
                    upload.lastResortKyberPreKey.publicKey.decode(),
                    upload.lastResortKyberPreKey.signature.decode(),
                )
            }
            upload.preKeys.forEach { preKey ->
                connection.execute(
                    "INSERT INTO one_time_prekeys(device_id, key_kind, key_id, public_key) VALUES (?, 'EC', ?, ?) ON CONFLICT DO NOTHING",
                    deviceId,
                    preKey.id,
                    preKey.publicKey.decode(),
                )
            }
            upload.kyberPreKeys.forEach { preKey ->
                connection.execute(
                    "INSERT INTO one_time_prekeys(device_id, key_kind, key_id, public_key, signature) VALUES (?, 'KYBER', ?, ?, ?) ON CONFLICT DO NOTHING",
                    deviceId,
                    preKey.id,
                    preKey.publicKey.decode(),
                    preKey.signature.decode(),
                )
            }
        }
    }

    override fun fetchBundleByUsername(username: String): PreKeyBundleResponse? = dataSource.transaction { connection ->
        val device = connection.queryOne(
            """
            SELECT dk.device_id, dk.account_id, dk.registration_id, dk.identity_key,
                   dk.signed_prekey_id, dk.signed_prekey_public, dk.signed_prekey_signature,
                   dk.last_resort_kyber_id, dk.last_resort_kyber_public, dk.last_resort_kyber_signature
            FROM device_keys dk
            JOIN accounts a ON a.id = dk.account_id
            JOIN devices d ON d.id = dk.device_id
            WHERE a.username_normalized = ? AND a.email_verified = TRUE AND d.revoked_at IS NULL
            FOR UPDATE OF dk
            """,
            { rs ->
                DeviceKeyRow(
                    deviceId = rs.getObject("device_id", UUID::class.java),
                    accountId = rs.getObject("account_id", UUID::class.java),
                    registrationId = rs.getInt("registration_id"),
                    identityKey = rs.getBytes("identity_key"),
                    signedPreKey = SignedPreKeyDto(
                        rs.getInt("signed_prekey_id"),
                        rs.getBytes("signed_prekey_public").encode(),
                        rs.getBytes("signed_prekey_signature").encode(),
                    ),
                    lastResort = KyberPreKeyDto(
                        rs.getInt("last_resort_kyber_id"),
                        rs.getBytes("last_resort_kyber_public").encode(),
                        rs.getBytes("last_resort_kyber_signature").encode(),
                    ),
                )
            },
            username,
        ) ?: return@transaction null

        val preKey = connection.consumePreKey(device.deviceId, "EC")?.let {
            PreKeyDto(it.id, it.publicKey.encode())
        }
        val kyber = connection.consumePreKey(device.deviceId, "KYBER")?.let {
            KyberPreKeyDto(it.id, it.publicKey.encode(), it.signature!!.encode())
        } ?: device.lastResort
        val remaining = connection.queryOne(
            "SELECT count(*) FROM one_time_prekeys WHERE device_id = ? AND key_kind = 'EC'",
            { it.getInt(1) },
            device.deviceId,
        ) ?: 0
        PreKeyBundleResponse(
            accountId = device.accountId.toString(),
            deviceId = device.deviceId.toString(),
            registrationId = device.registrationId,
            identityKey = device.identityKey.encode(),
            signedPreKey = device.signedPreKey,
            preKey = preKey,
            kyberPreKey = kyber,
            remainingPreKeys = remaining,
        )
    }

    override fun enqueue(senderAccountId: UUID, senderDeviceId: UUID, request: SendMessageRequest) {
        enqueueInternal(senderAccountId, senderDeviceId, null, null, request)
    }

    override fun enqueueGroup(senderAccountId: UUID, senderDeviceId: UUID, groupId: UUID, groupMessageId: UUID, request: SendMessageRequest) {
        enqueueInternal(senderAccountId, senderDeviceId, groupId, groupMessageId, request)
    }

    private fun enqueueInternal(
        senderAccountId: UUID,
        senderDeviceId: UUID,
        groupId: UUID?,
        groupMessageId: UUID?,
        request: SendMessageRequest,
    ) {
        request.validate()
        val messageId = UUID.fromString(request.messageId)
        val recipient = UUID.fromString(request.recipientDeviceId)
        val mediaId = request.mediaId?.let(UUID::fromString)
        val ciphertext = request.ciphertext.decode()
        dataSource.transaction { connection ->
            connection.execute("DELETE FROM message_queue WHERE expires_at <= now()")
            val senderAuthorized = connection.queryOne(
                "SELECT 1 FROM devices WHERE id = ? AND account_id = ? AND revoked_at IS NULL",
                { it.getInt(1) },
                senderDeviceId,
                senderAccountId,
            ) != null
            if (!senderAuthorized) throw MessageAuthorizationException()
            if (groupId != null) {
                val senderMember = connection.queryOne(
                    "SELECT 1 FROM group_members WHERE group_id = ? AND account_id = ? AND removed_at IS NULL",
                    { it.getInt(1) },
                    groupId,
                    senderAccountId,
                ) != null
                if (!senderMember) throw MessageAuthorizationException()
            }
            val recipientExists = if (groupId == null) {
                connection.queryOne(
                    "SELECT 1 FROM devices WHERE id = ? AND revoked_at IS NULL",
                    { it.getInt(1) },
                    recipient,
                ) != null
            } else {
                connection.queryOne(
                    """
                    SELECT 1 FROM devices d
                    JOIN group_members gm ON gm.account_id = d.account_id
                    WHERE d.id = ? AND d.revoked_at IS NULL AND gm.group_id = ? AND gm.removed_at IS NULL
                    """,
                    { it.getInt(1) },
                    recipient,
                    groupId,
                ) != null
            }
            if (!recipientExists) throw RecipientNotFoundException()
            if (mediaId != null) {
                val media = connection.queryOne(
                    "SELECT owner_account_id, status FROM media_objects WHERE id = ? FOR UPDATE",
                    { rs -> rs.getObject("owner_account_id", UUID::class.java) to rs.getString("status") },
                    mediaId,
                ) ?: throw MediaAccessException()
                if (media.first != senderAccountId) throw MediaAccessException()
                if (media.second != "READY") throw MediaNotReadyException()
            }
            if (groupId != null && groupMessageId != null) {
                val insertedGroupMessage = connection.queryOne(
                    """
                    INSERT INTO group_messages(id, group_id, sender_account_id, sender_device_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    RETURNING id
                    """,
                    { it.getObject(1, UUID::class.java) },
                    groupMessageId,
                    groupId,
                    senderAccountId,
                    senderDeviceId,
                )
                if (insertedGroupMessage == null) {
                    val groupMessage = connection.queryOne(
                        "SELECT group_id, sender_device_id FROM group_messages WHERE id = ? FOR UPDATE",
                        { rs -> rs.getObject("group_id", UUID::class.java) to rs.getObject("sender_device_id", UUID::class.java) },
                        groupMessageId,
                    ) ?: throw GroupConflictException()
                    if (groupMessage.first != groupId || groupMessage.second != senderDeviceId) throw GroupConflictException()
                }
            }
            val now = Instant.now()
            val inserted = connection.queryOne(
                """
                INSERT INTO message_queue(
                    message_id, recipient_device_id, sender_account_id, sender_device_id,
                    ciphertext_type, ciphertext, created_at, expires_at, group_id, group_message_id, media_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING message_id
                """,
                { it.getObject(1, UUID::class.java) },
                messageId,
                recipient,
                senderAccountId,
                senderDeviceId,
                request.ciphertextType,
                ciphertext,
                now,
                now + Duration.ofDays(7),
                groupId,
                groupMessageId,
                mediaId,
            )
            if (inserted == null) {
                val existing = connection.queryOne(
                    """
                    SELECT sender_device_id, ciphertext_type, ciphertext, group_id, group_message_id, media_id
                    FROM message_queue WHERE message_id = ? AND recipient_device_id = ?
                    """,
                    { rs ->
                        ExistingMessage(
                            rs.getObject("sender_device_id", UUID::class.java),
                            rs.getInt("ciphertext_type"),
                            rs.getBytes("ciphertext"),
                            rs.getObject("group_id", UUID::class.java),
                            rs.getObject("group_message_id", UUID::class.java),
                            rs.getObject("media_id", UUID::class.java),
                        )
                    },
                    messageId,
                    recipient,
                ) ?: throw MessageConflictException()
                if (existing.senderDeviceId != senderDeviceId || existing.type != request.ciphertextType ||
                    !MessageDigest.isEqual(existing.ciphertext, ciphertext) || existing.groupId != groupId ||
                    existing.groupMessageId != groupMessageId || existing.mediaId != mediaId
                ) {
                    throw MessageConflictException()
                }
            }
            if (mediaId != null) {
                connection.execute(
                    "INSERT INTO media_recipients(media_id, device_id, group_message_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    mediaId,
                    recipient,
                    groupMessageId,
                )
            }
        }
    }

    override fun fetchUndelivered(deviceId: UUID): List<IncomingMessage> = dataSource.connection.use { connection ->
        connection.queryAll(
            """
            SELECT message_id, sender_account_id, sender_device_id, ciphertext_type, ciphertext, created_at, group_id, group_message_id
            FROM message_queue
            WHERE recipient_device_id = ? AND delivered_at IS NULL AND expires_at > now()
            ORDER BY created_at
            LIMIT 100
            """,
            { rs ->
                IncomingMessage(
                    messageId = rs.getObject("message_id", UUID::class.java).toString(),
                    senderAccountId = rs.getObject("sender_account_id", UUID::class.java).toString(),
                    senderDeviceId = rs.getObject("sender_device_id", UUID::class.java).toString(),
                    ciphertextType = rs.getInt("ciphertext_type"),
                    ciphertext = rs.getBytes("ciphertext").encode(),
                    createdAt = rs.getTimestamp("created_at").toInstant().toEpochMilli(),
                    groupId = rs.getObject("group_id", UUID::class.java)?.toString(),
                    groupMessageId = rs.getObject("group_message_id", UUID::class.java)?.toString(),
                )
            },
            deviceId,
        )
    }

    override fun markDelivered(messageId: UUID, recipientDeviceId: UUID): Boolean = dataSource.transaction { connection ->
        val updated = connection.execute(
            "UPDATE message_queue SET delivered_at = now() WHERE message_id = ? AND recipient_device_id = ? AND delivered_at IS NULL",
            messageId,
            recipientDeviceId,
        )
        if (updated > 0) return@transaction true
        val alreadyDelivered = connection.queryOne(
            "SELECT 1 FROM message_queue WHERE message_id = ? AND recipient_device_id = ? AND delivered_at IS NOT NULL",
            { it.getInt(1) },
            messageId,
            recipientDeviceId,
        ) != null
        if (alreadyDelivered) true else throw MessageAuthorizationException()
    }

    override fun deliveredSenderDevice(messageId: UUID, recipientDeviceId: UUID): UUID? = dataSource.connection.use { connection ->
        connection.queryOne(
            "SELECT sender_device_id FROM message_queue WHERE message_id = ? AND recipient_device_id = ? AND delivered_at IS NOT NULL",
            { it.getObject(1, UUID::class.java) },
            messageId,
            recipientDeviceId,
        )
    }

    override fun deliveredForSender(senderDeviceId: UUID): List<String> = dataSource.connection.use { connection ->
        connection.queryAll(
            "SELECT message_id FROM message_queue WHERE sender_device_id = ? AND delivered_at IS NOT NULL",
            { it.getObject(1, UUID::class.java).toString() },
            senderDeviceId,
        )
    }

    private fun Connection.consumePreKey(deviceId: UUID, kind: String): ConsumedPreKey? = queryOne(
        """
        DELETE FROM one_time_prekeys
        WHERE ctid IN (
            SELECT ctid FROM one_time_prekeys
            WHERE device_id = ? AND key_kind = ?
            ORDER BY key_id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        RETURNING key_id, public_key, signature
        """,
        { rs -> ConsumedPreKey(rs.getInt("key_id"), rs.getBytes("public_key"), rs.getBytes("signature")) },
        deviceId,
        kind,
    )

    private data class DeviceKeyRow(
        val deviceId: UUID,
        val accountId: UUID,
        val registrationId: Int,
        val identityKey: ByteArray,
        val signedPreKey: SignedPreKeyDto,
        val lastResort: KyberPreKeyDto,
    )

    private data class ConsumedPreKey(val id: Int, val publicKey: ByteArray, val signature: ByteArray?)
    private data class ExistingMessage(
        val senderDeviceId: UUID,
        val type: Int,
        val ciphertext: ByteArray,
        val groupId: UUID?,
        val groupMessageId: UUID?,
        val mediaId: UUID?,
    )
}

private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)
private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)

private fun PreparedStatement.bind(parameters: Array<out Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            else -> setObject(index + 1, value)
        }
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
