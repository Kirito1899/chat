package dev.securemessenger.server.api

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitTest {
    @Test
    fun `limit is per key and resets only after its window`() {
        var now = Instant.parse("2026-11-23T12:00:00Z")
        val clock = object : Clock() {
            override fun instant(): Instant = now
            override fun getZone(): ZoneOffset = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
        }
        val limiter = SimpleRateLimiter(clock)
        val window = Duration.ofMinutes(5)

        repeat(5) { assertTrue(limiter.tryAcquire("ip:login", 5, window)) }
        assertFalse(limiter.tryAcquire("ip:login", 5, window))
        assertTrue(limiter.tryAcquire("other:login", 5, window))
        now += Duration.ofMinutes(4)
        assertFalse(limiter.tryAcquire("ip:login", 5, window))
        now += Duration.ofMinutes(1)
        assertTrue(limiter.tryAcquire("ip:login", 5, window))
    }
}
