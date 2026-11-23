package dev.securemessenger.server.e2ee

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.consumeEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class EventsHub {
    private val connections = ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketServerSession>>()

    suspend fun listen(deviceId: UUID, session: WebSocketServerSession) {
        val sessions = connections.computeIfAbsent(deviceId) { CopyOnWriteArraySet() }
        sessions += session
        try {
            session.incoming.consumeEach { }
        } finally {
            sessions -= session
            if (sessions.isEmpty()) connections.remove(deviceId, sessions)
        }
    }

    suspend fun notify(deviceId: UUID, event: String) {
        connections[deviceId]?.forEach { session ->
            runCatching { session.send(Frame.Text("{\"event\":\"$event\"}")) }
        }
    }
}
