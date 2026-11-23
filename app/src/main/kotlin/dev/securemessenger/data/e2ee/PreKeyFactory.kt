package dev.securemessenger.data.e2ee

import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SignedPreKeyDto
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64

class PreKeyFactory {
    fun generateInitial(store: PersistentSignalStore): DeviceKeysUpload {
        val identity = store.identityKeyPair
        val signedId = store.nextPreKeyId()
        val signedPair = ECKeyPair.generate()
        val signedSignature = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
        store.storeSignedPreKey(
            signedId,
            SignedPreKeyRecord(signedId, System.currentTimeMillis(), signedPair, signedSignature),
        )

        val lastResortId = store.nextPreKeyId()
        val lastResortPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val lastResortSignature = identity.privateKey.calculateSignature(lastResortPair.publicKey.serialize())
        store.storeKyberPreKey(
            lastResortId,
            KyberPreKeyRecord(lastResortId, System.currentTimeMillis(), lastResortPair, lastResortSignature),
        )

        val preKeys = List(100) {
            val id = store.nextPreKeyId()
            val pair = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, pair))
            PreKeyDto(id, pair.publicKey.serialize().encode())
        }
        val kyberPreKeys = List(100) {
            val id = store.nextPreKeyId()
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
            store.storeKyberPreKey(id, KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature))
            KyberPreKeyDto(id, pair.publicKey.serialize().encode(), signature.encode())
        }
        return DeviceKeysUpload(
            registrationId = store.localRegistrationId,
            identityKey = identity.publicKey.serialize().encode(),
            signedPreKey = SignedPreKeyDto(signedId, signedPair.publicKey.serialize().encode(), signedSignature.encode()),
            preKeys = preKeys,
            kyberPreKeys = kyberPreKeys,
            lastResortKyberPreKey = KyberPreKeyDto(lastResortId, lastResortPair.publicKey.serialize().encode(), lastResortSignature.encode()),
        )
    }
}

fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
private fun ByteArray.encode(): String = base64()
