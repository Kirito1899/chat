package dev.securemessenger.data.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointPoolTest {
    @Test
    fun `connection refusal switches to fallback and keeps using it`() = runTest {
        val primary = MockWebServer()
        primary.start()
        val primaryUrl = primary.url("/").toString()
        primary.shutdown()
        val fallback = MockWebServer()
        fallback.enqueue(MockResponse().setBody("{\"accountId\":\"account-1\",\"username\":\"friend_user\"}"))
        fallback.enqueue(MockResponse().setBody("{\"accountId\":\"account-1\",\"username\":\"friend_user\"}"))
        fallback.start()
        try {
            val api = MessengerApi(primaryUrl, OkHttpClient(), Json, fallback.url("/").toString())
            val first = api.findByUsername("friend_user", "")
            val stats = api.endpointStats()
            api.findByUsername("friend_user", "")

            assertEquals("friend_user", first.username)
            assertEquals(fallback.url("/").toString(), stats.activeOrigin)
            assertEquals(1, stats.failovers)
            assertTrue(stats.lastFailoverMillis >= 0)
            assertEquals(2, fallback.requestCount)
            assertEquals(1, api.endpointStats().failovers)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `http error does not trigger failover`() = runTest {
        val primary = MockWebServer()
        primary.enqueue(MockResponse().setResponseCode(500))
        primary.start()
        val fallback = MockWebServer()
        fallback.start()
        try {
            val api = MessengerApi(primary.url("/").toString(), OkHttpClient(), Json, fallback.url("/").toString())
            assertFailsWith<ApiException> { api.findByUsername("friend_user", "") }
            assertEquals(0, fallback.requestCount)
            assertEquals(0, api.endpointStats().failovers)
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `timeout is not blindly retried on another endpoint`() = runTest {
        val fallback = MockWebServer()
        fallback.start()
        val client = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("test timeout") }
            .build()
        try {
            val api = MessengerApi("http://127.0.0.1:1/", client, Json, fallback.url("/").toString())
            assertFailsWith<SocketTimeoutException> { api.findByUsername("friend_user", "") }
            assertEquals(0, fallback.requestCount)
            assertEquals(0, api.endpointStats().failovers)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `concurrent failures never retry an endpoint known to be down`() = runTest {
        withContext(Dispatchers.IO) {
            val pool = EndpointPool("http://primary.test/", "http://backup.test/")
            val started = CountDownLatch(2)
            val fallbackCalls = AtomicInteger()
            val request: (HttpUrl) -> String = { url ->
                started.countDown()
                started.await()
                if (url.host == "primary.test") throw ConnectException("offline")
                fallbackCalls.incrementAndGet()
                "ok"
            }

            val results = listOf(
                async { pool.execute("http://primary.test/v1/users".toHttpUrl(), request) },
                async { pool.execute("http://primary.test/v1/users".toHttpUrl(), request) },
            ).awaitAll()

            assertEquals(listOf("ok", "ok"), results)
            assertEquals(2, fallbackCalls.get())
            assertEquals("http://backup.test/", pool.snapshot().activeOrigin)
            assertTrue(pool.snapshot().failovers >= 1)
        }
    }
}
