package dev.securemessenger.data.remote

import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class EndpointSnapshot(
    val activeOrigin: String,
    val failovers: Int,
    val lastFailoverMillis: Long,
)

class EndpointPool(primaryBaseUrl: String, fallbackBaseUrl: String?) {
    private val primary = endpoint(primaryBaseUrl)
    private val fallback = fallbackBaseUrl?.takeIf { it.isNotBlank() }?.let(::endpoint)
    private var active = primary
    private var switchStarted = 0L
    private var failovers = 0
    private var lastFailoverMillis = 0L

    @Synchronized
    fun route(url: HttpUrl): HttpUrl = url.newBuilder()
        .scheme(active.scheme)
        .host(active.host)
        .port(active.port)
        .build()

    @Synchronized
    fun onConnectionFailure(url: HttpUrl, error: Throwable): Boolean {
        if (error !is ConnectException && error !is UnknownHostException) return false
        val failed = endpoint(url)
        val next = when {
            active != failed -> active
            failed == primary -> fallback
            else -> primary
        }
        if (next == null || next == failed) return false
        if (next != active) {
            active = next
            switchStarted = System.nanoTime()
        }
        return true
    }

    @Synchronized
    fun onConnectionOpen(url: HttpUrl) {
        val opened = endpoint(url)
        if (active == opened) {
            if (switchStarted != 0L) {
                failovers++
                lastFailoverMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - switchStarted)
                switchStarted = 0L
            }
            active = opened
        }
    }

    fun <T> execute(url: HttpUrl, call: (HttpUrl) -> T): T {
        val first = route(url)
        try {
            return call(first)
        } catch (error: Throwable) {
            if (!onConnectionFailure(first, error)) throw error
        }
        val routed = route(url)
        try {
            return call(routed).also { onConnectionOpen(routed) }
        } catch (error: Throwable) {
            onConnectionFailure(routed, error)
            throw error
        }
    }

    @Synchronized
    fun snapshot() = EndpointSnapshot(
        HttpUrl.Builder().scheme(active.scheme).host(active.host).port(active.port).build().toString(),
        failovers,
        lastFailoverMillis,
    )

    private data class EndpointOrigin(val scheme: String, val host: String, val port: Int)

    private fun endpoint(url: HttpUrl) = EndpointOrigin(url.scheme, url.host, url.port)

    private fun endpoint(url: String): EndpointOrigin {
        val parsed = url.toHttpUrl()
        require(parsed.encodedPath == "/") { "API endpoints must be origin URLs" }
        return endpoint(parsed)
    }
}
