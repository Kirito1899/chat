package dev.securemessenger.data.sync

import dev.securemessenger.data.remote.ApiException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerEventClientTest {
    @Test
    fun `websocket authenticates and accepts only supported events`() = runTest {
        withContext(Dispatchers.IO) {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("{\"event\":\"UNSUPPORTED\"}")
                            webSocket.send("not-json")
                            webSocket.send("{\"event\":\"SYNC\"}")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                ),
            )
            server.start()
            try {
                val events = AtomicInteger()
                val received = CompletableDeferred<Unit>()
                val connection = ServerEventClient(server.url("/").toString(), OkHttpClient(), Json).connect("test-token") {
                    if (events.incrementAndGet() == 1) received.complete(Unit)
                }
                connection.awaitOpen()
                withTimeout(5_000) { received.await() }

                assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
                assertEquals(1, events.get())
                connection.close()
                assertTrue(connection.awaitFailure() is IOException)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `unauthorized websocket exposes the authentication failure`() = runTest {
        withContext(Dispatchers.IO) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            try {
                val connection = ServerEventClient(server.url("/").toString(), OkHttpClient(), Json).connect("expired-token") {}
                val error = assertFailsWith<ApiException> { connection.awaitOpen() }
                assertEquals(401, error.httpStatus)
            } finally {
                server.shutdown()
            }
        }
    }
}
