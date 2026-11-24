package dev.securemessenger.data.e2ee

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.DisplayableFingerprint
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.fingerprint.ScannableFingerprint

class SafetyNumberService {
    fun create(local: SafetyNumberParty, remote: SafetyNumberParty): SafetyNumber {
        val fingerprint = NumericFingerprintGenerator(5200).createFor(
            FINGERPRINT_VERSION,
            local.stableId(),
            local.identityKey,
            remote.stableId(),
            remote.identityKey,
        )
        return SafetyNumber(
            displayable = fingerprint.displayableFingerprint,
            scannable = fingerprint.scannableFingerprint,
        )
    }

    fun qrMatrix(safetyNumber: SafetyNumber): Array<BooleanArray> {
        val contents = String(safetyNumber.scannable.serialized, Charsets.ISO_8859_1)
        val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 256, 256)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    }

    private fun SafetyNumberParty.stableId(): ByteArray =
        "$accountId:$deviceId".toByteArray(Charsets.UTF_8)

    private companion object {
        const val FINGERPRINT_VERSION = 2
    }
}

data class SafetyNumberParty(
    val accountId: String,
    val deviceId: String,
    val identityKey: IdentityKey,
)

data class SafetyNumber(
    val displayable: DisplayableFingerprint,
    val scannable: ScannableFingerprint,
)
