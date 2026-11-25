package dev.securemessenger.data.sync

import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.data.remote.EndpointPool
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Serializable
private data class ServerEvent(val event: String)

class ServerEventClient(
    private val baseUrl: String,
    client: OkHttpClient,
    private val json: Json,
    fallbackBaseUrl: String? = null,
) {
    private val endpoints = EndpointPool(baseUrl, fallbackBaseUrl)
    private val webSocketClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(accessToken: String, onEvent: () -> Unit): ServerEventConnection {
        val url = endpoints.route(
            baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/events").build(),
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return ServerEventConnection(webSocketClient, request, json, onEvent) {
            endpoints.onConnectionOpen(url)
        }
    }

    suspend fun connectWithFailover(accessToken: String, onEvent: () -> Unit): ServerEventConnection {
        val first = connect(accessToken, onEvent)
        try {
            first.awaitOpen()
            return first
        } catch (error: Throwable) {
            first.cancel()
            if (!endpoints.onConnectionFailure(first.endpointUrl, error)) throw error
        }
        val second = connect(accessToken, onEvent)
        try {
            second.awaitOpen()
            return second
        } catch (error: Throwable) {
            second.cancel()
            endpoints.onConnectionFailure(second.endpointUrl, error)
            throw error
        }
    }
}

class ServerEventConnection(
    client: OkHttpClient,
    request: Request,
    json: Json,
    private val onEvent: () -> Unit,
    val endpointUrl: HttpUrl = request.url,
    private val onConnected: () -> Unit = {},
) {
    private val opened = CompletableDeferred<Unit>()
    private val failure = CompletableDeferred<Throwable>()
    private val socket: WebSocket

    init {
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onConnected()
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { json.decodeFromString<ServerEvent>(text) }.getOrNull()
                    if (event?.event == "SYNC" || event?.event == "DELIVERED") onEvent()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    fail(IOException("Event stream closed by server"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail(IOException("Event stream closed"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(if (response?.code == 401) ApiException("INVALID_CREDENTIALS", 401) else t)
                }
            },
        )
    }

    suspend fun awaitOpen() = withTimeout(10_000) { opened.await() }

    suspend fun awaitFailure(): Throwable = failure.await()

    fun close() {
        socket.close(1000, null)
    }

    fun cancel() {
        socket.cancel()
    }

    private fun fail(error: Throwable) {
        opened.completeExceptionally(error)
        failure.complete(error)
    }
}
