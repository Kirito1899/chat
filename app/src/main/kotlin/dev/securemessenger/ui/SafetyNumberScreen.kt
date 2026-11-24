package dev.securemessenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.e2ee.SafetyNumberService

@Composable
fun SafetyNumberScreen(
    safetyNumber: SafetyNumber,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bitmap = remember(safetyNumber) {
        val matrix = SafetyNumberService().qrMatrix(safetyNumber)
        val height = matrix.size
        val width = matrix.first().size
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            matrix.forEachIndexed { y, row ->
                row.forEachIndexed { x, filled ->
                    setPixel(x, y, if (filled) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Проверка безопасности", style = MaterialTheme.typography.headlineSmall)
        Text("Сверьте код с собеседником через другой защищённый канал. Подтверждайте только при полном совпадении.")
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR-код safety number",
            modifier = Modifier.size(256.dp).testTag("safety_qr"),
        )
        Text(
            safetyNumber.displayable.displayText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("safety_number_text"),
        )
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag("confirm_safety")) {
            Text("Подтвердить совпадение")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Назад")
        }
    }
}
