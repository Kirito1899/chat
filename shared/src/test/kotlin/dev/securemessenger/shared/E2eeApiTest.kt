package dev.securemessenger.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class E2eeApiTest {
    private val ec = java.util.Base64.getEncoder().encodeToString(ByteArray(33))
    private val kem = java.util.Base64.getEncoder().encodeToString(ByteArray(1569))
    private val signature = java.util.Base64.getEncoder().encodeToString(ByteArray(64))

    @Test
    fun `device key upload validates bounded public key material`() {
        val upload = DeviceKeysUpload(
            registrationId = 123,
            identityKey = ec,
            signedPreKey = SignedPreKeyDto(1, ec, signature),
            preKeys = listOf(PreKeyDto(2, ec)),
            kyberPreKeys = listOf(KyberPreKeyDto(3, kem, signature)),
            lastResortKyberPreKey = KyberPreKeyDto(4, kem, signature),
        )
        upload.validate()
        assertFailsWith<ValidationException> {
            upload.copy(identityKey = "not base64").validate()
        }
        assertFailsWith<ValidationException> {
            upload.copy(preKeys = List(101) { PreKeyDto(it + 1, ec) }).validate()
        }
    }

    @Test
    fun `prekey bundle supports exhausted optional ec prekey`() {
        val bundle = PreKeyBundleResponse(
            accountId = "account",
            deviceId = "device",
            registrationId = 1,
            identityKey = ec,
            signedPreKey = SignedPreKeyDto(1, ec, signature),
            preKey = null,
            kyberPreKey = KyberPreKeyDto(2, kem, signature),
            remainingPreKeys = 0,
        )
        val decoded = Json.decodeFromString<PreKeyBundleResponse>(Json.encodeToString(bundle))
        assertNull(decoded.preKey)
        assertEquals(bundle, decoded)
    }
}
