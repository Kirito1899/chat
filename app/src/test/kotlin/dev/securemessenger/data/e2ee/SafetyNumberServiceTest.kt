package dev.securemessenger.data.e2ee

import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafetyNumberServiceTest {
    @Test
    fun fingerprint_has_sixty_digits_and_qr_matrix() {
        val localIdentity = IdentityKeyPair.generate().publicKey
        val remoteIdentity = IdentityKeyPair.generate().publicKey
        val service = SafetyNumberService()
        val safetyNumber = service.create(
            SafetyNumberParty(UUID.randomUUID().toString(), UUID.randomUUID().toString(), localIdentity),
            SafetyNumberParty(UUID.randomUUID().toString(), UUID.randomUUID().toString(), remoteIdentity),
        )

        assertEquals(60, safetyNumber.displayable.displayText.filter(Char::isDigit).length)
        val matrix = service.qrMatrix(safetyNumber)
        assertTrue(matrix.isNotEmpty())
        assertTrue(matrix.size > 20)
        assertTrue(matrix.any { row -> row.any { it } })
    }

    @Test
    fun fingerprint_is_symmetric_and_bound_to_both_parties_and_keys() {
        val service = SafetyNumberService()
        val alice = SafetyNumberParty(UUID.randomUUID().toString(), UUID.randomUUID().toString(), IdentityKeyPair.generate().publicKey)
        val bob = SafetyNumberParty(UUID.randomUUID().toString(), UUID.randomUUID().toString(), IdentityKeyPair.generate().publicKey)

        val aliceView = service.create(alice, bob)
        val bobView = service.create(bob, alice)

        assertEquals(aliceView.displayable.displayText, bobView.displayable.displayText)
        assertTrue(aliceView.scannable.compareTo(bobView.scannable.serialized))
        assertTrue(bobView.scannable.compareTo(aliceView.scannable.serialized))
        val changedBobView = service.create(alice, bob.copy(identityKey = IdentityKeyPair.generate().publicKey))
        assertNotEquals(aliceView.displayable.displayText, changedBobView.displayable.displayText)
        assertFalse(aliceView.scannable.compareTo(changedBobView.scannable.serialized))
        assertNotEquals(
            aliceView.displayable.displayText,
            service.create(alice, bob.copy(deviceId = UUID.randomUUID().toString())).displayable.displayText,
        )
    }
}
