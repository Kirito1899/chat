package dev.securemessenger.server.api

import dev.securemessenger.shared.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RateLimitException : RuntimeException("Rate limited")

class SimpleRateLimiter(private val clock: Clock) {
    private data class Window(val startedAt: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    @Synchronized
    fun tryAcquire(key: String, limit: Int, duration: Duration): Boolean {
        val now = clock.millis()
        val durationMillis = duration.toMillis()
        if (windows.size > 10_000) {
            windows.entries.removeIf { now - it.value.startedAt >= durationMillis }
        }
        val current = windows[key]
        if (current == null || now - current.startedAt >= durationMillis) {
            windows[key] = Window(now, 1)
            return true
        }
        if (current.count >= limit) return false
        current.count++
        return true
    }
}

fun Application.installRateLimiting(clock: Clock = Clock.systemUTC()) {
    val limiter = SimpleRateLimiter(clock)
    val plugin = createApplicationPlugin("AuthRateLimiting") {
        on(CallSetup) { call ->
            val path = call.request.path()
            val rule = when {
                path == "/v1/auth/recovery" || path == "/v1/auth/resend-verification" -> 5 to Duration.ofMinutes(15)
                path == "/v1/auth/register" -> 10 to Duration.ofHours(1)
                path == "/v1/auth/login" -> 10 to Duration.ofMinutes(5)
                path == "/v1/auth/reset" -> 10 to Duration.ofMinutes(15)
                path.startsWith("/v1/prekey-bundles/") -> 20 to Duration.ofMinutes(5)
                path.startsWith("/v1/admin/") -> 20 to Duration.ofMinutes(1)
                else -> null
            }
            if (rule != null) {
                val key = "${call.request.origin.remoteHost}:$path"
                if (!limiter.tryAcquire(key, rule.first, rule.second)) {
                    throw RateLimitException()
                }
            }
        }
    }
    install(plugin)
}
