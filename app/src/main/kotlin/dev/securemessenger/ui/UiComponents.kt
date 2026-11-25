package dev.securemessenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemessenger.data.local.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ChatAvatar(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.trim().firstOrNull()?.toString()?.uppercase() ?: "#",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun MessageBubble(message: MessageEntity, latestOutgoing: Boolean, senderName: String? = null) {
    val locale = LocalLocale.current.platformLocale
    val outgoing = message.outgoing
    val container = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (outgoing) 18.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 18.dp,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = container, contentColor = content, shape = shape) {
            Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (senderName != null && !outgoing) {
                    Text("@$senderName", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                if (message.mediaJson != null) {
                    MediaMessageBubble(message.mediaJson, message.body)
                } else {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(
                            when {
                                !outgoing -> "incoming_message_body"
                                latestOutgoing -> "latest_outgoing_body"
                                else -> "outgoing_message_body"
                            },
                        ),
                    )
                }
                Row(
                    Modifier.align(Alignment.End).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        SimpleDateFormat("HH:mm", locale).format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.65f),
                    )
                    if (outgoing) {
                        Icon(
                            imageVector = when (message.status) {
                                "PENDING" -> Icons.Default.Schedule
                                "SKIPPED" -> Icons.Default.ErrorOutline
                                "SENT" -> Icons.Default.Done
                                else -> Icons.Default.DoneAll
                            },
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = content.copy(alpha = 0.7f),
                        )
                        Text(
                            message.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.7f),
                            modifier = Modifier.testTag(if (latestOutgoing) "latest_outgoing_status" else "message_status"),
                        )
                    }
                }
            }
        }
    }
}
